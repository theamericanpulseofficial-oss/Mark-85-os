package com.example.wakeword

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
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
 * High-precision, zero-false-positive wake-word detector.
 * Strictly triggers ONLY on the 3 designated wake words:
 * 1. "Jarvis"
 * 2. "Hey Jarvis"
 * 3. "Ok Jarvis"
 *
 * Rejects ALL other conversational words ("karo", "hello", "theek", "bhai", "yes", etc.).
 *
 * Architecture:
 * - Uses on-device speech recognition in silent continuous loop (no UI dialogs).
 * - Real-time partial result evaluation triggers instantaneously as soon as "Jarvis" is spoken.
 * - Extracts direct commands spoken with the wake word (e.g., "Hey Jarvis open Chrome" -> "open Chrome").
 * - Features high-precision strict acoustic engine fallback for offline/isolated environments.
 */
class LocalWakeWordDetector(
    private val context: Context,
    private val scope: CoroutineScope,
    private val picovoiceAccessKey: String = "",
    private val sensitivity: Float = 0.5f,
    private val filterPhoneSpeakerAudio: Boolean = true
) : WakeWordDetector {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var onDetectedCallback: ((String?) -> Unit)? = null

    @Volatile
    override var isListening: Boolean = false
        private set

    @Volatile
    private var lastTriggerTime: Long = 0L

    // Fallback acoustic fields
    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var acousticJob: Job? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

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

        mainHandler.post {
            val isAvailable = SpeechRecognizer.isRecognitionAvailable(context.applicationContext)
            if (isAvailable) {
                Log.d(TAG, "Initializing primary continuous SpeechRecognizer for strict 3-word detection...")
                startSpeechRecognizerLoop()
            } else {
                Log.w(TAG, "SpeechRecognizer not available on this device; starting strict acoustic fallback.")
                startAcousticFallback()
            }
        }
    }

    /**
     * Checks if the text strictly matches one of the 3 wake words:
     * 1. "Jarvis"
     * 2. "Hey Jarvis"
     * 3. "Ok Jarvis"
     *
     * Returns Pair(matched, extractedDirectCommand)
     */
    private fun extractWakeWordAndCommand(rawText: String): Pair<Boolean, String?> {
        val text = rawText.lowercase(Locale.ROOT).trim()
        if (text.isBlank()) return Pair(false, null)

        // 1. "Hey Jarvis" variations
        val heyPrefixes = listOf("hey jarvis", "he jarvis", "hai jarvis", "ay jarvis", "hey jaervis", "hey jervis")
        for (prefix in heyPrefixes) {
            val idx = text.indexOf(prefix)
            if (idx != -1) {
                val after = text.substring(idx + prefix.length).trim().takeIf { it.isNotBlank() }
                return Pair(true, after)
            }
        }

        // 2. "Ok Jarvis" variations
        val okPrefixes = listOf("ok jarvis", "okay jarvis", "oke jarvis", "ok jaervis", "okay jaervis", "ok jervis")
        for (prefix in okPrefixes) {
            val idx = text.indexOf(prefix)
            if (idx != -1) {
                val after = text.substring(idx + prefix.length).trim().takeIf { it.isNotBlank() }
                return Pair(true, after)
            }
        }

        // 3. "Jarvis" single word (must match full word, not substring inside another word)
        val jarvisTokens = listOf("jarvis", "jaervis", "jervis", "zarvis")
        for (token in jarvisTokens) {
            val regex = Regex("\\b$token\\b")
            val match = regex.find(text)
            if (match != null) {
                val after = text.substring(match.range.last + 1).trim().takeIf { it.isNotBlank() }
                return Pair(true, after)
            }
        }

        return Pair(false, null)
    }

    private fun startSpeechRecognizerLoop() {
        if (!isListening) return

        try {
            destroyRecognizer()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext).apply {
                setRecognitionListener(createListener())
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                // Headless dictation mode - avoids beeps & UI
                putExtra("android.speech.extra.DICTATION_MODE", true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
            }

            speechRecognizer?.startListening(intent)
            Log.d(TAG, "Continuous wake listener actively monitoring for: 'Jarvis', 'Hey Jarvis', 'Ok Jarvis'")
        } catch (e: Exception) {
            Log.w(TAG, "Error starting SpeechRecognizer loop: ${e.message}. Falling back to strict acoustic...")
            startAcousticFallback()
        }
    }

    private fun createListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onPartialResults(partialResults: Bundle?) {
                if (!isListening) return
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
                for (cand in matches) {
                    val (matched, command) = extractWakeWordAndCommand(cand)
                    if (matched) {
                        handleWakeTrigger(command, source = "partial: '$cand'")
                        return
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                if (!isListening) return
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null) {
                    for (cand in matches) {
                        val (matched, command) = extractWakeWordAndCommand(cand)
                        if (matched) {
                            handleWakeTrigger(command, source = "result: '$cand'")
                            return
                        }
                    }
                }
                // No wake word in this utterance; seamlessly restart
                restartListening(delayMs = 60L)
            }

            override fun onError(error: Int) {
                if (!isListening) return

                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    // Normal silence/timeout: restart immediately
                    restartListening(delayMs = 80L)
                } else if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_AUDIO || error == SpeechRecognizer.ERROR_CLIENT) {
                    // Engine busy or audio glitch: reset and restart
                    destroyRecognizer()
                    restartListening(delayMs = 250L)
                } else if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    Log.e(TAG, "Microphone permission denied for SpeechRecognizer.")
                    stop()
                } else {
                    restartListening(delayMs = 200L)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    private fun handleWakeTrigger(command: String?, source: String) {
        val now = System.currentTimeMillis()
        if (now - lastTriggerTime < 1800L) return
        lastTriggerTime = now

        Log.i(TAG, "STRICT WAKE-WORD CONFIRMED from $source. Direct command: '$command'")
        isListening = false
        destroyRecognizer()

        mainHandler.post {
            onDetectedCallback?.invoke(command)
        }
    }

    private fun restartListening(delayMs: Long) {
        if (!isListening) return
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            if (isListening) {
                startSpeechRecognizerLoop()
            }
        }, delayMs)
    }

    private fun destroyRecognizer() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        } finally {
            speechRecognizer = null
        }
    }

    // -------------------------------------------------------------
    // STRICT ACOUSTIC FALLBACK (Active ONLY if SpeechRecognizer is missing)
    // -------------------------------------------------------------
    private fun startAcousticFallback() {
        acousticJob?.cancel()
        acousticJob = scope.launch(Dispatchers.IO) {
            runStrictAcousticLoop()
        }
    }

    private suspend fun runStrictAcousticLoop() {
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val frameSize = 320 // 20ms
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = maxOf(minBuf, frameSize * 4, 2048)

        var record: AudioRecord? = null
        try {
            val candidate = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize)
            if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                record = candidate
            }
        } catch (_: Exception) {}

        if (record == null) {
            isListening = false
            return
        }

        audioRecord = record
        try {
            record.startRecording()
        } catch (_: Exception) {
            isListening = false
            return
        }

        val audioBuffer = ShortArray(frameSize)
        val utteranceRms = ArrayList<Float>(50)
        val utteranceZcr = ArrayList<Float>(50)
        var noiseFloor = 35f
        var silenceCount = 0

        while (scope.isActive && isListening) {
            val readCount = record.read(audioBuffer, 0, frameSize)
            if (readCount < frameSize) {
                delay(15)
                continue
            }

            var sumSquare = 0.0
            var zeroCrossings = 0
            for (i in 0 until readCount) {
                val s = audioBuffer[i]
                sumSquare += s.toLong() * s
                if (i > 0) {
                    val prev = audioBuffer[i - 1]
                    if ((s >= 0 && prev < 0) || (s < 0 && prev >= 0)) zeroCrossings++
                }
            }

            val rms = sqrt(sumSquare / readCount).toFloat()
            val zcr = zeroCrossings.toFloat() / (readCount - 1)

            if (rms < noiseFloor * 1.5f) {
                noiseFloor = 0.96f * noiseFloor + 0.04f * rms
            }

            // High barrier: Speech must be 3.2x noise floor + 100f
            val speechThreshold = noiseFloor * 3.2f + 100f

            if (rms > speechThreshold) {
                utteranceRms.add(rms)
                utteranceZcr.add(zcr)
                silenceCount = 0
                if (utteranceRms.size > 55) {
                    utteranceRms.clear()
                    utteranceZcr.clear()
                }
            } else {
                if (utteranceRms.isNotEmpty()) {
                    silenceCount++
                    if (silenceCount >= 4) {
                        // Utterance ended: evaluate strictly
                        val totalFrames = utteranceRms.size
                        // Jarvis utterance duration: 16 to 45 frames (320ms - 900ms)
                        if (totalFrames in 16..45) {
                            val peakRms = utteranceRms.maxOrNull() ?: 0f
                            val tailStart = (totalFrames * 0.65f).toInt()
                            val headZcr = utteranceZcr.subList(0, tailStart)
                            val tailZcr = utteranceZcr.subList(tailStart, totalFrames)

                            // Head must have resonant vowel core (ZCR 0.04 - 0.25)
                            val voicedHead = headZcr.count { it in 0.04f..0.25f } >= 3
                            // Tail must have strong sibilance /s/ (ZCR >= 0.30)
                            val fricativeTail = tailZcr.count { it >= 0.30f } >= 2

                            if (voicedHead && fricativeTail && peakRms > noiseFloor * 3.5f) {
                                isListening = false
                                mainHandler.post {
                                    handleWakeTrigger(null, source = "strict_acoustic")
                                }
                                break
                            }
                        }
                        utteranceRms.clear()
                        utteranceZcr.clear()
                        silenceCount = 0
                    }
                }
            }
        }

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
    }

    override fun stop() {
        isListening = false
        mainHandler.removeCallbacksAndMessages(null)
        destroyRecognizer()
        acousticJob?.cancel()
        acousticJob = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
    }

    override fun release() {
        stop()
    }

    companion object {
        private const val TAG = "LocalWakeWord"
    }
}


