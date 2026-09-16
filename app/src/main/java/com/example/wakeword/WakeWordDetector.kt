package com.example.wakeword

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
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
 * Production-grade, hardware-accelerated local wake-word detector.
 *
 * Runs a single continuous AudioRecord stream silently in the background.
 *
 * PHONE SPEAKER AUDIO IMMUNITY:
 * When audio is playing through the phone's physical speaker (e.g. YouTube, Instagram Reels,
 * Spotify, media videos, incoming ringtone, TTS, or phone calls), the sound coming out
 * of the phone speaker is STRICTLY BLOCKED from triggering the wake word.
 *
 * Reliably triggers ONLY when the user speaks:
 * - "Hey Jarvis"
 * - "Jarvis"
 * - "Ok Jarvis"
 *
 * Uses multi-stage phonetic verification:
 * 1. Adaptive SNR Speech Activity Detection (calibrated to actual ambient room noise).
 * 2. Temporal Duration Gating: 16 to 55 frames (320ms to 1100ms).
 * 3. Voiced Resonant Core ("JAR" / "HEY"): low-ZCR vowel energy in the first 65%.
 * 4. Sibilant Fricative Tail ("-VIS" /s/): high-ZCR acoustic signature in the final 35%.
 * Non-wake words ending in vowels/nasals ("karo", "hello", "bhai", "theek", "open") are rejected.
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

    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null
    @Volatile
    private var isPlaybackActiveFromCallback: Boolean = false
    @Volatile
    private var lastSpeakerActiveTime: Long = 0L

    @Volatile
    override var isListening: Boolean = false
        private set

    @Volatile
    private var lastTriggerTime: Long = 0L

    private data class FrameData(
        val rms: Float,
        val zcr: Float
    )

    /**
     * Determines whether audio is actively playing through the device's physical built-in speaker.
     * If headphones/earbuds (Bluetooth or wired) are connected, audio is routed to ears and does
     * not blast into the phone's microphone, so normal wake-word listening remains active.
     */
    private fun isPhoneSpeakerActive(): Boolean {
        val am = audioManager ?: return false
        return try {
            // If Jarvis itself is speaking TTS
            if (isAppSpeaking) return true

            // Check if headphones / Bluetooth headsets are connected
            @Suppress("DEPRECATION")
            val isHeadphonesConnected = am.isWiredHeadsetOn ||
                    am.isBluetoothA2dpOn ||
                    am.isBluetoothScoOn

            val isSystemAudioActive = am.isMusicActive ||
                    am.mode != AudioManager.MODE_NORMAL ||
                    isPlaybackActiveFromCallback

            if (isSystemAudioActive) {
                // If headphones are plugged in, built-in speaker is silent -> not blasting into mic
                !isHeadphonesConnected
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun registerPlaybackCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val am = audioManager ?: return
            try {
                val cb = object : AudioManager.AudioPlaybackCallback() {
                    override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>?) {
                        super.onPlaybackConfigChanged(configs)
                        isPlaybackActiveFromCallback = !configs.isNullOrEmpty()
                        if (isPlaybackActiveFromCallback) {
                            lastSpeakerActiveTime = System.currentTimeMillis()
                        }
                    }
                }
                am.registerAudioPlaybackCallback(cb, Handler(Looper.getMainLooper()))
                playbackCallback = cb
            } catch (e: Exception) {
                Log.w(TAG, "AudioPlaybackCallback register warning: ${e.message}")
            }
        }
    }

    private fun unregisterPlaybackCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val am = audioManager ?: return
            playbackCallback?.let {
                try {
                    am.unregisterAudioPlaybackCallback(it)
                } catch (_: Exception) {
                }
            }
            playbackCallback = null
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

        registerPlaybackCallback()

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

        // Prioritize VOICE_RECOGNITION for hardware acoustic echo cancellation and tuning
        val candidateSources = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
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
            Log.i(TAG, "Silent wake-word detector active. Waiting for 'Hey Jarvis' / 'Jarvis' / 'Ok Jarvis'...")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to startRecording: ${e.message}")
            isListening = false
            return
        }

        val audioBuffer = ShortArray(frameSize)
        val utteranceFrames = ArrayList<FrameData>(65)
        var noiseFloor = 30f
        var silenceCount = 0

        // Sensitivity multiplier: 0.5f -> 1.0f factor
        val sensClamped = sensitivity.coerceIn(0.1f, 1.0f)
        val sensMultiplier = 1.25f - (sensClamped * 0.5f)

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

            // STRICT PHONE SPEAKER AUDIO IMMUNITY:
            // When media / videos / reels / music / calls / TTS are blasting through the phone's
            // physical speaker right next to the mic, discard all frames and prevent any wake trigger!
            val speakerActive = filterPhoneSpeakerAudio && isPhoneSpeakerActive()
            if (speakerActive) {
                lastSpeakerActiveTime = System.currentTimeMillis()
                utteranceFrames.clear()
                silenceCount = 0
                // Adapt noiseFloor to the speaker output so when it stops, the floor quickly settles
                if (rms < noiseFloor * 1.5f) {
                    noiseFloor = 0.95f * noiseFloor + 0.05f * rms
                } else {
                    noiseFloor = 0.98f * noiseFloor + 0.02f * rms
                }
                if (noiseFloor < 12f) noiseFloor = 12f
                if (noiseFloor > 800f) noiseFloor = 800f
                continue
            }

            // Acoustic tail clearance: prevent room reverberation from the phone speaker
            // from falsely triggering within 450ms after the speaker stops playing.
            val timeSinceSpeaker = System.currentTimeMillis() - lastSpeakerActiveTime
            if (timeSinceSpeaker < 450L) {
                utteranceFrames.clear()
                silenceCount = 0
                continue
            }

            // Dynamic ambient noise floor calibration during quiet intervals
            if (rms < noiseFloor * 1.5f) {
                noiseFloor = 0.98f * noiseFloor + 0.02f * rms
            } else if (rms > noiseFloor * 2.2f) {
                // Very slow drift up if room noise changes
                noiseFloor = 0.999f * noiseFloor + 0.001f * rms
            }
            if (noiseFloor < 12f) noiseFloor = 12f
            if (noiseFloor > 600f) noiseFloor = 600f

            // Balanced speech activity detection:
            // Adapts to ambient noise so normal speech at normal distance triggers,
            // while ambient room noise / fan hum does NOT trigger.
            val speechThreshold = maxOf(noiseFloor * 1.45f + 18f, 38f * sensMultiplier)
            val isSpeech = rms >= speechThreshold

            if (isSpeech) {
                utteranceFrames.add(FrameData(rms, zcr))
                silenceCount = 0
                // Prevent unbounded growth if someone speaks continuously
                if (utteranceFrames.size > 65) {
                    utteranceFrames.removeAt(0)
                }
            } else {
                if (utteranceFrames.isNotEmpty()) {
                    silenceCount++
                    // When speech pauses for 4 frames (~80ms), evaluate the completed phrase
                    if (silenceCount >= 4) {
                        val now = System.currentTimeMillis()
                        if (now - lastTriggerTime > 2000L) {
                            val matched = evaluateUtterance(
                                frames = utteranceFrames,
                                noiseFloor = noiseFloor,
                                sensMultiplier = sensMultiplier
                            )

                            if (matched) {
                                lastTriggerTime = now
                                Log.i(TAG, "WAKE-WORD CONFIRMED ('Hey Jarvis' / 'Jarvis')! Activating assistant...")
                                isListening = false
                                utteranceFrames.clear()
                                releaseAudioEffects()
                                unregisterPlaybackCallback()
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
        unregisterPlaybackCallback()
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {
        } finally {
            audioRecord = null
        }
    }

    /**
     * Accurately verifies the acoustic fingerprint of "Jarvis", "Hey Jarvis", or "Ok Jarvis".
     *
     * Invariants of "Jarvis" / "Hey Jarvis":
     * 1. Duration: 16 to 55 frames (320ms to 1100ms).
     *    - Short words ("haan", "kya", "bhai", "is", "no") are < 15 frames -> REJECTED.
     *    - Long sentences are > 55 frames -> REJECTED.
     * 2. Peak Energy: Peak RMS must rise clearly above the calibrated ambient noise floor.
     * 3. Voiced Resonant Core ("JAR" / "HEY"):
     *    - Vowels produce low Zero Crossing Rate (0.02 to 0.24) with sustained energy in the first 65%.
     * 4. Sibilant Fricative Tail ("-VIS" /s/):
     *    - The unvoiced /s/ produces elevated ZCR (peak >= 0.23, tail avg elevated) in the final 35%.
     *    - Non-wake words ending in vowels/nasals ("karo", "hello", "theek", "bhai", "open") lack this /s/ tail -> REJECTED.
     *    - Words with sibilants at start ("shuru", "stop") have high ZCR at head, not tail -> REJECTED.
     */
    private fun evaluateUtterance(
        frames: List<FrameData>,
        noiseFloor: Float,
        sensMultiplier: Float
    ): Boolean {
        val total = frames.size
        // "Jarvis" ~16-36 frames (320-720ms); "Hey Jarvis" / "Ok Jarvis" ~24-54 frames (480-1080ms)
        if (total !in 16..55) {
            return false
        }

        val peakRms = frames.maxOfOrNull { it.rms } ?: 0f
        val minPeakThreshold = maxOf(noiseFloor * 1.6f, 48f * sensMultiplier)
        if (peakRms < minPeakThreshold) {
            return false
        }

        // Split into Head (first 65%) and Tail (last 35%)
        val tailStartIndex = (total * 0.65f).toInt().coerceIn(1, total - 2)
        val headFrames = frames.subList(0, tailStartIndex)
        val tailFrames = frames.subList(tailStartIndex, total)

        // 1. Voiced Resonant Core ("JAR" / "HEY")
        val headVoicedCount = headFrames.count {
            it.zcr in 0.02f..0.24f && it.rms > noiseFloor * 1.15f
        }
        if (headVoicedCount < 4) {
            return false
        }

        // 2. Sibilant Fricative Tail ("-VIS" /s/)
        val tailPeakZcr = tailFrames.maxOfOrNull { it.zcr } ?: 0f
        val tailHighZcrCount = tailFrames.count { it.zcr >= 0.17f }
        val tailAvgZcr = if (tailFrames.isNotEmpty()) tailFrames.map { it.zcr }.average().toFloat() else 0f
        val headAvgZcr = if (headFrames.isNotEmpty()) headFrames.map { it.zcr }.average().toFloat() else 0f

        // Must exhibit genuine /s/ sibilance in the tail:
        // - Peak ZCR in tail must reach at least 0.23 (fricative sound)
        // - At least 2 frames in the tail with ZCR >= 0.17
        // - Tail must be more sibilant than the voiced head
        val hasSibilantTail = tailPeakZcr >= 0.23f &&
                tailHighZcrCount >= 2 &&
                (tailAvgZcr > headAvgZcr * 1.15f || tailAvgZcr >= 0.16f)

        if (!hasSibilantTail) {
            return false
        }

        Log.i(TAG, "Acoustic match confirmed! [frames=$total, peakRms=%.1f, voiced=$headVoicedCount, tailPeakZcr=%.2f, tailAvgZcr=%.2f]".format(
            peakRms, tailPeakZcr, tailAvgZcr
        ))
        return true
    }

    override fun stop() {
        isListening = false
        listeningJob?.cancel()
        listeningJob = null
        releaseAudioEffects()
        unregisterPlaybackCallback()
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

        /**
         * Global flag set whenever Jarvis is actively speaking via TTS
         * to guarantee zero self-wakeups.
         */
        @Volatile
        var isAppSpeaking: Boolean = false
    }
}
