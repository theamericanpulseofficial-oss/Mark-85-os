package com.example.audio

import android.content.Context
import com.example.settings.JarvisSettings

/**
 * Intelligent TTS Engine dispatcher for MARK OS (J.A.R.V.I.S.).
 *
 * Dynamically routes speech synthesis based on user settings:
 * 1. ElevenLabs Neural AI (Realistic British/Human Jarvis voice)
 * 2. Inworld AI / Kokoro (Realtime TTS-2)
 * 3. Android System TTS (Offline, zero-latency fallback)
 */
class SmartJarvisTtsEngine(
    private val context: Context,
    private var settings: JarvisSettings
) : TextToSpeechEngine {

    private val androidEngine = AndroidTextToSpeechEngine(
        context = context,
        speechRate = settings.ttsSpeed,
        pitch = settings.ttsPitch
    )

    private val elevenLabsEngine = ElevenLabsTtsEngine(
        context = context,
        settings = settings
    )

    private val inworldEngine = InworldKokoroTtsEngine(
        context = context,
        settings = settings
    )

    fun updateSettings(newSettings: JarvisSettings) {
        this.settings = newSettings
        androidEngine.setRate(newSettings.ttsSpeed)
        androidEngine.setPitch(newSettings.ttsPitch)
        elevenLabsEngine.updateSettings(newSettings)
        inworldEngine.updateSettings(newSettings)
    }

    private fun getActiveEngine(): TextToSpeechEngine {
        return when (settings.ttsProvider) {
            JarvisSettings.TTS_PROVIDER_ELEVENLABS -> {
                if (settings.elevenLabsApiKey.isNotBlank()) elevenLabsEngine else androidEngine
            }
            JarvisSettings.TTS_PROVIDER_INWORLD -> {
                if (settings.inworldApiKey.isNotBlank()) inworldEngine else androidEngine
            }
            else -> androidEngine
        }
    }

    override fun speak(text: String, onComplete: (() -> Unit)?) {
        getActiveEngine().speak(text, onComplete)
    }

    override fun speak(text: String, queueMode: Int, onComplete: (() -> Unit)?) {
        getActiveEngine().speak(text, queueMode, onComplete)
    }

    override fun stop() {
        androidEngine.stop()
        elevenLabsEngine.stop()
        inworldEngine.stop()
    }

    override fun shutdown() {
        androidEngine.shutdown()
        elevenLabsEngine.shutdown()
        inworldEngine.shutdown()
    }

    override fun setRate(rate: Float) {
        androidEngine.setRate(rate)
        elevenLabsEngine.setRate(rate)
        inworldEngine.setRate(rate)
    }

    override fun setPitch(pitch: Float) {
        androidEngine.setPitch(pitch)
        elevenLabsEngine.setPitch(pitch)
        inworldEngine.setPitch(pitch)
    }
}
