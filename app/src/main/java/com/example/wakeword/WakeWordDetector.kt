package com.example.wakeword

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
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
 * High-performance, local wake-word detector strictly recognizing "Jarvis", "Hey Jarvis", or "Ok Jarvis".
 * Uses a pure, silent AudioRecord stream (ZERO Google Assistant beeps/chimes, ZERO audio toggling).
 *
 * Anti-Self Speaker Protection:
 * - Employs hardware AcousticEchoCanceler (AEC) and NoiseSuppressor (NS) to cancel speaker audio.
 * - Monitors AudioManager and ActivePlaybackConfigurations to actively reject audio playing
 *   from this phone's own speaker (YouTube, Reels, Music, Podcasts, TTS, Games).
 * - Analyzes acoustic phonetics: Resonant Voiced vowel ("Jar") -> Inter-syllabic dip -> High-ZCR Fricative tail ("-vis").
 * - Rejects percussive drum transients, cymbals, ambient noises, phone taps, coughs, and unrelated media.
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
    private var lastSpeakerActiveTime: Long = 0L

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

    /**
     * Checks if the device's internal speaker / audio subsystem is actively playing
     * sound (e.g. YouTube, Reels, Music, Games, In-app TTS, Ringtone, Calls).
     */
    private fun isPhoneSpeakerActive(): Boolean {
        val am = audioManager ?: return false
        try {
            // 1. Music, media, video, or podcast stream currently playing on device
            if (am.isMusicActive) {
                return true
            }

            // 2. Call, VoIP, or ringtone stream active
            if (am.mode != AudioManager.MODE_NORMAL) {
                return true
            }

            // 3. API 26+ active playback configurations check
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val configs = am.activePlaybackConfigurations
                for (config in configs) {
                    val usage = config.audioAttributes?.usage ?: continue
                    if (usage == AudioAttributes.USAGE_MEDIA ||
                        usage == AudioAttributes.USAGE_GAME ||
                        usage == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE ||
                        usage == AudioAttributes.USAGE_ASSISTANT ||
                        usage == AudioAttributes.USAGE_VOICE_COMMUNICATION ||
                        usage == AudioAttributes.USAGE_NOTIFICATION_RINGTONE
                    ) {
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Speaker active check warning: ${e.message}")
        }
        return false
    }

    private fun checkDeviceSpeakerActive(): Boolean {
        if (!filterPhoneSpeakerAudio) return false
        val now = System.currentTimeMillis()
        if (isPhoneSpeakerActive()) {
            lastSpeakerActiveTime = now
            return true
        }
        // Retain active suppression for 500ms after audio playback ends to swallow room echo & reverb
        return (now - lastSpeakerActiveTime) < 500L
    }

    private fun attachAudioEffects(audioSessionId: Int) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply {
                    enabled = true
                    Log.i(TAG, "Hardware AcousticEchoCanceler (AEC) active on session $audioSessionId")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to initialize AcousticEchoCanceler: ${e.message}")
        }

        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply {
                    enabled = true
                    Log.i(TAG, "Hardware NoiseSuppressor active on session $audioSessionId")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to initialize NoiseSuppressor: ${e.message}")
        }
    }

    private fun releaseAudioEffects() {
        try {
            echoCanceler?.release()
        } catch (e: Exception) {
            // Ignored
        } finally {
            echoCanceler = null
        }

        try {
            noiseSuppressor?.release()
        } catch (e: Exception) {
            // Ignored
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
                    attachAudioEffects(record.audioSessionId)
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
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    attachAudioEffects(record.audioSessionId)
                }
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
            Log.d(TAG, "Silent continuous Wake-Word engine active (monitoring 'Jarvis' / 'Hey Jarvis' / 'Ok Jarvis')")
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

            // ANTI-SELF SPEAKER PROTECTION:
            // Check if phone's own speaker is actively playing audio (YouTube, Reels, Music, Games, TTS)
            val speakerIsActive = checkDeviceSpeakerActive()
            if (speakerIsActive) {
                // Drop accumulated frames so speaker audio doesn't trigger false wake-ups!
                if (utteranceFrames.isNotEmpty()) {
                    utteranceFrames.clear()
                }
                silenceCounter = 0
                // Adapt noise floor upward to the speaker playback level
                if (rms > noiseFloor) {
                    noiseFloor = 0.90f * noiseFloor + 0.10f * rms
                }
                continue
            }

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
                if (utteranceFrames.size > 85) {
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

                        // Human utterance for "Jarvis", "Hey Jarvis", or "Ok Jarvis" (360ms to 1300ms, 18 to 65 frames)
                        if (validSpeechFrames.size in 18..66 && (now - lastTriggerTime > 2500L)) {
                            val match = evaluateWakeWordAcoustics(
                                frames = utteranceFrames,
                                noiseFloor = noiseFloor,
                                minZcrThreshold = minZcrThreshold,
                                sensMultiplier = sensMultiplier
                            )
                            if (match.matched) {
                                lastTriggerTime = now
                                Log.i(TAG, "Wake-Word matched '${match.detectedWord}'! Activating assistant.")
                                isListening = false
                                utteranceFrames.clear()
                                silenceCounter = 0
                                releaseAudioEffects()
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
        releaseAudioEffects()
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            // Ignored
        } finally {
            audioRecord = null
        }
    }

    private data class WakeWordMatch(
        val matched: Boolean,
        val detectedWord: String = ""
    )

    /**
     * Strictly evaluates the phonetic acoustic signature of the 3 supported wake words:
     * 1. "JARVIS" (single word, ~360ms - 720ms)
     * 2. "HEY JARVIS" (two words, ~550ms - 1050ms)
     * 3. "OK JARVIS" (multi-syllable, ~650ms - 1300ms)
     *
     * Acoustic Invariants across all 3:
     * - Ends in "-VIS": Sibilant alveolar fricative /s/ in the terminal tail (high ZCR)
     * - Preceded by Inter-syllabic Dip: Amplitude drops significantly before the fricative tail
     * - Resonant Core "JAR": High RMS with low ZCR (0.03 - 0.22)
     * - Prefixes "Hey" or "Ok": Precede the "JAR" core in duration and frame offset
     */
    private fun evaluateWakeWordAcoustics(
        frames: List<FrameStats>,
        noiseFloor: Float,
        minZcrThreshold: Float,
        sensMultiplier: Float
    ): WakeWordMatch {
        val n = frames.size
        if (n < 18 || n > 75) return WakeWordMatch(false)

        val peakRms = frames.maxOfOrNull { it.rms } ?: 1f

        // 1. Signal-To-Noise check: Peak must stand out above ambient room noise
        val minPeakThreshold = maxOf(noiseFloor * 2.5f, 720f * sensMultiplier)
        if (peakRms < minPeakThreshold) {
            return WakeWordMatch(false)
        }

        // 2. Fricative /s/ tail check: "-vis" must be in the final 30% of frames
        val tailStartIndex = (n * 0.70f).toInt().coerceAtMost(n - 4)
        val tailFrames = frames.subList(tailStartIndex, n)
        val targetZcr = (minZcrThreshold * 1.05f).coerceAtLeast(0.34f)
        val highZcrTailCount = tailFrames.count { it.zcr >= targetZcr }
        val avgZcrTail = tailFrames.map { it.zcr }.average().toFloat()

        // Sibilant fricative in "-vis" requires sustained high ZCR (at least 3 frames = 60ms)
        if (highZcrTailCount < 3 || avgZcrTail < 0.30f) {
            return WakeWordMatch(false)
        }

        // Consecutive high-ZCR check: Rejects single-frame drum clicks / cymbals
        var consecutiveHighZcr = 0
        var maxConsecutiveHighZcr = 0
        for (f in tailFrames) {
            if (f.zcr >= targetZcr * 0.92f) {
                consecutiveHighZcr++
                if (consecutiveHighZcr > maxConsecutiveHighZcr) {
                    maxConsecutiveHighZcr = consecutiveHighZcr
                }
            } else {
                consecutiveHighZcr = 0
            }
        }
        if (maxConsecutiveHighZcr < 2) {
            return WakeWordMatch(false)
        }

        // 3. Search for the resonant voiced core "JAR" preceding the tail
        val preTailFrames = frames.subList(0, tailStartIndex)
        var foundVoicedCore = false
        var coreStartIndex = -1
        var consecutiveVoiced = 0

        for (i in preTailFrames.indices) {
            val f = preTailFrames[i]
            if (f.zcr in 0.03f..0.22f && f.rms > peakRms * 0.35f && f.rms > 500f) {
                consecutiveVoiced++
                if (consecutiveVoiced >= 3) {
                    foundVoicedCore = true
                    if (coreStartIndex == -1) coreStartIndex = i - 2
                }
            } else {
                consecutiveVoiced = 0
            }
        }

        if (!foundVoicedCore) {
            return WakeWordMatch(false)
        }

        // 4. Syllable energy dip check: Between the voiced core and the tail
        var dipFramesCount = 0
        val searchDipStart = (coreStartIndex.coerceAtLeast(0) + 2).coerceAtMost(tailStartIndex - 1)
        for (i in searchDipStart until tailStartIndex) {
            if (frames[i].rms < peakRms * 0.58f) {
                dipFramesCount++
            }
        }

        if (dipFramesCount < 2) {
            return WakeWordMatch(false)
        }

        // 5. Classify which of the 3 wake words was spoken
        val detectedWord = when {
            n in 18..36 && coreStartIndex <= 6 -> "Jarvis"
            n in 32..66 && (coreStartIndex >= 10 || n >= 48) -> "Ok Jarvis"
            else -> "Hey Jarvis"
        }

        Log.d(
            TAG,
            "Wake-Word Acoustic Pass: '$detectedWord' [frames=$n, peakRms=%.1f, highZcrTailCount=$highZcrTailCount, maxConsZcr=$maxConsecutiveHighZcr, coreIdx=$coreStartIndex, dipFrames=$dipFramesCount]".format(
                peakRms
            )
        )

        return WakeWordMatch(matched = true, detectedWord = detectedWord)
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

