package com.example.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Universal OpenAI-compatible & NVIDIA NIM implementation of [AIProvider].
 * Connects securely over HTTPS with auto-fallback for reasoning models,
 * intelligent endpoint resolution, and multi-provider key support.
 */
class NvidiaProvider(
    private val debugLogging: Boolean = false
) : AIProvider {

    override val providerName: String = "MARK 85 Neural Core"

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun createHttpClient(timeoutSeconds: Int): OkHttpClient {
        val effectiveTimeout = timeoutSeconds.coerceAtLeast(60)
        return OkHttpClient.Builder()
            .connectTimeout(effectiveTimeout.toLong(), TimeUnit.SECONDS)
            .readTimeout(effectiveTimeout.toLong(), TimeUnit.SECONDS)
            .writeTimeout(effectiveTimeout.toLong(), TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Resolves the most appropriate endpoint automatically based on API key prefix or model name.
     * Also sanitizes any user or custom endpoint so it always points to the valid chat/completions path.
     */
    private fun resolveEndpoint(customEndpoint: String, apiKey: String, model: String): String {
        val trimmed = customEndpoint.trim()
        val keyTrimmed = apiKey.trim()

        val rawEndpoint = if (trimmed.isNotBlank() && trimmed != DEFAULT_ENDPOINT) {
            trimmed
        } else {
            when {
                keyTrimmed.startsWith("sk-or-") -> "https://openrouter.ai/api/v1/chat/completions"
                keyTrimmed.startsWith("gsk_") -> "https://api.groq.com/openai/v1/chat/completions"
                keyTrimmed.startsWith("sk-proj-") || (keyTrimmed.startsWith("sk-") && !keyTrimmed.startsWith("sk-or-") && !keyTrimmed.startsWith("nvapi-")) -> "https://api.openai.com/v1/chat/completions"
                model.startsWith("deepseek") && !keyTrimmed.startsWith("nvapi-") -> "https://api.deepseek.com/chat/completions"
                else -> DEFAULT_ENDPOINT
            }
        }

        // Sanitize endpoint URL to prevent 404 errors caused by missing paths or trailing slashes
        var sanitized = rawEndpoint.trim().removeSuffix("/")
        if (!sanitized.endsWith("/chat/completions")) {
            sanitized = if (sanitized.endsWith("/v1")) {
                "$sanitized/chat/completions"
            } else {
                "$sanitized/v1/chat/completions"
            }
        }
        return sanitized
    }

    /**
     * Normalizes user-entered model names so they match exact provider requirements
     * without causing HTTP 404 (Model not found).
     */
    fun normalizeModelName(rawModel: String, endpointUrl: String, apiKey: String): String {
        val clean = rawModel.trim().trim('"', '\'', ' ')
        if (clean.isBlank()) return "meta/llama-3.3-70b-instruct"

        val isNvidia = endpointUrl.contains("nvidia.com") || apiKey.trim().startsWith("nvapi-")
        val isGroq = endpointUrl.contains("groq.com") || apiKey.trim().startsWith("gsk_")
        val isOpener = endpointUrl.contains("openrouter.ai") || apiKey.trim().startsWith("sk-or-")

        if (isNvidia) {
            val lower = clean.lowercase()
            return when {
                // If user already typed the full vendor prefix, keep it
                clean.contains("/") -> clean
                lower.contains("3.3") && lower.contains("70b") -> "meta/llama-3.3-70b-instruct"
                lower.contains("3.1") && lower.contains("8b") -> "meta/llama-3.1-8b-instruct"
                lower.contains("3.1") && lower.contains("70b") -> "meta/llama-3.1-70b-instruct"
                lower.contains("deepseek") && lower.contains("r1") -> "deepseek-ai/deepseek-r1"
                lower.contains("deepseek") && lower.contains("v3") -> "deepseek-ai/deepseek-v3"
                lower.contains("mistral") && (lower.contains("large") || lower.contains("2")) -> "mistralai/mistral-large-2-instruct"
                lower.contains("nemotron") -> "nvidia/llama-3.1-nemotron-70b-instruct"
                lower.contains("qwen") && lower.contains("72b") -> "qwen/qwen2.5-72b-instruct"
                lower.contains("gemma") -> "google/gemma-2-27b-it"
                lower.contains("llama") -> "meta/llama-3.3-70b-instruct"
                else -> "meta/$clean"
            }
        } else if (isGroq) {
            val lower = clean.lowercase()
            return when {
                lower.contains("70b") -> "llama-3.3-70b-versatile"
                lower.contains("8b") -> "llama-3.1-8b-instant"
                lower.contains("mixtral") -> "mixtral-8x7b-32768"
                else -> clean
            }
        } else if (isOpener) {
            // OpenRouter uses meta-llama/ rather than meta/
            if (clean.startsWith("meta/")) {
                return clean.replace("meta/", "meta-llama/")
            }
        }

        return clean
    }

    override suspend fun generateResponse(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        model: String,
        apiKey: String,
        endpoint: String,
        timeoutSeconds: Int
    ): Result<AIResponse> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(
                IllegalArgumentException("AI API Key is missing. Please enter your API Key in Settings.")
            )
        }

        val url = resolveEndpoint(endpoint, apiKey, model)
        val activeModel = normalizeModelName(model, url, apiKey)
        val effectiveTimeout = timeoutSeconds.coerceAtLeast(60)
        val client = createHttpClient(effectiveTimeout)

        // Attempt 1: Standard request with tools if tools provided
        if (tools.isNotEmpty()) {
            val toolsResult = executeApiCall(client, url, apiKey, activeModel, messages, tools)
            if (toolsResult.isSuccess) {
                return@withContext toolsResult
            }

            val toolsError = toolsResult.exceptionOrNull()
            val toolsErrorMsg = toolsError?.message?.lowercase() ?: ""
            if (debugLogging) {
                Log.w(TAG, "Request with tools failed ($toolsErrorMsg). Retrying without tools.")
            }
        }

        // Attempt 2: Request without tools (universal OpenAI compatible format)
        val plainTextResult = executeApiCall(client, url, apiKey, activeModel, messages, emptyList())
        if (plainTextResult.isSuccess) {
            return@withContext plainTextResult
        }

        val textError = plainTextResult.exceptionOrNull()
        val textErrorMsg = textError?.message?.lowercase() ?: ""

        // Attempt 3: If 404 (model not found) or server error / timeout, auto-recover with high-speed model
        val fastFallbackModel = when {
            url.contains("nvidia.com") || apiKey.trim().startsWith("nvapi-") -> "meta/llama-3.1-8b-instruct"
            url.contains("groq.com") || apiKey.trim().startsWith("gsk_") -> "llama-3.1-8b-instant"
            url.contains("openai.com") -> "gpt-4o-mini"
            url.contains("openrouter.ai") -> "meta-llama/llama-3.1-8b-instruct"
            else -> "meta/llama-3.1-8b-instruct"
        }

        if (fastFallbackModel != activeModel) {
            if (debugLogging) {
                Log.w(TAG, "Model $activeModel failed ($textErrorMsg). Auto-recovering with: $fastFallbackModel")
            }
            val recoveryResult = executeApiCall(client, url, apiKey, fastFallbackModel, messages, emptyList())
            if (recoveryResult.isSuccess) {
                return@withContext recoveryResult
            }
        }

        return@withContext plainTextResult
    }

    private suspend fun executeApiCall(
        client: OkHttpClient,
        url: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>
    ): Result<AIResponse> {
        val requestJson = buildRequestBody(messages, tools, model)
        var lastException: Exception? = null
        val maxRetries = 1

        for (attempt in 0..maxRetries) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer ${apiKey.trim()}")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .post(requestJson.toString().toRequestBody(jsonMediaType))
                    .build()

                if (debugLogging) {
                    Log.d(TAG, "Connecting to $url (Attempt ${attempt + 1}) for model: $model")
                }

                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string() ?: ""

                    if (!response.isSuccessful) {
                        val errorMsg = parseErrorMessage(response.code, responseBody)
                        if (response.code in 500..599 && attempt < maxRetries) {
                            delay(1000L)
                            lastException = IOException(errorMsg)
                            return@use
                        }
                        return Result.failure(IOException(errorMsg))
                    }

                    val aiResponse = parseSuccessResponse(responseBody)
                    return Result.success(aiResponse)
                }
            } catch (e: IOException) {
                lastException = e
                if (attempt < maxRetries) {
                    delay(1000L)
                }
            } catch (e: Exception) {
                return Result.failure(e)
            }
        }

        return Result.failure(
            lastException ?: IOException("Failed to connect to AI server at $url.")
        )
    }

    override suspend fun testConnection(
        apiKey: String,
        model: String,
        endpoint: String,
        timeoutSeconds: Int
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Please enter an API Key first."))
        }

        val testMessages = listOf(
            ChatMessage.user("Respond in one short sentence: 'J.A.R.V.I.S. neural core online, systems nominal.'")
        )

        val result = generateResponse(
            messages = testMessages,
            tools = emptyList(),
            model = model,
            apiKey = apiKey,
            endpoint = endpoint,
            timeoutSeconds = timeoutSeconds
        )

        result.fold(
            onSuccess = { response ->
                val rawReply = response.content?.trim() ?: "Connection verified successfully."
                val cleanReply = sanitizeThinking(rawReply)
                Result.success(cleanReply)
            },
            onFailure = { error ->
                Result.failure(error)
            }
        )
    }

    private fun buildRequestBody(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        model: String
    ): JSONObject {
        val root = JSONObject()
        root.put("model", model.trim())
        root.put("temperature", 0.3)
        root.put("max_tokens", 1024)

        val messagesArray = JSONArray()
        for (msg in messages) {
            val msgObj = JSONObject()
            if (tools.isEmpty()) {
                // When tools is empty (fallback or text mode), normalize roles so standard LLMs don't reject tool schema
                val role = if (msg.role == "tool") "user" else msg.role
                msgObj.put("role", role)
                val content = if (msg.role == "tool") {
                    "[Action result for ${msg.name ?: "system"}: ${msg.content ?: "completed"}]"
                } else {
                    msg.content ?: ""
                }
                msgObj.put("content", content)
            } else {
                msgObj.put("role", msg.role)
                if (msg.content != null) {
                    msgObj.put("content", msg.content)
                }
                if (msg.name != null) {
                    msgObj.put("name", msg.name)
                }
                if (msg.toolCallId != null) {
                    msgObj.put("tool_call_id", msg.toolCallId)
                }
                if (!msg.toolCalls.isNullOrEmpty()) {
                    val toolCallsArray = JSONArray()
                    for (tc in msg.toolCalls) {
                        val tcObj = JSONObject()
                        tcObj.put("id", tc.id)
                        tcObj.put("type", tc.type)
                        val fnObj = JSONObject()
                        fnObj.put("name", tc.functionName)
                        fnObj.put("arguments", tc.argumentsJson)
                        tcObj.put("function", fnObj)
                        toolCallsArray.put(tcObj)
                    }
                    msgObj.put("tool_calls", toolCallsArray)
                }
            }
            messagesArray.put(msgObj)
        }
        root.put("messages", messagesArray)

        // Only include tools if provided and not empty
        if (tools.isNotEmpty()) {
            val toolsArray = JSONArray()
            for (tool in tools) {
                val toolObj = JSONObject()
                toolObj.put("type", "function")
                val fnObj = JSONObject()
                fnObj.put("name", tool.name)
                fnObj.put("description", tool.description)
                fnObj.put("parameters", JSONObject(tool.parametersJson))
                toolObj.put("function", fnObj)
                toolsArray.put(toolObj)
            }
            root.put("tools", toolsArray)
            root.put("tool_choice", "auto")
        }

        return root
    }

    private fun parseSuccessResponse(responseBody: String): AIResponse {
        val root = JSONObject(responseBody)
        val choices = root.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            return AIResponse(content = null, rawResponse = responseBody)
        }

        val firstChoice = choices.getJSONObject(0)
        val finishReason = firstChoice.optString("finish_reason", null)
        val message = firstChoice.optJSONObject("message")

        val rawContent = message?.optString("content")?.takeIf { it.isNotBlank() && it != "null" }
        val cleanContent = if (rawContent != null) sanitizeThinking(rawContent) else null
        val toolCallsList = mutableListOf<ToolCall>()

        val toolCallsArray = message?.optJSONArray("tool_calls")
        if (toolCallsArray != null) {
            for (i in 0 until toolCallsArray.length()) {
                val tcObj = toolCallsArray.getJSONObject(i)
                val id = tcObj.optString("id", "call_$i")
                val type = tcObj.optString("type", "function")
                val functionObj = tcObj.optJSONObject("function")
                val name = functionObj?.optString("name", "") ?: ""
                val arguments = functionObj?.optString("arguments", "{}") ?: "{}"
                if (name.isNotBlank()) {
                    toolCallsList.add(
                        ToolCall(
                            id = id,
                            type = type,
                            functionName = name,
                            argumentsJson = arguments
                        )
                    )
                }
            }
        }

        return AIResponse(
            content = cleanContent,
            toolCalls = toolCallsList,
            finishReason = finishReason,
            rawResponse = responseBody
        )
    }

    /**
     * Strips <think> ... </think> reasoning tokens from models like DeepSeek-R1
     * so that the spoken voice output is clean and natural.
     */
    private fun sanitizeThinking(text: String): String {
        return text.replace(Regex("(?s)<think>.*?</think>"), "").trim()
    }

    private fun parseErrorMessage(statusCode: Int, responseBody: String): String {
        val parsedDetail = try {
            val root = JSONObject(responseBody)
            val errorObj = root.optJSONObject("error")
            val msg = errorObj?.optString("message")
            if (!msg.isNullOrBlank()) {
                msg
            } else {
                val detail = root.opt("detail")
                when (detail) {
                    is String -> detail
                    is JSONArray -> {
                        val first = detail.optJSONObject(0)
                        first?.optString("msg") ?: detail.toString()
                    }
                    is JSONObject -> detail.optString("msg", detail.toString())
                    else -> ""
                }
            }
        } catch (e: Exception) {
            ""
        }

        return when (statusCode) {
            401 -> "Invalid API key (HTTP 401). Please check the API Key entered in Settings."
            403 -> "Access forbidden (HTTP 403). The model may require special access or quota."
            404 -> "Model not found (HTTP 404): ${if (parsedDetail.isNotBlank()) parsedDetail else responseBody.take(100)}"
            422 -> "Unprocessable request (HTTP 422): ${if (parsedDetail.isNotBlank()) parsedDetail else responseBody.take(100)}"
            429 -> "Rate limit or quota exceeded (HTTP 429). Please wait a moment or check your API credits."
            in 500..599 -> "AI Server error (HTTP $statusCode): ${if (parsedDetail.isNotBlank()) parsedDetail else "Temporary service downtime"}"
            else -> "API error (HTTP $statusCode): ${if (parsedDetail.isNotBlank()) parsedDetail else responseBody.take(120)}"
        }
    }

    companion object {
        private const val TAG = "NvidiaProvider"
        const val DEFAULT_ENDPOINT = "https://integrate.api.nvidia.com/v1/chat/completions"
    }
}
