package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import com.example.settings.JarvisSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * High-fidelity neural TTS engine powered by ElevenLabs Text-to-Speech API.
 * Supports ultra-realistic human voices (e.g. George, Adam, Daniel, Callum)
 * with low-latency turbo models (eleven_turbo_v2_5, eleven_flash_v2_5).
 *
 * Includes disk caching for zero-latency playback of common phrases
 * and automatic fallback to native Android TTS if offline or out of quota.
 */
class ElevenLabsTtsEngine(
    private val context: Context,
    private var settings: JarvisSettings
) : TextToSpeechEngine {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val fallbackTts = AndroidTextToSpeechEngine(
        context = context,
        speechRate = settings.ttsSpeed,
        pitch = settings.ttsPitch
    )

    private var mediaPlayer: MediaPlayer? = null
    private var isPlaying = false

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val audioCacheDir = File(context.cacheDir, "elevenlabs_voice_cache").apply { mkdirs() }

    init {
        prewarmHumanVoiceCache()
    }

    private fun getCacheFile(text: String, voiceId: String): File {
        val sanitized = text.trim().lowercase().replace(Regex("[^a-z0-9]"), "_").take(32)
        val hash = (text.trim().lowercase() + voiceId).hashCode()
        return File(audioCacheDir, "eleven_${voiceId}_${sanitized}_$hash.mp3")
    }

    /**
     * Pre-synthesizes and caches common responses on disk so that instant replies
     * play immediately with 0ms network latency and zero credit consumption.
     */
    fun prewarmHumanVoiceCache() {
        if (settings.elevenLabsApiKey.isBlank()) return
        scope.launch(Dispatchers.IO) {
            val commonPhrases = listOf(
                "Yes, sir?",
                "Bilkul, sir.",
                "At your service, sir.",
                "Ok sir, abhi karta hoon.",
                "Ji sir, bilkul.",
                "Ek second sir, check karta hoon.",
                "Sir, sun raha hoon. Boliye?",
                "Taking screenshot now, sir.",
                "Opening Wi-Fi control panel, sir.",
                "Wi-Fi enabled, sir.",
                "Wi-Fi turned off, sir.",
                "Volume raised, sir.",
                "Volume lowered, sir.",
                "Flashlight on, sir.",
                "Flashlight off, sir.",
                "Goodbye sir, standing by."
            )
            for (phrase in commonPhrases) {
                try {
                    val cacheFile = getCacheFile(phrase, settings.elevenLabsVoiceId)
                    if (!cacheFile.exists() || cacheFile.length() < 100) {
                        val audioBytes = fetchAudioFromElevenLabs(phrase, settings)
                        if (audioBytes != null && audioBytes.isNotEmpty()) {
                            cacheFile.writeBytes(audioBytes)
                            Log.d(TAG, "Pre-cached ElevenLabs voice for: '$phrase' (${audioBytes.size} bytes)")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Prewarm cache failed for '$phrase': ${e.message}")
                }
            }
        }
    }

    fun updateSettings(newSettings: JarvisSettings) {
        val keyOrVoiceChanged = this.settings.elevenLabsApiKey != newSettings.elevenLabsApiKey ||
                this.settings.elevenLabsVoiceId != newSettings.elevenLabsVoiceId ||
                this.settings.elevenLabsModel != newSettings.elevenLabsModel
        this.settings = newSettings
        fallbackTts.setRate(newSettings.ttsSpeed)
        fallbackTts.setPitch(newSettings.ttsPitch)
        if (keyOrVoiceChanged) {
            prewarmHumanVoiceCache()
        }
    }

    override fun speak(text: String, onComplete: (() -> Unit)?) {
        speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, onComplete)
    }

    override fun speak(text: String, queueMode: Int, onComplete: (() -> Unit)?) {
        if (text.isBlank()) {
            onComplete?.invoke()
            return
        }

        // If no ElevenLabs API key is configured, immediately use native TTS
        if (settings.elevenLabsApiKey.isBlank()) {
            fallbackTts.speak(text, queueMode, onComplete)
            return
        }

        stopCurrentPlayback()

        // 1. Instant Cache Hit Check
        val cacheFile = getCacheFile(text, settings.elevenLabsVoiceId)
        if (cacheFile.exists() && cacheFile.length() > 100) {
            try {
                playAudioFile(cacheFile, text, onComplete)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Failed to play cached ElevenLabs audio file: ${e.message}")
            }
        }

        // 2. Synthesize via ElevenLabs API
        scope.launch {
            try {
                val audioBytes = fetchAudioFromElevenLabs(text, settings)
                if (audioBytes != null && audioBytes.isNotEmpty()) {
                    try {
                        cacheFile.writeBytes(audioBytes)
                    } catch (_: Exception) {}
                    playAudioBytes(audioBytes, text, onComplete)
                } else {
                    Log.w(TAG, "ElevenLabs returned empty audio. Falling back to native TTS.")
                    fallbackTts.speak(text, queueMode, onComplete)
                }
            } catch (e: Exception) {
                Log.e(TAG, "ElevenLabs TTS failed: ${e.message}. Falling back to native TTS.", e)
                fallbackTts.speak(text, queueMode, onComplete)
            }
        }
    }

    private fun playAudioFile(file: File, originalText: String, onComplete: (() -> Unit)?) {
        try {
            requestAudioFocus()
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    this@ElevenLabsTtsEngine.isPlaying = false
                    releaseAudioFocus()
                    it.release()
                    mediaPlayer = null
                    onComplete?.invoke()
                }
                setOnErrorListener { mp, what, extra ->
                    Log.e(TAG, "MediaPlayer error ($what, $extra) on cached audio. Falling back.")
                    this@ElevenLabsTtsEngine.isPlaying = false
                    releaseAudioFocus()
                    mp.release()
                    mediaPlayer = null
                    fallbackTts.speak(originalText, onComplete)
                    true
                }
                prepare()
            }
            mediaPlayer = player
            isPlaying = true
            player.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play cached audio file: ${e.message}", e)
            fallbackTts.speak(originalText, onComplete)
        }
    }

    private fun playAudioBytes(bytes: ByteArray, originalText: String, onComplete: (() -> Unit)?) {
        try {
            requestAudioFocus()
            val tempFile = File.createTempFile("eleven_temp_", ".mp3", context.cacheDir)
            tempFile.deleteOnExit()
            tempFile.writeBytes(bytes)

            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(tempFile.absolutePath)
                setOnCompletionListener {
                    this@ElevenLabsTtsEngine.isPlaying = false
                    releaseAudioFocus()
                    it.release()
                    mediaPlayer = null
                    try { tempFile.delete() } catch (_: Exception) {}
                    onComplete?.invoke()
                }
                setOnErrorListener { mp, what, extra ->
                    Log.e(TAG, "MediaPlayer error ($what, $extra) on stream. Falling back.")
                    this@ElevenLabsTtsEngine.isPlaying = false
                    releaseAudioFocus()
                    mp.release()
                    mediaPlayer = null
                    try { tempFile.delete() } catch (_: Exception) {}
                    fallbackTts.speak(originalText, onComplete)
                    true
                }
                prepare()
            }
            mediaPlayer = player
            isPlaying = true
            player.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play ElevenLabs audio stream: ${e.message}", e)
            fallbackTts.speak(originalText, onComplete)
        }
    }

    private suspend fun fetchAudioFromElevenLabs(text: String, config: JarvisSettings): ByteArray? = withContext(Dispatchers.IO) {
        val voiceId = config.elevenLabsVoiceId.ifBlank { JarvisSettings.DEFAULT_ELEVENLABS_VOICE }
        val modelId = config.elevenLabsModel.ifBlank { JarvisSettings.DEFAULT_ELEVENLABS_MODEL }
        val apiKey = config.elevenLabsApiKey.trim()

        if (apiKey.isBlank()) return@withContext null

        val url = "https://api.elevenlabs.io/v1/text-to-speech/$voiceId?output_format=mp3_44100_128&optimize_streaming_latency=3"

        val voiceSettings = JSONObject().apply {
            put("stability", config.elevenLabsStability.toDouble())
            put("similarity_boost", config.elevenLabsSimilarity.toDouble())
            put("style", 0.0)
            put("use_speaker_boost", true)
        }

        val jsonBody = JSONObject().apply {
            put("text", text)
            put("model_id", modelId)
            put("voice_settings", voiceSettings)
        }

        val request = Request.Builder()
            .url(url)
            .header("xi-api-key", apiKey)
            .header("Content-Type", "application/json")
            .header("Accept", "audio/mpeg")
            .post(jsonBody.toString().toRequestBody(jsonMediaType))
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            val code = response.code
            val bodyBytes = response.body?.bytes()

            if (!response.isSuccessful || bodyBytes == null) {
                val errorMsg = bodyBytes?.let { String(it, StandardCharsets.UTF_8) } ?: "HTTP $code"
                Log.e(TAG, "ElevenLabs API error HTTP $code: $errorMsg")
                return@withContext null
            }
            return@withContext bodyBytes
        } catch (e: Exception) {
            Log.e(TAG, "ElevenLabs network call exception: ${e.message}", e)
            return@withContext null
        }
    }

    private fun stopCurrentPlayback() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (_: Exception) {}
        mediaPlayer = null
        isPlaying = false
        fallbackTts.stop()
        releaseAudioFocus()
    }

    override fun stop() {
        stopCurrentPlayback()
    }

    override fun shutdown() {
        stopCurrentPlayback()
        fallbackTts.shutdown()
    }

    override fun setRate(rate: Float) {
        fallbackTts.setRate(rate)
    }

    override fun setPitch(pitch: Float) {
        fallbackTts.setPitch(pitch)
    }

    private fun requestAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { /* Focus change */ }
                .build()
            audioFocusRequest = focusRequest
            am.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                null,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
    }

    private fun releaseAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let {
                try { am.abandonAudioFocusRequest(it) } catch (_: Exception) {}
            }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            try { am.abandonAudioFocus(null) } catch (_: Exception) {}
        }
    }

    companion object {
        private const val TAG = "ElevenLabsTts"

        /**
         * Test synthesis helper for instant preview inside the Settings screen.
         */
        suspend fun testVoiceSynthesis(
            context: Context,
            settings: JarvisSettings,
            testText: String
        ): Result<String> = withContext(Dispatchers.IO) {
            val apiKey = settings.elevenLabsApiKey.trim()
            if (apiKey.isBlank()) {
                return@withContext Result.failure(Exception("ElevenLabs API Key is missing. Please enter your API Key."))
            }

            val voiceId = settings.elevenLabsVoiceId.ifBlank { JarvisSettings.DEFAULT_ELEVENLABS_VOICE }
            val modelId = settings.elevenLabsModel.ifBlank { JarvisSettings.DEFAULT_ELEVENLABS_MODEL }

            val url = "https://api.elevenlabs.io/v1/text-to-speech/$voiceId?output_format=mp3_44100_128&optimize_streaming_latency=3"

            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .build()

            val voiceSettings = JSONObject().apply {
                put("stability", settings.elevenLabsStability.toDouble())
                put("similarity_boost", settings.elevenLabsSimilarity.toDouble())
                put("style", 0.0)
                put("use_speaker_boost", true)
            }

            val jsonBody = JSONObject().apply {
                put("text", testText)
                put("model_id", modelId)
                put("voice_settings", voiceSettings)
            }

            val request = Request.Builder()
                .url(url)
                .header("xi-api-key", apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "audio/mpeg")
                .post(jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            try {
                val response = client.newCall(request).execute()
                val code = response.code
                val bodyBytes = response.body?.bytes()

                if (!response.isSuccessful || bodyBytes == null) {
                    val rawError = bodyBytes?.let { String(it, StandardCharsets.UTF_8) } ?: "HTTP $code"
                    val detail = try {
                        val obj = JSONObject(rawError)
                        obj.optString("detail")
                            .ifBlank { obj.optJSONObject("detail")?.optString("message", "") ?: "" }
                            .ifBlank { obj.optString("message") }
                    } catch (_: Exception) {
                        rawError.take(120)
                    }

                    val msg = when (code) {
                        401 -> "Invalid ElevenLabs API Key (HTTP 401). Please check your key on elevenlabs.io."
                        403 -> "Access forbidden (HTTP 403). Check your key permissions or plan quota."
                        404 -> "Voice ID not found (HTTP 404): $voiceId"
                        429 -> "Rate limit or character quota exceeded (HTTP 429). Check your usage on elevenlabs.io."
                        else -> "ElevenLabs error ($code): $detail"
                    }
                    return@withContext Result.failure(Exception(msg))
                }

                // Play test audio on Main thread
                withContext(Dispatchers.Main) {
                    val tempFile = File.createTempFile("test_eleven_", ".mp3", context.cacheDir)
                    tempFile.deleteOnExit()
                    tempFile.writeBytes(bodyBytes)

                    val player = MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        setDataSource(tempFile.absolutePath)
                        setOnCompletionListener {
                            it.release()
                            tempFile.delete()
                        }
                        prepare()
                        start()
                    }
                }

                return@withContext Result.success("ElevenLabs neural voice synthesized & playing successfully! ($voiceId)")
            } catch (e: Exception) {
                return@withContext Result.failure(e)
            }
        }
    }
}
