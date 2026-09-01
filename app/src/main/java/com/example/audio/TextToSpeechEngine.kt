package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Interface for Text-To-Speech engines.
 */
interface TextToSpeechEngine {
    fun speak(text: String, onComplete: (() -> Unit)? = null)
    fun stop()
    fun shutdown()
    fun setRate(rate: Float)
    fun setPitch(pitch: Float)
}

/**
 * Native Android TTS engine configured with JARVIS assistant styling.
 */
class AndroidTextToSpeechEngine(
    private val context: Context,
    private var speechRate: Float = 1.0f,
    private var pitch: Float = 1.0f
) : TextToSpeechEngine, TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private val pendingSpeechQueue = mutableListOf<Pair<String, (() -> Unit)?>>()
    private val completionCallbacks = mutableMapOf<String, (() -> Unit)?>()

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
            // Prefer UK English for authentic JARVIS cadence, or fall back to default
            val result = tts?.setLanguage(Locale.UK)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.getDefault())
            }

            tts?.setSpeechRate(speechRate)
            tts?.setPitch(pitch)

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    releaseAudioFocus()
                    if (utteranceId != null) {
                        val callback = completionCallbacks.remove(utteranceId)
                        callback?.invoke()
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    releaseAudioFocus()
                    if (utteranceId != null) {
                        val callback = completionCallbacks.remove(utteranceId)
                        callback?.invoke()
                    }
                }
            })

            // Process any pending speech items
            synchronized(pendingSpeechQueue) {
                for ((text, callback) in pendingSpeechQueue) {
                    speak(text, callback)
                }
                pendingSpeechQueue.clear()
            }
        } else {
            Log.e(TAG, "TextToSpeech initialization failed ($status).")
        }
    }

    override fun speak(text: String, onComplete: (() -> Unit)?) {
        if (text.isBlank()) {
            onComplete?.invoke()
            return
        }

        if (!isInitialized || tts == null) {
            synchronized(pendingSpeechQueue) {
                pendingSpeechQueue.add(Pair(text, onComplete))
            }
            return
        }

        requestAudioFocus()

        val utteranceId = "jarvis_${System.currentTimeMillis()}"
        completionCallbacks[utteranceId] = onComplete

        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)

        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(audioAttributes)
                .build()
            audioFocusRequest = request
            audioManager?.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
    }

    private fun releaseAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(null)
        }
    }

    override fun stop() {
        tts?.stop()
        releaseAudioFocus()
        completionCallbacks.clear()
    }

    override fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }

    override fun setRate(rate: Float) {
        this.speechRate = rate
        tts?.setSpeechRate(rate)
    }

    override fun setPitch(pitch: Float) {
        this.pitch = pitch
        tts?.setPitch(pitch)
    }

    companion object {
        private const val TAG = "TextToSpeechEngine"
    }
}
