package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.util.Base64
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
 * High-fidelity neural TTS engine supporting Inworld Realtime TTS-2 and Kokoro endpoints.
 * Provides ultra-realistic human speech with seamless fallback to Android native TTS
 * in case of network unavailability or invalid API credentials.
 */
class InworldKokoroTtsEngine(
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
    private var currentTempFile: File? = null
    private var isPlaying = false

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun updateSettings(newSettings: JarvisSettings) {
        this.settings = newSettings
        fallbackTts.setRate(newSettings.ttsSpeed)
        fallbackTts.setPitch(newSettings.ttsPitch)
    }

    override fun speak(text: String, onComplete: (() -> Unit)?) {
        if (text.isBlank()) {
            onComplete?.invoke()
            return
        }

        // Check if user selected Android Native TTS or Inworld API key is missing
        if (settings.ttsProvider == JarvisSettings.TTS_PROVIDER_ANDROID || settings.inworldApiKey.isBlank()) {
            fallbackTts.speak(text, onComplete)
            return
        }

        stopCurrentPlayback()

        scope.launch {
            try {
                val audioBytes = fetchAudioFromInworld(text, settings)
                if (audioBytes != null && audioBytes.isNotEmpty()) {
                    playAudioBytes(audioBytes, text, onComplete)
                } else {
                    Log.w(TAG, "Inworld TTS returned empty audio. Falling back to native TTS.")
                    fallbackTts.speak(text, onComplete)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Inworld TTS synthesis failed: ${e.message}. Falling back to native TTS.", e)
                fallbackTts.speak(text, onComplete)
            }
        }
    }

    private suspend fun fetchAudioFromInworld(text: String, config: JarvisSettings): ByteArray? = withContext(Dispatchers.IO) {
        val endpoint = config.inworldEndpoint.ifBlank { JarvisSettings.DEFAULT_INWORLD_ENDPOINT }
        val authHeader = formatAuthorizationHeader(config.inworldApiKey)

        val requestBodyJson = buildRequestBodyJson(text, config, endpoint)
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", authHeader)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, audio/mpeg, audio/wav, */*")
            .post(requestBodyJson.toRequestBody(jsonMediaType))
            .build()

        val response = httpClient.newCall(request).execute()
        val responseCode = response.code
        val bodyBytes = response.body?.bytes()

        if (!response.isSuccessful || bodyBytes == null) {
            val errorText = bodyBytes?.let { String(it, StandardCharsets.UTF_8) } ?: "Empty response"
            Log.e(TAG, "Inworld TTS API call returned HTTP $responseCode: $errorText")
            return@withContext null
        }

        val contentType = response.header("Content-Type", "")?.lowercase() ?: ""
        // If response is JSON containing base64 audioContent
        if (contentType.contains("application/json") || looksLikeJson(bodyBytes)) {
            val jsonString = String(bodyBytes, StandardCharsets.UTF_8)
            val jsonObject = JSONObject(jsonString)
            val base64Content = jsonObject.optString("audioContent", "")
                .ifBlank { jsonObject.optString("audio", "") }
            if (base64Content.isNotBlank()) {
                return@withContext Base64.decode(base64Content, Base64.DEFAULT)
            }
        }

        // Otherwise, it's direct raw audio stream (MP3 or WAV)
        return@withContext bodyBytes
    }

    private fun buildRequestBodyJson(text: String, config: JarvisSettings, endpoint: String): String {
        return if (endpoint.contains("audio/speech") || endpoint.contains("kokoro/v1")) {
            // OpenAI compatible Kokoro TTS endpoint
            val json = JSONObject()
            json.put("model", config.inworldModel.ifBlank { "kokoro" })
            json.put("input", text)
            json.put("voice", config.inworldVoiceId.ifBlank { "Dennis" })
            json.put("response_format", "mp3")
            json.put("speed", config.ttsSpeed)
            json.toString()
        } else {
            // Official Inworld AI Realtime TTS format
            val json = JSONObject()
            json.put("text", text)
            json.put("voiceId", config.inworldVoiceId.ifBlank { JarvisSettings.DEFAULT_INWORLD_VOICE })
            json.put("modelId", config.inworldModel.ifBlank { JarvisSettings.DEFAULT_INWORLD_MODEL })
            val audioConfig = JSONObject()
            audioConfig.put("audioEncoding", "MP3")
            audioConfig.put("sampleRateHertz", 24000)
            json.put("audioConfig", audioConfig)
            json.toString()
        }
    }

    private suspend fun playAudioBytes(
        bytes: ByteArray,
        originalText: String,
        onComplete: (() -> Unit)?
    ) = withContext(Dispatchers.Main) {
        try {
            val tempFile = File.createTempFile("inworld_tts_", ".mp3", context.cacheDir)
            tempFile.deleteOnExit()
            tempFile.writeBytes(bytes)
            currentTempFile = tempFile

            requestAudioFocus()

            val player = MediaPlayer()
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            player.setDataSource(tempFile.absolutePath)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    player.playbackParams = player.playbackParams.setSpeed(settings.ttsSpeed.coerceIn(0.5f, 2.0f))
                } catch (e: Exception) {
                    // Ignore device-specific playbackParams issues
                }
            }

            player.setOnCompletionListener { mp ->
                isPlaying = false
                releaseAudioFocus()
                mp.release()
                mediaPlayer = null
                tempFile.delete()
                currentTempFile = null
                onComplete?.invoke()
            }

            player.setOnErrorListener { mp, what, extra ->
                Log.e(TAG, "MediaPlayer error ($what, $extra). Falling back to native TTS.")
                isPlaying = false
                releaseAudioFocus()
                mp.release()
                mediaPlayer = null
                tempFile.delete()
                currentTempFile = null
                fallbackTts.speak(originalText, onComplete)
                true
            }

            player.prepare()
            mediaPlayer = player
            isPlaying = true
            player.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play Inworld audio: ${e.message}. Using fallback.", e)
            fallbackTts.speak(originalText, onComplete)
        }
    }

    private fun stopCurrentPlayback() {
        try {
            if (isPlaying && mediaPlayer != null) {
                mediaPlayer?.stop()
                mediaPlayer?.release()
                mediaPlayer = null
            }
        } catch (e: Exception) {
            // Ignore stop errors
        } finally {
            isPlaying = false
            currentTempFile?.delete()
            currentTempFile = null
            releaseAudioFocus()
        }
        fallbackTts.stop()
    }

    override fun stop() {
        stopCurrentPlayback()
    }

    override fun shutdown() {
        stopCurrentPlayback()
        fallbackTts.shutdown()
    }

    override fun setRate(rate: Float) {
        settings = settings.copy(ttsSpeed = rate)
        fallbackTts.setRate(rate)
    }

    override fun setPitch(pitch: Float) {
        settings = settings.copy(ttsPitch = pitch)
        fallbackTts.setPitch(pitch)
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

    private fun looksLikeJson(bytes: ByteArray): Boolean {
        for (b in bytes) {
            val c = b.toInt().toChar()
            if (c.isWhitespace()) continue
            return c == '{' || c == '['
        }
        return false
    }

    companion object {
        private const val TAG = "InworldKokoroTts"

        /**
         * Automatically formats authorization header for Inworld or Kokoro API.
         * Accepts apiKey:apiSecret, raw Base64 token, or pre-formatted 'Basic <token>'.
         */
        fun formatAuthorizationHeader(rawKey: String): String {
            val trimmed = rawKey.trim()
            if (trimmed.startsWith("Basic ", ignoreCase = true) || trimmed.startsWith("Bearer ", ignoreCase = true)) {
                return trimmed
            }
            return if (trimmed.contains(":")) {
                val encoded = Base64.encodeToString(trimmed.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                "Basic $encoded"
            } else {
                "Basic $trimmed"
            }
        }

        /**
         * Standalone test synthesis call to verify API credentials and preview voice.
         */
        suspend fun testVoiceSynthesis(
            context: Context,
            settings: JarvisSettings,
            testText: String = "Greetings, sir. Neural voice pipeline is active and nominal."
        ): Result<String> = withContext(Dispatchers.IO) {
            if (settings.inworldApiKey.isBlank()) {
                return@withContext Result.failure(Exception("Inworld / Kokoro API key is empty. Please enter your key."))
            }

            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .build()

            val endpoint = settings.inworldEndpoint.ifBlank { JarvisSettings.DEFAULT_INWORLD_ENDPOINT }
            val authHeader = formatAuthorizationHeader(settings.inworldApiKey)

            val json = JSONObject()
            if (endpoint.contains("audio/speech") || endpoint.contains("kokoro/v1")) {
                json.put("model", settings.inworldModel.ifBlank { "kokoro" })
                json.put("input", testText)
                json.put("voice", settings.inworldVoiceId.ifBlank { "Dennis" })
            } else {
                json.put("text", testText)
                json.put("voiceId", settings.inworldVoiceId.ifBlank { JarvisSettings.DEFAULT_INWORLD_VOICE })
                json.put("modelId", settings.inworldModel.ifBlank { JarvisSettings.DEFAULT_INWORLD_MODEL })
                val audioConfig = JSONObject()
                audioConfig.put("audioEncoding", "MP3")
                audioConfig.put("sampleRateHertz", 24000)
                json.put("audioConfig", audioConfig)
            }

            val request = Request.Builder()
                .url(endpoint)
                .header("Authorization", authHeader)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, audio/mpeg, audio/wav, */*")
                .post(json.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            try {
                val response = client.newCall(request).execute()
                val code = response.code
                val bodyBytes = response.body?.bytes()

                if (!response.isSuccessful || bodyBytes == null) {
                    val rawError = bodyBytes?.let { String(it, StandardCharsets.UTF_8) } ?: "HTTP $code"
                    val detail = try {
                        val obj = JSONObject(rawError)
                        obj.optString("message")
                            .ifBlank { obj.optString("error") }
                            .ifBlank { obj.optString("detail") }
                    } catch (e: Exception) {
                        rawError.take(120)
                    }
                    val msg = when (code) {
                        401 -> "Invalid Inworld API Key (HTTP 401). Please verify your key or signature."
                        403 -> "Access forbidden (HTTP 403). Check your Inworld workspace permissions."
                        404 -> "Endpoint or voice not found (HTTP 404): $detail"
                        429 -> "Rate limit reached (HTTP 429). Please wait a moment."
                        else -> "Inworld API error ($code): $detail"
                    }
                    return@withContext Result.failure(Exception(msg))
                }

                // Parse audio
                val audioBytes: ByteArray = if (bodyBytes.isNotEmpty() && (bodyBytes[0] == '{'.code.toByte())) {
                    val jsonResponse = JSONObject(String(bodyBytes, StandardCharsets.UTF_8))
                    val base64 = jsonResponse.optString("audioContent", "")
                        .ifBlank { jsonResponse.optString("audio", "") }
                    if (base64.isNotBlank()) {
                        Base64.decode(base64, Base64.DEFAULT)
                    } else {
                        bodyBytes
                    }
                } else {
                    bodyBytes
                }

                // Play audio on Main thread so user hears it immediately
                withContext(Dispatchers.Main) {
                    val tempFile = File.createTempFile("test_inworld_", ".mp3", context.cacheDir)
                    tempFile.deleteOnExit()
                    tempFile.writeBytes(audioBytes)

                    val player = MediaPlayer()
                    player.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    player.setDataSource(tempFile.absolutePath)
                    player.setOnCompletionListener {
                        it.release()
                        tempFile.delete()
                    }
                    player.prepare()
                    player.start()
                }

                return@withContext Result.success("Neural voice synthesized & playing (${settings.inworldVoiceId} / ${settings.inworldModel})")
            } catch (e: Exception) {
                return@withContext Result.failure(e)
            }
        }
    }
}
