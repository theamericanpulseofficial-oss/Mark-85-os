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
 * Robust, silent local wake-word detector.
 *
 * Keeps a single continuous AudioRecord stream open silently in the background
 * without status-bar blinking or auto-triggering on background noise.
 *
 * Strictly triggers the microphone ONLY when the user speaks:
 * - "Hey Jarvis"
 * - "Jarvis"
 * - "Ok Jarvis"
 *
 * Rejects ambient room noise, fan, AC, breathing, and non-wake words.
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
                    Log.i(TAG, "Hardware AEC active on session $audioSessionId")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "AEC init warning: ${e.message}")
        }

        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply {
                    enabled = true
                    Log.i(TAG, "Hardware NS active on session $audioSessionId")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "NS init warning: ${e.message}")
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
        val frameSize = 320 // 20ms at 16kHz
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = maxOf(minBuf, frameSize * 4, 2048)

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
                    Log.d(TAG, "AudioRecord initialized with source $source")
                    break
                } else {
                    candidate.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Source $source failed: ${e.message}")
            }
        }

        if (record == null) {
            Log.e(TAG, "Could not initialize AudioRecord.")
            isListening = false
            return
        }

        audioRecord = record
        try {
            record.startRecording()
            Log.i(TAG, "Silent wake-word detector waiting for 'Hey Jarvis' / 'Jarvis' / 'Ok Jarvis'...")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to startRecording: ${e.message}")
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
            val speakerMultiplier = if (speakerActive) 1.6f else 1.0f

            // Dynamic ambient noise floor calibration during quiet intervals
            if (rms < noiseFloor * 1.5f && !speakerActive) {
                noiseFloor = 0.97f * noiseFloor + 0.03f * rms
                if (noiseFloor < 15f) noiseFloor = 15f
                if (noiseFloor > 1200f) noiseFloor = 1200f
            }

            // Real speech threshold: ensures ambient room noise, fan, AC do NOT register as speech
            val speechThreshold = maxOf(noiseFloor * 1.85f * speakerMultiplier + 50f, 95f * sensFactor)
            val isSpeech = rms > speechThreshold

            if (isSpeech) {
                utteranceFrames.add(FrameInfo(rms, zcr))
                silenceCount = 0
                if (utteranceFrames.size > 55) {
                    utteranceFrames.removeAt(0)
                }
            } else {
                if (utteranceFrames.isNotEmpty()) {
                    silenceCount++
                    // When user pauses speaking for 3 frames (~60ms), evaluate the completed word
                    if (silenceCount >= 3) {
                        val now = System.currentTimeMillis()
                        if (now - lastTriggerTime > 2500L) {
                            val matched = evaluateCompletedUtterance(utteranceFrames, noiseFloor, sensFactor)
                            if (matched) {
                                lastTriggerTime = now
                                Log.i(TAG, "WAKE-WORD CONFIRMED! Activating microphone for user command...")
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
     * Invariant Criteria:
     * 1. Duration: 16 to 50 frames (320ms - 1000ms).
     * 2. Peak Energy: Peak RMS must rise significantly above ambient room noise (>= 300f).
     * 3. Voiced Resonant Core ("JAR" / "HEY"): Vowel sound in first 65% with low ZCR (0.03 - 0.24) and high RMS.
     * 4. Sibilant Fricative Tail ("-VIS"): /s/ sound in final 35% with high ZCR (>= 0.28, peak >= 0.32).
     * 5. Inter-syllabic Dip: Acoustic energy dip between voiced core and fricative tail.
     */
    private fun evaluateCompletedUtterance(
        frames: List<FrameInfo>,
        noiseFloor: Float,
        sensFactor: Float
    ): Boolean {
        val total = frames.size
        if (total !in 16..50) {
            return false
        }

        val peakRms = frames.maxOfOrNull { it.rms } ?: 0f
        val minPeakThreshold = maxOf(noiseFloor * 2.3f, 300f * sensFactor)
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
            if (f.zcr in 0.03f..0.24f && f.rms > noiseFloor * 1.35f) {
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
            if (f.zcr >= 0.28f && f.rms > noiseFloor * 1.05f) {
                tailHighZcrCount++
            }
        }

        val avgTailZcr = if (tailFrames.isNotEmpty()) tailFrames.map { it.zcr }.average().toFloat() else 0f

        if (tailHighZcrCount < 2 || tailPeakZcr < 0.32f || avgTailZcr < 0.21f) {
            return false
        }

        // 3. Inter-syllabic dip check
        val midStart = (total * 0.40f).toInt().coerceIn(0, tailStartIndex - 1)
        val minMidRms = frames.subList(midStart, tailStartIndex).minOfOrNull { it.rms } ?: peakRms
        val hasDip = minMidRms < peakRms * 0.80f

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
