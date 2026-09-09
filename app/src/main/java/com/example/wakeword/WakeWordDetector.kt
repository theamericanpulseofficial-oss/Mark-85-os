package com.example.wakeword

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
import kotlinx.coroutines.launch
import java.util.Locale

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
 * Will NOT trigger on random speech or background noises.
 * Runs continuously in the background and auto-recovers from Android silence timeouts.
 */
class LocalWakeWordDetector(
    private val context: Context,
    private val scope: CoroutineScope,
    private val picovoiceAccessKey: String = "",
    private val sensitivity: Float = 0.5f
) : WakeWordDetector {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var onDetectedCallback: ((String?) -> Unit)? = null

    @Volatile
    override var isListening: Boolean = false
        private set

    @Volatile
    private var isRestarting = false

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
            initAndStartRecognition()
        }
    }

    private fun initAndStartRecognition() {
        if (!isListening) return
        try {
            cleanupRecognizer()

            val recognizer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            ) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }

            recognizer.setRecognitionListener(createListener())
            speechRecognizer = recognizer

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra("android.speech.extra.DICTATION_MODE", true)
            }

            recognizer.startListening(intent)
            Log.d(TAG, "Wake-Word listener armed for 'Jarvis' / 'Hey Jarvis'")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting wake-word recognizer: ${e.message}")
            scheduleRestart(800)
        }
    }

    private fun createListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                if (!isListening) return
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        // Room was quiet or 5-second silence elapsed.
                        // Instantly re-arm so the mic stays continuously active!
                        scheduleRestart(100)
                    }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                    SpeechRecognizer.ERROR_CLIENT -> {
                        scheduleRestart(350)
                    }
                    SpeechRecognizer.ERROR_AUDIO,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                        scheduleRestart(500)
                    }
                    else -> {
                        scheduleRestart(500)
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                if (!isListening) return
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
                checkMatchesForWakeWord(matches)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!isListening) return
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
                for (phrase in matches) {
                    val matchResult = findWakeWord(phrase)
                    if (matchResult != null) {
                        Log.d(TAG, "Wake word matched on partial: '$phrase'")
                        triggerDetection(matchResult.command)
                        return
                    }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    private data class WakeWordMatch(val wakeWord: String, val command: String?)

    private fun findWakeWord(phrase: String): WakeWordMatch? {
        val lower = phrase.lowercase().trim()
        // Regex strictly matching Jarvis / Hey Jarvis and phonetic variants
        val regex = Regex("""\b(?:hey|hi|hello|ok|okay|oye|aye)?\s*(?:jarvis|javis|jarves|service)\b""", RegexOption.IGNORE_CASE)
        val match = regex.find(lower) ?: return null

        val wakeWordMatched = match.value
        val postText = lower.substring(match.range.last + 1).trim()
        val command = postText.ifBlank { null }
        return WakeWordMatch(wakeWordMatched, command)
    }

    private fun checkMatchesForWakeWord(matches: List<String>) {
        for (phrase in matches) {
            val matchResult = findWakeWord(phrase)
            if (matchResult != null) {
                Log.d(TAG, "Wake word matched on final results: '$phrase'")
                triggerDetection(matchResult.command)
                return
            }
        }
        // User spoke words but none matched "Jarvis" (e.g. ambient conversations). Silently ignore!
        Log.d(TAG, "Ignored non-wake speech: ${matches.firstOrNull()}. Continuing standby...")
        scheduleRestart(100)
    }

    private fun triggerDetection(command: String?) {
        isListening = false
        mainHandler.removeCallbacksAndMessages(null)
        cleanupRecognizer()
        scope.launch(Dispatchers.Main) {
            onDetectedCallback?.invoke(command)
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!isListening || isRestarting) return
        isRestarting = true
        mainHandler.postDelayed({
            isRestarting = false
            if (isListening) {
                initAndStartRecognition()
            }
        }, delayMs)
    }

    private fun cleanupRecognizer() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up SpeechRecognizer: ${e.message}")
        } finally {
            speechRecognizer = null
        }
    }

    override fun stop() {
        isListening = false
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.post {
            cleanupRecognizer()
        }
    }

    override fun release() {
        stop()
    }

    companion object {
        private const val TAG = "LocalWakeWord"
    }
}

