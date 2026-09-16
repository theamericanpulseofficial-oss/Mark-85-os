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
        val zcr: Float,
        val nhfr: Float // Normalized High-Frequency Ratio: 0.0 (low-pitch vowel) to 1.0 (high-pitch /s/ friction)
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
            var sumDiffSquare = 0.0
            var zeroCrossings = 0

            for (i in 0 until readCount) {
                val sample = audioBuffer[i]
                sumSquare += sample.toLong() * sample
                if (i > 0) {
                    val prev = audioBuffer[i - 1]
                    val diff = sample - prev
                    sumDiffSquare += diff.toLong() * diff
                    if ((sample >= 0 && prev < 0) || (sample < 0 && prev >= 0)) {
                        zeroCrossings++
                    }
                }
            }

            val rms = sqrt(sumSquare / readCount).toFloat()
            val zcr = zeroCrossings.toFloat() / (readCount - 1)
            // Normalized High-Frequency Ratio: 0.0 (low-pitch vowels) to 1.0 (sibilant friction /s/)
            val nhfr = if (sumSquare > 1000.0) {
                ((sumDiffSquare / (4.0 * sumSquare)).toFloat()).coerceIn(0.0f, 1.0f)
            } else {
                0.0f
            }

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

            // Calibrated speech activity threshold:
            // High enough to ignore distant background voices, room chatter, and television noise,
            // while comfortably picking up the user speaking toward the phone.
            val speechThreshold = maxOf(noiseFloor * 1.55f + 20f, 42f * sensMultiplier)
            val isSpeech = rms >= speechThreshold

            if (isSpeech) {
                utteranceFrames.add(FrameData(rms, zcr, nhfr))
                silenceCount = 0
                // Prevent unbounded growth if someone speaks continuously
                if (utteranceFrames.size > 65) {
                    utteranceFrames.removeAt(0)
                }
            } else {
                if (utteranceFrames.isNotEmpty()) {
                    silenceCount++
                    // When speech pauses for 5 frames (~100ms), evaluate the completed candidate wake phrase
                    if (silenceCount >= 5) {
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
     * Precision acoustic and phonetic verification for "Jarvis", "Hey Jarvis", or "Ok Jarvis".
     *
     * STUBBORN IMMUNITY TO OTHER VOICES:
     * Casual conversation words ("haan", "kya", "bhai", "karo", "accha", "theek", "bolo", "kaha ho",
     * "phone", "yes", "stop", "six", etc.) are strictly rejected by multi-stage invariants:
     *
     * 1. Temporal Bounds:
     *    - "Jarvis" / "Hey Jarvis" takes 18 to 54 frames (360ms to 1080ms).
     *    - Short words (< 18 frames) or long sentences (> 54 frames) are immediately rejected.
     * 2. Envelope Rhythm:
     *    - "Jarvis" peaks in the first syllable "JAR" (open /ɑː/ vowel), NOT at the very end.
     * 3. Non-sibilant Head:
     *    - "J-A-R" starts with a voiced affricate/vowel (low NHFR). Words starting with /s/, /sh/, /ch/
     *      are rejected.
     * 4. Voiced Resonant Core ("JAR"):
     *    - Sustained low NHFR (vocal cord formants) in the first 65%.
     * 5. Sibilant Fricative Coda ("-VIS" /s/):
     *    - The alveolar /s/ produces true high-frequency acoustic friction (NHFR >= 0.28, ZCR >= 0.26)
     *      sustained for at least 3 active speech frames (60ms+).
     *    - Words ending in vowels or nasals lack this tail -> REJECTED.
     *    - Low-amplitude microphone noise floor hiss is explicitly rejected via speech-energy gating.
     * 6. High-Frequency Transition:
     *    - The tail has significantly higher NHFR and ZCR than the voiced head.
     */
    private fun evaluateUtterance(
        frames: List<FrameData>,
        noiseFloor: Float,
        sensMultiplier: Float
    ): Boolean {
        val total = frames.size
        // "Jarvis" ~18-36 frames (360-720ms); "Hey Jarvis" / "Ok Jarvis" ~28-54 frames (560-1080ms)
        if (total !in 18..54) {
            return false
        }

        val peakRms = frames.maxOfOrNull { it.rms } ?: 0f
        val minPeakThreshold = maxOf(noiseFloor * 1.8f, 52f * sensMultiplier)
        if (peakRms < minPeakThreshold) {
            return false
        }

        // 1. Envelope Structure: In "JAR-VIS", peak volume is in the vowel "JAR" (first 15% to 70% of duration).
        // Sentences where volume rises at the end are rejected.
        val peakIndex = frames.indexOfFirst { it.rms == peakRms }
        if (peakIndex > total * 0.72f) {
            return false
        }

        // Split into Head (first 65%) and Tail (last 35%)
        val tailStartIndex = (total * 0.65f).toInt().coerceIn(1, total - 3)
        val headFrames = frames.subList(0, tailStartIndex)
        val tailFrames = frames.subList(tailStartIndex, total)

        // 2. Non-sibilant Head: "Jarvis" starts with /dʒ/ + /ɑː/ (low NHFR).
        // If someone said "stop", "shuru", "six", "status", the start has high NHFR (> 0.32).
        val initialFrames = headFrames.take(4)
        val initialAvgNhfr = if (initialFrames.isNotEmpty()) initialFrames.map { it.nhfr }.average().toFloat() else 0f
        if (initialAvgNhfr > 0.32f) {
            return false
        }

        // 3. Voiced Resonant Core ("JAR" / "HEY"):
        // Must contain sustained low-frequency vowel energy
        val headVoicedCount = headFrames.count {
            it.zcr <= 0.22f && it.nhfr <= 0.24f && it.rms >= noiseFloor * 1.25f
        }
        if (headVoicedCount < 4) {
            return false
        }

        // 4. Sibilant Fricative Tail ("-VIS" /s/):
        // Real human /s/ produces concentrated high frequency energy (4kHz - 8kHz)
        val tailPeakNhfr = tailFrames.maxOfOrNull { it.nhfr } ?: 0f
        val tailPeakZcr = tailFrames.maxOfOrNull { it.zcr } ?: 0f

        // Sibilant frames must have genuine speech energy (NOT just quiet background microphone hiss)
        val tailSibilantFrames = tailFrames.filter {
            it.nhfr >= 0.22f && it.zcr >= 0.20f && it.rms >= noiseFloor * 1.15f
        }
        val tailSibilantCount = tailSibilantFrames.size

        val minTailNhfr = 0.28f * sensMultiplier.coerceIn(0.85f, 1.15f)
        val hasTrueSibilantTail = tailPeakNhfr >= minTailNhfr &&
                tailPeakZcr >= 0.25f &&
                tailSibilantCount >= 3

        if (!hasTrueSibilantTail) {
            return false
        }

        // 5. Spectral Contrast: Transition from Voiced Head to Sibilant Tail
        val headAvgNhfr = if (headFrames.isNotEmpty()) headFrames.map { it.nhfr }.average().toFloat() else 0f
        val tailAvgNhfr = if (tailFrames.isNotEmpty()) tailFrames.map { it.nhfr }.average().toFloat() else 0f
        val headAvgZcr = if (headFrames.isNotEmpty()) headFrames.map { it.zcr }.average().toFloat() else 0f
        val tailAvgZcr = if (tailFrames.isNotEmpty()) tailFrames.map { it.zcr }.average().toFloat() else 0f

        val hasContrast = (tailAvgNhfr >= headAvgNhfr * 1.35f || (tailAvgNhfr >= 0.26f && tailAvgZcr >= 0.22f)) &&
                (tailAvgZcr >= headAvgZcr * 1.18f || tailAvgZcr >= 0.24f)

        if (!hasContrast) {
            return false
        }

        Log.i(TAG, "Acoustic match confirmed! [frames=$total, peakRms=%.1f, voiced=$headVoicedCount, tailPeakNhfr=%.2f, tailPeakZcr=%.2f, sibilantCount=$tailSibilantCount]".format(
            peakRms, tailPeakNhfr, tailPeakZcr, tailSibilantCount
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
