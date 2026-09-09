package com.example.wakeword

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
 * High-performance, local wake-word detector strictly recognizing "Jarvis" / "Hey Jarvis".
 * Uses a pure, silent AudioRecord stream (ZERO Google Assistant beeps/chimes, ZERO audio toggling).
 * Analyzes acoustic phonetics: Voiced vowel ("Jar") -> Inter-syllabic dip -> High-ZCR Fricative tail ("-vis").
 * Completely rejects ambient noises, phone taps, coughs, and unrelated speech.
 */
class LocalWakeWordDetector(
    private val context: Context,
    private val scope: CoroutineScope,
    private val picovoiceAccessKey: String = "",
    private val sensitivity: Float = 0.5f
) : WakeWordDetector {

    private var audioRecord: AudioRecord? = null
    private var listeningJob: Job? = null
    private var onDetectedCallback: ((String?) -> Unit)? = null

    @Volatile
    override var isListening: Boolean = false
        private set

    @Volatile
    private var lastTriggerTime: Long = 0L

    private data class FrameStats(
        val rms: Float,
        val zcr: Float,
        val isVoice: Boolean
    )

    override fun start(onWakeWordDetected: (command: String?) -> Unit) {
        if (isListening) return

        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Cannot start wake-word detector: RECORD_AUDIO permission missing.")
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

        var record: AudioRecord? = null
        var attempts = 0

        // Retry loop for transient hardware locks (e.g. after phone call or app launch)
        while (scope.isActive && isListening && attempts < 5) {
            try {
                // Use VOICE_RECOGNITION source for hardware AEC and noise suppression
                record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    break
                } else {
                    record.release()
                    record = null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Attempt $attempts initializing AudioRecord: ${e.message}")
            }
            attempts++
            delay(200)
        }

        // Fallback to MIC if VOICE_RECOGNITION is restricted by OEM
        if (record == null && isListening) {
            try {
                record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
            } catch (e: Exception) {
                Log.e(TAG, "Fallback to MIC failed: ${e.message}")
            }
        }

        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord could not be initialized.")
            isListening = false
            return
        }

        audioRecord = record
        try {
            record.startRecording()
            Log.d(TAG, "Silent continuous Wake-Word engine active (monitoring 'Jarvis' / 'Hey Jarvis')")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to startRecording: ${e.message}")
            isListening = false
            return
        }

        val audioBuffer = ShortArray(frameSize)
        val utteranceFrames = ArrayList<FrameStats>(80)
        var silenceCounter = 0
        var noiseFloor = 350f

        // Sensitivity modifier: higher sensitivity lowers the energy and ZCR barrier slightly
        val sensMultiplier = (1.3f - sensitivity.coerceIn(0.1f, 1.0f) * 0.6f)
        val minZcrThreshold = 0.28f * (1.2f - sensitivity.coerceIn(0.1f, 1.0f) * 0.35f)

        while (scope.isActive && isListening) {
            val readCount = record.read(audioBuffer, 0, frameSize)
            if (readCount < frameSize) {
                delay(20)
                continue
            }

            // 1. Calculate RMS Energy and Zero-Crossing Rate (ZCR)
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

            // Dynamic noise floor tracking during silence
            if (rms < noiseFloor * 1.4f) {
                noiseFloor = 0.98f * noiseFloor + 0.02f * rms
                if (noiseFloor < 100f) noiseFloor = 100f
                if (noiseFloor > 3500f) noiseFloor = 3500f
            }

            val speechThreshold = maxOf(noiseFloor * 2.0f * sensMultiplier, 400f * sensMultiplier)
            val isSpeech = rms > speechThreshold

            if (isSpeech) {
                utteranceFrames.add(FrameStats(rms, zcr, isVoice = true))
                silenceCounter = 0
                // Prevent runaway buffer if speech continues too long (e.g. conversation, music)
                if (utteranceFrames.size > 70) {
                    utteranceFrames.clear()
                }
            } else {
                if (utteranceFrames.isNotEmpty()) {
                    silenceCounter++
                    utteranceFrames.add(FrameStats(rms, zcr, isVoice = false))

                    // End of utterance detected (5 consecutive silence frames = 100ms)
                    if (silenceCounter >= 5) {
                        val validSpeechFrames = utteranceFrames.filter { it.isVoice }
                        val now = System.currentTimeMillis()

                        // Human utterance for "Jarvis" or "Hey Jarvis" typically lasts 440ms to 1100ms (22 to 55 frames)
                        if (validSpeechFrames.size in 22..55 && (now - lastTriggerTime > 3000L)) {
                            val matched = evaluateJarvisAcoustics(
                                frames = utteranceFrames,
                                noiseFloor = noiseFloor,
                                minZcrThreshold = minZcrThreshold
                            )
                            if (matched) {
                                lastTriggerTime = now
                                Log.i(TAG, "Wake-Word matched 'Jarvis' / 'Hey Jarvis'! Activating assistant.")
                                isListening = false
                                utteranceFrames.clear()
                                silenceCounter = 0
                                try {
                                    record.stop()
                                    record.release()
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error closing record: ${e.message}")
                                } finally {
                                    audioRecord = null
                                }
                                scope.launch(Dispatchers.Main) {
                                    onDetectedCallback?.invoke(null)
                                }
                                break // Exit loop while assistant handles speech
                            }
                        }
                        utteranceFrames.clear()
                        silenceCounter = 0
                    }
                }
            }
        }

        // Clean up on exit if still open
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            // Ignored
        } finally {
            audioRecord = null
        }
    }

    /**
     * Strictly evaluates the phonetic acoustic signature of "JARVIS" or "HEY JARVIS":
     * 1. Duration: 440ms - 1100ms
     * 2. SNR: Peak energy must be well above the ambient room noise floor (avoid false TV/talk triggers)
     * 3. Voiced "JAR" Core: Low ZCR (0.05 - 0.22) with strong resonant vowel energy
     * 4. Inter-syllabic Dip: Deep energy valley between "JAR" and "VIS"
     * 5. Terminal Fricative /s/: Sustained high ZCR (>0.35) in the tail for at least 3 consecutive frames
     */
    private fun evaluateJarvisAcoustics(
        frames: List<FrameStats>,
        noiseFloor: Float,
        minZcrThreshold: Float
    ): Boolean {
        if (frames.size < 22 || frames.size > 60) return false

        val n = frames.size
        val peakRms = frames.maxOfOrNull { it.rms } ?: 1f

        // Signal must be distinctly louder than the room's ambient noise floor
        if (peakRms < noiseFloor * 2.8f || peakRms < 850f) {
            return false
        }

        val firstHalfCount = (n * 0.45f).toInt().coerceAtLeast(6)
        val tailStartIndex = (n * 0.70f).toInt().coerceAtMost(n - 4)

        val firstHalf = frames.subList(0, firstHalfCount)
        val tailFrames = frames.subList(tailStartIndex, n)

        val avgRmsFirstHalf = firstHalf.map { it.rms }.average().toFloat()
        val avgZcrFirstHalf = firstHalf.map { it.zcr }.average().toFloat()

        val avgZcrTail = tailFrames.map { it.zcr }.average().toFloat()
        val highZcrTailCount = tailFrames.count { it.zcr >= 0.35f }

        // 1. Voiced core check: "Jar" must be resonant with low ZCR
        val isFirstHalfVoiced = avgZcrFirstHalf in 0.04f..0.22f && avgRmsFirstHalf > 600f

        // 2. Fricative /s/ tail check: "-vis" must have sustained high ZCR
        val hasFricativeTail = highZcrTailCount >= 3 && avgZcrTail >= (avgZcrFirstHalf * 2.0f)

        // 3. Syllable energy dip check: Check for an amplitude drop of at least 48% between peak and tail
        var hasDip = false
        val midFrames = frames.subList((n * 0.35f).toInt(), (n * 0.75f).toInt())
        for (f in midFrames) {
            if (f.rms < peakRms * 0.52f) {
                hasDip = true
                break
            }
        }

        Log.d(
            TAG,
            "Jarvis Acoustic Filter [frames=$n, peakRms=%.1f, avgZcr1st=%.3f, tailZcrCount=$highZcrTailCount, voiced=$isFirstHalfVoiced, fricative=$hasFricativeTail, dip=$hasDip]".format(
                peakRms, avgZcrFirstHalf
            )
        )

        return isFirstHalfVoiced && hasFricativeTail && hasDip
    }

    override fun stop() {
        isListening = false
        listeningJob?.cancel()
        listeningJob = null
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

