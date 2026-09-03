package com.example.settings

import android.content.Context
import com.example.security.SecurePreferencesHelper

/**
 * Encapsulates MARK 85 OS settings and configurations.
 * Unified for single-API key and single-model operation with full LiveKit integration.
 */
data class JarvisSettings(
    val nvidiaApiKey: String = "",
    val nvidiaModel: String = DEFAULT_NVIDIA_AGENT_MODEL,
    val voiceModel: String = DEFAULT_NVIDIA_AGENT_MODEL, // Unified with nvidiaModel
    val nvidiaEndpoint: String = DEFAULT_NVIDIA_ENDPOINT,
    val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    val temperature: Float = DEFAULT_TEMPERATURE,
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val livekitUrl: String = DEFAULT_LIVEKIT_URL,
    val livekitApiKey: String = DEFAULT_LIVEKIT_API_KEY,
    val livekitSecret: String = DEFAULT_LIVEKIT_SECRET,
    val picovoiceAccessKey: String = "",
    val wakeWordEnabled: Boolean = true,
    val wakeWordSensitivity: Float = 0.5f,
    val ttsSpeed: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    val ttsVolume: Float = 1.0f,
    val debugLogging: Boolean = false
) {
    // Convenience aliases
    val aiApiKey: String get() = nvidiaApiKey
    val aiModel: String get() = nvidiaModel

    companion object {
        const val DEFAULT_NVIDIA_AGENT_MODEL = "meta/llama-3.3-70b-instruct"
        const val DEFAULT_NVIDIA_ENDPOINT = "https://integrate.api.nvidia.com/v1/chat/completions"
        const val DEFAULT_TIMEOUT_SECONDS = 60
        const val DEFAULT_TEMPERATURE = 0.3f
        const val DEFAULT_MAX_TOKENS = 1024

        // Hardcoded user LiveKit credentials as default
        const val DEFAULT_LIVEKIT_URL = "wss://jarvis-dnr09c6u.livekit.cloud"
        const val DEFAULT_LIVEKIT_API_KEY = "APIHVZxMkwNEhe5"
        const val DEFAULT_LIVEKIT_SECRET = "JgR3Q1KfkrQhpLnzjfPJ98voFXlA45wsFfxBuTOOP2oA"

        const val DEFAULT_SYSTEM_PROMPT = """You are MARK 85 OS (J.A.R.V.I.S.), an advanced, polite, and razor-sharp AI phone operating assistant inspired by Tony Stark's Mark 85 nanotech armor systems.
Address the user respectfully as "sir" when appropriate.
Keep your verbal spoken answers brief, natural, elegant, confident, and actionable."""

        val PRESET_AGENT_MODELS = listOf(
            "meta/llama-3.3-70b-instruct",
            "meta/llama-3.1-70b-instruct",
            "meta/llama-3.1-8b-instruct",
            "deepseek-ai/deepseek-r1",
            "deepseek-ai/deepseek-v3",
            "mistralai/mistral-large-2-instruct",
            "mistralai/mixtral-8x22b-instruct",
            "nvidia/llama-3.1-nemotron-70b-instruct",
            "nvidia/nemotron-4-340b-instruct",
            "google/gemma-2-27b-it",
            "qwen/qwen2.5-72b-instruct",
            "microsoft/phi-3-medium-128k-instruct",
            "gpt-4o-mini",
            "gpt-4o",
            "llama-3.3-70b-versatile"
        )

        val PRESET_VOICE_MODELS = PRESET_AGENT_MODELS

        fun load(context: Context): JarvisSettings {
            val prefs = SecurePreferencesHelper(context)
            val model = prefs.getString(
                SecurePreferencesHelper.KEY_NVIDIA_MODEL,
                DEFAULT_NVIDIA_AGENT_MODEL
            )
            return JarvisSettings(
                nvidiaApiKey = prefs.getString(SecurePreferencesHelper.KEY_NVIDIA_API_KEY, ""),
                nvidiaModel = model,
                voiceModel = model,
                nvidiaEndpoint = prefs.getString(
                    SecurePreferencesHelper.KEY_NVIDIA_ENDPOINT,
                    DEFAULT_NVIDIA_ENDPOINT
                ),
                timeoutSeconds = prefs.getInt(
                    SecurePreferencesHelper.KEY_TIMEOUT_SECONDS,
                    DEFAULT_TIMEOUT_SECONDS
                ),
                temperature = prefs.getFloat(
                    SecurePreferencesHelper.KEY_TEMPERATURE,
                    DEFAULT_TEMPERATURE
                ),
                maxTokens = prefs.getInt(
                    SecurePreferencesHelper.KEY_MAX_TOKENS,
                    DEFAULT_MAX_TOKENS
                ),
                systemPrompt = prefs.getString(
                    SecurePreferencesHelper.KEY_SYSTEM_PROMPT,
                    DEFAULT_SYSTEM_PROMPT
                ),
                livekitUrl = prefs.getString(
                    SecurePreferencesHelper.KEY_LIVEKIT_URL,
                    DEFAULT_LIVEKIT_URL
                ),
                livekitApiKey = prefs.getString(
                    SecurePreferencesHelper.KEY_LIVEKIT_API_KEY,
                    DEFAULT_LIVEKIT_API_KEY
                ),
                livekitSecret = prefs.getString(
                    SecurePreferencesHelper.KEY_LIVEKIT_SECRET,
                    DEFAULT_LIVEKIT_SECRET
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
            prefs.saveString(SecurePreferencesHelper.KEY_VOICE_MODEL, settings.nvidiaModel)
            prefs.saveString(SecurePreferencesHelper.KEY_NVIDIA_ENDPOINT, settings.nvidiaEndpoint)
            prefs.saveInt(SecurePreferencesHelper.KEY_TIMEOUT_SECONDS, settings.timeoutSeconds)
            prefs.saveFloat(SecurePreferencesHelper.KEY_TEMPERATURE, settings.temperature)
            prefs.saveInt(SecurePreferencesHelper.KEY_MAX_TOKENS, settings.maxTokens)
            prefs.saveString(SecurePreferencesHelper.KEY_SYSTEM_PROMPT, settings.systemPrompt)
            prefs.saveString(SecurePreferencesHelper.KEY_LIVEKIT_URL, settings.livekitUrl)
            prefs.saveString(SecurePreferencesHelper.KEY_LIVEKIT_API_KEY, settings.livekitApiKey)
            prefs.saveString(SecurePreferencesHelper.KEY_LIVEKIT_SECRET, settings.livekitSecret)
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
