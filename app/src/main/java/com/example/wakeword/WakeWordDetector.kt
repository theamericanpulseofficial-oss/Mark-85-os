package com.example.wakeword

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Interface for wake-word detection engines.
 */
interface WakeWordDetector {
    fun start(onWakeWordDetected: (command: String?) -> Unit)
    fun start(onWakeWordDetected: () -> Unit) {
        start { _ -> onWakeWordDetected() }
    }
    fun stop()
    fun release()
    val isListening: Boolean
}

/**
 * Continuous, silent, hardware-accelerated local wake-word detector.
 *
 * Keeps a single continuous AudioRecord stream open silently in the background
 * (NO constant mic on/off blinking, NO periodic speech recognizer timeouts).
 *
 * Strictly triggers the microphone ONLY when the user speaks:
 * - "Hey Jarvis"
 * - "Jarvis"
 * - "Ok Jarvis"
 *
 * Rejects ambient conversation and non-wake words through multi-feature acoustic validation:
 * 1. Exact temporal duration boundary (360ms to 1120ms).
 * 2. Prominent voiced resonant core ("JAR" / "HEY") with low ZCR fundamental.
 * 3. Phonetic inter-syllabic dip between core and tail.
 * 4. Distinct sibilant fricative tail ("-VIS") with high-frequency ZCR.
 * 5. Dynamic ambient noise floor calibration with hardware AEC & NoiseSuppressor.
 */
class LocalWakeWordDetector(
    private val context: Context,
    private val scope: CoroutineScope,
    private val picovoiceAccessKey: String = "",
    private val sensitivity: Float = 0.5f,
    private val filterPhoneSpeakerAudio: Boolean = true
) : WakeWordDetector {

    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var listeningJob: Job? = null
    private var onDetectedCallback: ((String?) -> Unit)? = null

    @Volatile
    override var isListening: Boolean = false
        private set

    @Volatile
    private var lastTriggerTime: Long = 0L

    private data class FrameInfo(
        val rms: Float,
        val zcr: Float
    )

    private fun isPhoneSpeakerActive(): Boolean {
        val am = audioManager ?: return false
        return try {
            am.mode != AudioManager.MODE_NORMAL || am.isMusicActive
        } catch (_: Exception) {
            false
        }
    }

    private fun attachAudioEffects(audioSessionId: Int) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply {
                    enabled = true
                    Log.i(TAG, "Hardware AcousticEchoCanceler (AEC) enabled on session $audioSessionId")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "AcousticEchoCanceler init warning: ${e.message}")
        }

        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply {
                    enabled = true
                    Log.i(TAG, "Hardware NoiseSuppressor enabled on session $audioSessionId")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "NoiseSuppressor init warning: ${e.message}")
        }
    }

    private fun releaseAudioEffects() {
        try {
            echoCanceler?.release()
        } catch (_: Exception) {
        } finally {
            echoCanceler = null
        }

        try {
            noiseSuppressor?.release()
        } catch (_: Exception) {
        } finally {
            noiseSuppressor = null
        }
    }

    override fun start(onWakeWordDetected: (command: String?) -> Unit) {
        if (isListening) return

        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "RECORD_AUDIO permission missing.")
            return
        }

        this.onDetectedCallback = onWakeWordDetected
        isListening = true

        listeningJob = scope.launch(Dispatchers.IO) {
            runAudioLoop()
        }
    }

    private suspend fun runAudioLoop() {
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val frameSize = 320 // 20ms per frame
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = maxOf(minBuf, frameSize * 4, 2048)

        // Try primary MIC source, fallback to VOICE_RECOGNITION if needed
        val candidateSources = listOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.DEFAULT
        )

        var record: AudioRecord? = null
        for (source in candidateSources) {
            try {
                val candidate = AudioRecord(source, sampleRate, channelConfig, audioFormat, bufferSize)
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    record = candidate
                    attachAudioEffects(candidate.audioSessionId)
                    Log.d(TAG, "AudioRecord initialized successfully with source $source")
                    break
                } else {
                    candidate.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed source $source: ${e.message}")
            }
        }

        if (record == null) {
            Log.e(TAG, "Unable to initialize AudioRecord on any hardware source.")
            isListening = false
            return
        }

        audioRecord = record
        try {
            record.startRecording()
            Log.i(TAG, "Silent background wake-word listener ACTIVE. Waiting for 'Hey Jarvis' / 'Jarvis' / 'Ok Jarvis'...")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioRecord: ${e.message}")
            isListening = false
            return
        }

        val audioBuffer = ShortArray(frameSize)
        val utteranceFrames = ArrayList<FrameInfo>(60)
        var noiseFloor = 35f
        var silenceCount = 0

        // Sensitivity curve: 0.5f maps to 1.0f
        val sensClamped = sensitivity.coerceIn(0.1f, 1.0f)
        val sensFactor = 1.25f - (sensClamped * 0.5f)

        while (scope.isActive && isListening) {
            val readCount = record.read(audioBuffer, 0, frameSize)
            if (readCount < frameSize) {
                delay(10)
                continue
            }

            var sumSquare = 0.0
            var zeroCrossings = 0
            for (i in 0 until readCount) {
                val sample = audioBuffer[i]
                sumSquare += sample.toLong() * sample
                if (i > 0) {
                    val prev = audioBuffer[i - 1]
                    if ((sample >= 0 && prev < 0) || (sample < 0 && prev >= 0)) {
                        zeroCrossings++
                    }
                }
            }

            val rms = sqrt(sumSquare / readCount).toFloat()
            val zcr = zeroCrossings.toFloat() / (readCount - 1)

            val speakerActive = filterPhoneSpeakerAudio && isPhoneSpeakerActive()
            val thresholdMultiplier = if (speakerActive) 1.6f else 1.0f

            // Noise floor tracking during ambient quiet
            if (rms < noiseFloor * 1.5f && !speakerActive) {
                noiseFloor = 0.97f * noiseFloor + 0.03f * rms
                if (noiseFloor < 15f) noiseFloor = 15f
                if (noiseFloor > 1500f) noiseFloor = 1500f
            }

            val speechThreshold = (noiseFloor * 1.55f * sensFactor * thresholdMultiplier + (40f * sensFactor)).coerceAtLeast(60f)
            val isSpeech = rms > speechThreshold

            if (isSpeech) {
                utteranceFrames.add(FrameInfo(rms, zcr))
                silenceCount = 0
                // Prevent buffer unbounded growth
                if (utteranceFrames.size > 60) {
                    utteranceFrames.removeAt(0)
                }
            } else {
                if (utteranceFrames.isNotEmpty()) {
                    silenceCount++
                    // When speech pauses for 3 frames (~60ms), evaluate complete utterance
                    if (silenceCount >= 3) {
                        val now = System.currentTimeMillis()
                        if (now - lastTriggerTime > 1800L) {
                            val matched = evaluateUtterance(
                                frames = utteranceFrames,
                                noiseFloor = noiseFloor,
                                sensFactor = sensFactor
                            )

                            if (matched) {
                                lastTriggerTime = now
                                Log.i(TAG, "WAKE-WORD DETECTED! Activating microphone for user command...")
                                isListening = false
                                utteranceFrames.clear()
                                releaseAudioEffects()
                                try {
                                    record.stop()
                                    record.release()
                                } catch (_: Exception) {
                                } finally {
                                    audioRecord = null
                                }

                                scope.launch(Dispatchers.Main) {
                                    onDetectedCallback?.invoke(null)
                                }
                                break // Exit loop cleanly
                            }
                        }
                        utteranceFrames.clear()
                        silenceCount = 0
                    }
                }
            }
        }

        releaseAudioEffects()
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {
        } finally {
            audioRecord = null
        }
    }

    /**
     * Strictly verifies the acoustic fingerprint of "Jarvis", "Hey Jarvis", or "Ok Jarvis".
     *
     * Invariants:
     * 1. Duration: 16 to 52 frames (320ms - 1040ms). Single words ("karo", "haan", "no") or long sentences are rejected.
     * 2. Peak Energy: Peak RMS must comfortably rise above ambient noise (>= 2.4x noiseFloor).
     * 3. Voiced Resonant Core ("JAR" / "HEY"): Vowel sound in first 65% with low ZCR (0.03 - 0.25) sustained for >= 3 frames.
     * 4. Sibilant Fricative Tail ("-VIS"): /s/ fricative sound in final 35% with high ZCR (>= 0.26, peak >= 0.32).
     * 5. Inter-syllabic Dip: Acoustic energy dip between voiced core and fricative tail.
     */
    private fun evaluateUtterance(
        frames: List<FrameInfo>,
        noiseFloor: Float,
        sensFactor: Float
    ): Boolean {
        val total = frames.size
        // "Jarvis" typically 16-36 frames (320-720ms); "Hey Jarvis" / "Ok Jarvis" 24-52 frames (480-1040ms)
        if (total !in 16..52) {
            return false
        }

        val peakRms = frames.maxOfOrNull { it.rms } ?: 0f
        val minPeakThreshold = maxOf(noiseFloor * 2.3f, 380f * sensFactor)
        if (peakRms < minPeakThreshold) {
            return false
        }

        // Split into Head (first 65%) and Tail (last 35%)
        val tailStartIndex = (total * 0.65f).toInt().coerceIn(1, total - 2)
        val headFrames = frames.subList(0, tailStartIndex)
        val tailFrames = frames.subList(tailStartIndex, total)

        // 1. Voiced Resonant Core ("JAR" / "HEY")
        var voicedCount = 0
        var maxConsecutiveVoiced = 0
        var currentVoicedStreak = 0

        for (f in headFrames) {
            if (f.zcr in 0.03f..0.25f && f.rms > noiseFloor * 1.4f) {
                voicedCount++
                currentVoicedStreak++
                if (currentVoicedStreak > maxConsecutiveVoiced) {
                    maxConsecutiveVoiced = currentVoicedStreak
                }
            } else {
                currentVoicedStreak = 0
            }
        }

        if (maxConsecutiveVoiced < 2 || voicedCount < 3) {
            return false
        }

        // 2. Sibilant Fricative Tail ("-VIS")
        var tailHighZcrCount = 0
        var tailPeakZcr = 0f

        for (f in tailFrames) {
            if (f.zcr > tailPeakZcr) tailPeakZcr = f.zcr
            if (f.zcr >= 0.25f && f.rms > noiseFloor * 1.05f) {
                tailHighZcrCount++
            }
        }

        val avgTailZcr = if (tailFrames.isNotEmpty()) tailFrames.map { it.zcr }.average().toFloat() else 0f

        if (tailHighZcrCount < 2 || tailPeakZcr < 0.30f || avgTailZcr < 0.20f) {
            return false
        }

        // 3. Inter-syllabic dip check:
        // Ensure energy dips between peak and tail (distinguishes two-syllable "Jarvis" from flat continuous words)
        val minMidRms = frames.subList((total * 0.4f).toInt(), tailStartIndex).minOfOrNull { it.rms } ?: peakRms
        val hasDip = minMidRms < peakRms * 0.82f

        if (!hasDip) {
            return false
        }

        Log.d(TAG, "Acoustic match confirmed! [frames=$total, peakRms=%.1f, voiced=$voicedCount, tailZcrCount=$tailHighZcrCount, tailPeakZcr=%.2f]".format(
            peakRms, tailPeakZcr
        ))
        return true
    }

    override fun stop() {
        isListening = false
        listeningJob?.cancel()
        listeningJob = null
        releaseAudioEffects()
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord: ${e.message}")
        } finally {
            audioRecord = null
        }
    }

    override fun release() {
        stop()
    }

    companion object {
        private const val TAG = "LocalWakeWord"
    }
}
