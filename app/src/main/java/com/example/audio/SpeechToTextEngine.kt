package com.example.audio

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * Interface for Speech-To-Text engines.
 */
interface SpeechToTextEngine {
    fun startListening(
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    )
    fun stopListening()
    fun destroy()
    val isListening: Boolean
}

/**
 * Native headless Android STT Engine.
 * Operates without Google Assistant UI dialogs or default activation tones ("tan-tan" beep).
 */
class AndroidSpeechRecognizerEngine(private val context: Context) : SpeechToTextEngine {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    @Volatile
    override var isListening: Boolean = false
        private set

    private var onResultCallback: ((String) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var previousNotificationStreamMute = false
    private var previousMusicMute = false

    init {
        initRecognizer()
    }

    private var originalMusicVol: Int = -1
    private var originalNotifVol: Int = -1
    private var originalSystemVol: Int = -1

    private fun muteBeepSound(mute: Boolean) {
        try {
            audioManager?.let { am ->
                if (mute) {
                    if (originalMusicVol == -1) {
                        originalMusicVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                        originalNotifVol = am.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
                        originalSystemVol = am.getStreamVolume(AudioManager.STREAM_SYSTEM)
                    }
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                    am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)
                    am.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0)
                } else {
                    if (originalMusicVol != -1) {
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, originalMusicVol, 0)
                        am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, originalNotifVol, 0)
                        am.setStreamVolume(AudioManager.STREAM_SYSTEM, originalSystemVol, 0)
                        originalMusicVol = -1
                        originalNotifVol = -1
                        originalSystemVol = -1
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not adjust system stream volume for silent recognition: ${e.message}")
        }
    }

    private fun initRecognizer() {
        mainHandler.post {
            val appContext = context.applicationContext
            if (SpeechRecognizer.isRecognitionAvailable(appContext)) {
                try {
                    speechRecognizer = createSpeechRecognizerInstance()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create SpeechRecognizer: ${e.message}")
                }
            }
        }
    }

    private fun createSpeechRecognizerInstance(): SpeechRecognizer {
        val appContext = context.applicationContext
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
        } else {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        }.apply {
            setRecognitionListener(createListener())
        }
    }

    private fun createListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                muteBeepSound(false)
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                isListening = false
                muteBeepSound(false)
            }

            override fun onError(error: Int) {
                isListening = false
                muteBeepSound(false)
                try {
                    speechRecognizer?.destroy()
                } catch (_: Exception) {}
                speechRecognizer = null

                val errorMsg = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                    SpeechRecognizer.ERROR_CLIENT -> "Speech recognition client error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
                    SpeechRecognizer.ERROR_NETWORK -> "Network communication error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timed out"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition engine busy"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
                    else -> "Recognition error ($error)"
                }
                Log.w(TAG, "SpeechRecognizer onError: $error ($errorMsg)")
                onErrorCallback?.invoke(errorMsg)
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                muteBeepSound(false)
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val recognizedText = matches?.firstOrNull() ?: ""
                if (recognizedText.isNotBlank()) {
                    onResultCallback?.invoke(recognizedText)
                } else {
                    onErrorCallback?.invoke("No speech detected")
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    override fun startListening(
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val appContext = context.applicationContext
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            onError("Speech recognition not supported on this device.")
            return
        }

        this.onResultCallback = onResult
        this.onErrorCallback = onError

        mainHandler.post {
            try {
                if (speechRecognizer == null) {
                    speechRecognizer = createSpeechRecognizerInstance()
                }
                muteBeepSound(true)
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    // Headless mode - suppresses Google Assistant dialogs
                    putExtra("android.speech.extra.DICTATION_MODE", true)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    }
                }
                speechRecognizer?.startListening(intent)
                isListening = true
            } catch (e: Exception) {
                isListening = false
                muteBeepSound(false)
                try {
                    speechRecognizer?.destroy()
                } catch (_: Exception) {}
                speechRecognizer = null
                Log.e(TAG, "Failed to start speech recognition: ${e.message}")
                onError("Failed to start voice capture: ${e.message}")
            }
        }
    }

    override fun stopListening() {
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping SpeechRecognizer: ${e.message}")
            } finally {
                isListening = false
                muteBeepSound(false)
            }
        }
    }

    override fun destroy() {
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.destroy()
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying SpeechRecognizer: ${e.message}")
            } finally {
                isListening = false
                speechRecognizer = null
            }
        }
    }

    companion object {
        private const val TAG = "SpeechToTextEngine"
    }
}
