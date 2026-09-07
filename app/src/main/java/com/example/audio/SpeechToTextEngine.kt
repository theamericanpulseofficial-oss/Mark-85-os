package com.example.audio

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
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

    private fun muteBeepSound(mute: Boolean) {
        try {
            audioManager?.let { am ->
                if (mute) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0)
                        am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0)
                    }
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0)
                        am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not adjust system stream volume for silent recognition: ${e.message}")
        }
    }

    private fun initRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            try {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(createListener())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create SpeechRecognizer: ${e.message}")
            }
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
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("Speech recognition not supported on this device.")
            return
        }

        if (speechRecognizer == null) {
            initRecognizer()
        }

        this.onResultCallback = onResult
        this.onErrorCallback = onError

        try {
            muteBeepSound(true)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                // Headless mode - suppresses Google Assistant dialogs
                putExtra("android.speech.extra.DICTATION_MODE", true)
            }
            speechRecognizer?.startListening(intent)
            isListening = true
        } catch (e: Exception) {
            isListening = false
            muteBeepSound(false)
            onError("Failed to start voice capture: ${e.message}")
        }
    }

    override fun stopListening() {
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping SpeechRecognizer: ${e.message}")
        } finally {
            isListening = false
            muteBeepSound(false)
        }
    }

    override fun destroy() {
        stopListening()
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying SpeechRecognizer: ${e.message}")
        } finally {
            speechRecognizer = null
        }
    }

    companion object {
        private const val TAG = "SpeechToTextEngine"
    }
}
