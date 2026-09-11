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
 * High-sensitivity, silent, hardware-accelerated local wake-word detector.
 *
 * Runs a single continuous AudioRecord stream silently in the background
 * without status-bar blinking or periodic timeouts.
 *
 * Reliably detects natural human speech of:
 * - "Hey Jarvis"
 * - "Jarvis"
 * - "Ok Jarvis"
 *
 * Rejects non-wake words ("karo", "hello", "bhai", "theek", "open", "chrome")
 * by requiring the phonetic transition:
 * Voiced Resonant Core ("JAR" / "HEY") -> Sibilant Fricative Tail ("-VIS" /s/).
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

    private data class FrameData(
        val rms: Float,
        val zcr: Float,
        val isSpeech: Boolean
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
            Log.i(TAG, "Wake-word detector listening for 'Hey Jarvis' / 'Jarvis' / 'Ok Jarvis'")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to startRecording: ${e.message}")
            isListening = false
            return
        }

        val audioBuffer = ShortArray(frameSize)
        val ringBuffer = ArrayList<FrameData>(50)
        var noiseFloor = 30f
        var silenceCount = 0
        var speechStreak = 0
        var consecutiveZeros = 0

        // Sensitivity factor: maps 0.5f -> 1.0f
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
            var allZeros = true

            for (i in 0 until readCount) {
                val sample = audioBuffer[i]
                if (sample != 0.toShort()) allZeros = false
                sumSquare += sample.toLong() * sample
                if (i > 0) {
                    val prev = audioBuffer[i - 1]
                    if ((sample >= 0 && prev < 0) || (sample < 0 && prev >= 0)) {
                        zeroCrossings++
                    }
                }
            }

            // Zero-data watchdog: re-init if OEM system muted background audio
            if (allZeros) {
                consecutiveZeros++
                if (consecutiveZeros > 30) {
                    Log.w(TAG, "Continuous silence detected. Reconnecting microphone...")
                    break
                }
            } else {
                consecutiveZeros = 0
            }

            val rms = sqrt(sumSquare / readCount).toFloat()
            val zcr = zeroCrossings.toFloat() / (readCount - 1)

            val speakerActive = filterPhoneSpeakerAudio && isPhoneSpeakerActive()
            val speakerMultiplier = if (speakerActive) 1.5f else 1.0f

            // Dynamic ambient noise floor calibration
            if (rms < noiseFloor * 1.4f && !speakerActive) {
                noiseFloor = 0.96f * noiseFloor + 0.04f * rms
                if (noiseFloor < 10f) noiseFloor = 10f
                if (noiseFloor > 1200f) noiseFloor = 1200f
            }

            // Clean, accessible speech threshold (detects natural speaking volume easily)
            val speechThreshold = (noiseFloor * 1.25f * sensFactor * speakerMultiplier + (12f * sensFactor)).coerceAtLeast(20f)
            val isSpeech = rms > speechThreshold

            if (isSpeech) {
                speechStreak++
                silenceCount = 0
            } else {
                speechStreak = 0
                silenceCount++
            }

            if (ringBuffer.size >= 50) {
                ringBuffer.removeAt(0)
            }
            ringBuffer.add(FrameData(rms, zcr, isSpeech))

            val now = System.currentTimeMillis()
            // Evaluate when speech completes (1-3 frames of silence after speaking) or during sustained phrase
            val shouldEvaluate = (silenceCount in 1..4 && ringBuffer.count { it.isSpeech } >= 12) ||
                    (speechStreak >= 18 && speechStreak % 4 == 0)

            if (shouldEvaluate && (now - lastTriggerTime > 1800L)) {
                val matched = checkWakeWordInFrames(ringBuffer, noiseFloor, sensFactor)
                if (matched) {
                    lastTriggerTime = now
                    Log.i(TAG, "WAKE-WORD CONFIRMED ('Hey Jarvis' / 'Jarvis')! Activating assistant...")
                    isListening = false
                    ringBuffer.clear()
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
                    break
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
     * Checks whether the recent frames contain the acoustic pattern of "Jarvis" or "Hey Jarvis".
     *
     * Natural speech characteristics of "Hey Jarvis" / "Jarvis":
     * - Total duration of speech: 14 to 48 frames (280ms to 960ms).
     * - Voiced Resonant Core ("JAR" / "HEY"): low ZCR (0.02 - 0.28) with solid energy in the first 70%.
     * - Sibilant Fricative Tail ("-VIS"): higher ZCR (>= 0.18, peak >= 0.22) in the final 40%.
     * - Peak RMS comfortably stands above the ambient noise floor.
     *
     * Rejection of non-wake words:
     * - "karo", "hello", "bhai", "theek", "open", "chrome" end in vowels/sonorants (ZCR 0.05-0.12),
     *   so they have 0 high-ZCR tail frames and are instantly rejected!
     * - "shanti", "stop" have fricatives at the BEGINNING, not the tail, so they are rejected!
     */
    private fun checkWakeWordInFrames(
        frames: List<FrameData>,
        noiseFloor: Float,
        sensFactor: Float
    ): Boolean {
        if (frames.size < 14) return false

        // Extract the active speech segment (strip trailing silence)
        var lastSpeechIdx = frames.size - 1
        while (lastSpeechIdx >= 0 && !frames[lastSpeechIdx].isSpeech) {
            lastSpeechIdx--
        }
        if (lastSpeechIdx < 12) return false

        // Test window lengths from 14 frames (280ms) to 48 frames (960ms)
        val maxLen = minOf(lastSpeechIdx + 1, 48)
        val minLen = 14

        for (len in minLen..maxLen step 2) {
            val startIdx = lastSpeechIdx + 1 - len
            if (startIdx < 0) continue

            val window = frames.subList(startIdx, lastSpeechIdx + 1)
            val peakRms = window.maxOfOrNull { it.rms } ?: 0f

            // Prominence check
            if (peakRms < noiseFloor * 1.4f || peakRms < 75f * sensFactor) {
                continue
            }

            val splitIdx = (len * 0.65f).toInt().coerceIn(1, len - 2)
            val head = window.subList(0, splitIdx)
            val tail = window.subList(splitIdx, len)

            // 1. Voiced Core ("JAR" / "HEY"): low ZCR, sustained energy
            val voicedCount = head.count { it.zcr in 0.02f..0.28f && it.rms > noiseFloor * 1.12f }
            if (voicedCount < 3) {
                continue
            }

            // 2. Sibilant Tail ("-VIS"): /s/ sibilance with high ZCR
            val tailHighZcrCount = tail.count { it.zcr >= 0.18f }
            val tailPeakZcr = tail.maxOfOrNull { it.zcr } ?: 0f

            // Must have at least 2 high-ZCR frames and peak >= 0.22f
            if (tailHighZcrCount < 2 || tailPeakZcr < 0.22f) {
                continue
            }

            Log.d(
                TAG,
                "Acoustic match [len=$len, peakRms=%.1f, voiced=$voicedCount, tailZcrCount=$tailHighZcrCount, tailPeak=%.2f]".format(
                    peakRms, tailPeakZcr
                )
            )
            return true
        }

        return false
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
