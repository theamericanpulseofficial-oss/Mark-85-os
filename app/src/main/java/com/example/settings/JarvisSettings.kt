package com.example.settings

import android.content.Context
import com.example.security.SecurePreferencesHelper

/**
 * Encapsulates JARVIS settings and preferences.
 */
data class JarvisSettings(
    val nvidiaApiKey: String = "",
    val nvidiaModel: String = DEFAULT_NVIDIA_AGENT_MODEL,
    val voiceModel: String = DEFAULT_NVIDIA_VOICE_MODEL,
    val nvidiaEndpoint: String = DEFAULT_NVIDIA_ENDPOINT,
    val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    val picovoiceAccessKey: String = "",
    val wakeWordEnabled: Boolean = true,
    val wakeWordSensitivity: Float = 0.5f,
    val ttsSpeed: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    val ttsVolume: Float = 1.0f,
    val debugLogging: Boolean = false
) {
    companion object {
        const val DEFAULT_NVIDIA_AGENT_MODEL = "nemotron-3-ultra-550b-a55b"
        const val DEFAULT_NVIDIA_VOICE_MODEL = "nemotron-voicechat"
        const val DEFAULT_NVIDIA_ENDPOINT = "https://integrate.api.nvidia.com/v1/chat/completions"
        const val DEFAULT_TIMEOUT_SECONDS = 30

        val PRESET_AGENT_MODELS = listOf(
            "meta/llama-3.3-70b-instruct",
            "meta/llama-3.1-405b-instruct",
            "meta/llama-3.1-70b-instruct",
            "meta/llama-3.1-8b-instruct",
            "deepseek-ai/deepseek-r1",
            "deepseek-ai/deepseek-v3",
            "mistralai/mixtral-8x22b-instruct",
            "mistralai/mistral-large-2-instruct",
            "nvidia/llama-3.1-nemotron-70b-instruct",
            "nvidia/nemotron-4-340b-instruct",
            "google/gemma-2-27b-it",
            "qwen/qwen2.5-72b-instruct",
            "microsoft/phi-3-medium-128k-instruct",
            "nemotron-3-ultra-550b-a55b"
        )

        val PRESET_VOICE_MODELS = listOf(
            "meta/llama-3.3-70b-instruct",
            "meta/llama-3.1-8b-instruct",
            "nemotron-voicechat",
            "nvidia/riva-stt-tts-en",
            "fastconformer-hybrid-large-en"
        )

        fun load(context: Context): JarvisSettings {
            val prefs = SecurePreferencesHelper(context)
            return JarvisSettings(
                nvidiaApiKey = prefs.getString(SecurePreferencesHelper.KEY_NVIDIA_API_KEY, ""),
                nvidiaModel = prefs.getString(
                    SecurePreferencesHelper.KEY_NVIDIA_MODEL,
                    DEFAULT_NVIDIA_AGENT_MODEL
                ),
                voiceModel = prefs.getString(
                    SecurePreferencesHelper.KEY_VOICE_MODEL,
                    DEFAULT_NVIDIA_VOICE_MODEL
                ),
                nvidiaEndpoint = prefs.getString(
                    SecurePreferencesHelper.KEY_NVIDIA_ENDPOINT,
                    DEFAULT_NVIDIA_ENDPOINT
                ),
                timeoutSeconds = prefs.getInt(
                    SecurePreferencesHelper.KEY_TIMEOUT_SECONDS,
                    DEFAULT_TIMEOUT_SECONDS
                ),
                picovoiceAccessKey = prefs.getString(SecurePreferencesHelper.KEY_PICOVOICE_KEY, ""),
                wakeWordEnabled = prefs.getBoolean(SecurePreferencesHelper.KEY_WAKEWORD_ENABLED, true),
                wakeWordSensitivity = prefs.getFloat(SecurePreferencesHelper.KEY_WAKEWORD_SENSITIVITY, 0.5f),
                ttsSpeed = prefs.getFloat(SecurePreferencesHelper.KEY_TTS_SPEED, 1.0f),
                ttsPitch = prefs.getFloat(SecurePreferencesHelper.KEY_TTS_PITCH, 1.0f),
                ttsVolume = prefs.getFloat(SecurePreferencesHelper.KEY_TTS_VOLUME, 1.0f),
                debugLogging = prefs.getBoolean(SecurePreferencesHelper.KEY_DEBUG_LOGGING, false)
            )
        }

        fun save(context: Context, settings: JarvisSettings) {
            val prefs = SecurePreferencesHelper(context)
            prefs.saveString(SecurePreferencesHelper.KEY_NVIDIA_API_KEY, settings.nvidiaApiKey)
            prefs.saveString(SecurePreferencesHelper.KEY_NVIDIA_MODEL, settings.nvidiaModel)
            prefs.saveString(SecurePreferencesHelper.KEY_VOICE_MODEL, settings.voiceModel)
            prefs.saveString(SecurePreferencesHelper.KEY_NVIDIA_ENDPOINT, settings.nvidiaEndpoint)
            prefs.saveInt(SecurePreferencesHelper.KEY_TIMEOUT_SECONDS, settings.timeoutSeconds)
            prefs.saveString(SecurePreferencesHelper.KEY_PICOVOICE_KEY, settings.picovoiceAccessKey)
            prefs.saveBoolean(SecurePreferencesHelper.KEY_WAKEWORD_ENABLED, settings.wakeWordEnabled)
            prefs.saveFloat(SecurePreferencesHelper.KEY_WAKEWORD_SENSITIVITY, settings.wakeWordSensitivity)
            prefs.saveFloat(SecurePreferencesHelper.KEY_TTS_SPEED, settings.ttsSpeed)
            prefs.saveFloat(SecurePreferencesHelper.KEY_TTS_PITCH, settings.ttsPitch)
            prefs.saveFloat(SecurePreferencesHelper.KEY_TTS_VOLUME, settings.ttsVolume)
            prefs.saveBoolean(SecurePreferencesHelper.KEY_DEBUG_LOGGING, settings.debugLogging)
        }
    }
}
