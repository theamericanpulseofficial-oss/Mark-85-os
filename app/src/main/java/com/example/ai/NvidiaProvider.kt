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
        return OkHttpClient.Builder()
            .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Resolves the most appropriate endpoint if user didn't customize it,
     * based on API key prefix or model name.
     */
    private fun resolveEndpoint(customEndpoint: String, apiKey: String, model: String): String {
        val trimmed = customEndpoint.trim()
        if (trimmed.isNotBlank() && trimmed != DEFAULT_ENDPOINT) {
            return trimmed
        }
        val keyTrimmed = apiKey.trim()
        return when {
            keyTrimmed.startsWith("sk-or-") -> "https://openrouter.ai/api/v1/chat/completions"
            keyTrimmed.startsWith("gsk_") -> "https://api.groq.com/openai/v1/chat/completions"
            keyTrimmed.startsWith("sk-proj-") || (keyTrimmed.startsWith("sk-") && !keyTrimmed.startsWith("sk-or-") && !keyTrimmed.startsWith("nvapi-")) -> "https://api.openai.com/v1/chat/completions"
            model.startsWith("deepseek") && !keyTrimmed.startsWith("nvapi-") -> "https://api.deepseek.com/chat/completions"
            else -> DEFAULT_ENDPOINT
        }
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
        val client = createHttpClient(timeoutSeconds)

        // Try standard request first with tools
        val firstAttemptResult = executeApiCall(client, url, apiKey, model, messages, tools)
        if (firstAttemptResult.isSuccess) {
            return@withContext firstAttemptResult
        }

        val firstError = firstAttemptResult.exceptionOrNull()
        val errorMsg = firstError?.message?.lowercase() ?: ""

        // If failure was due to tools/function calling unsupported by this model (common with DeepSeek-R1 / custom models),
        // automatically fallback and retry without tools!
        if (tools.isNotEmpty() && (errorMsg.contains("tool") || errorMsg.contains("function") || errorMsg.contains("schema") || errorMsg.contains("400") || errorMsg.contains("422"))) {
            if (debugLogging) {
                Log.w(TAG, "Model $model rejected tools schema. Retrying without tools.")
            }
            val fallbackResult = executeApiCall(client, url, apiKey, model, messages, emptyList())
            if (fallbackResult.isSuccess) {
                return@withContext fallbackResult
            }
        }

        return@withContext firstAttemptResult
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
            errorObj?.optString("message") ?: root.optString("detail", "")
        } catch (e: Exception) {
            ""
        }

        return when (statusCode) {
            401 -> "Invalid API key (HTTP 401). Please check the API Key entered in Settings."
            403 -> "Access forbidden (HTTP 403). The model may require special access or quota."
            404 -> "Model not found (HTTP 404): ${if (parsedDetail.isNotBlank()) parsedDetail else responseBody.take(100)}"
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
