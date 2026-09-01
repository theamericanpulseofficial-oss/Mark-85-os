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
 * NVIDIA NIM / NVIDIA API implementation of [AIProvider].
 * Connects securely over HTTPS with configurable timeouts, retries, and tool schemas.
 */
class NvidiaProvider(
    private val debugLogging: Boolean = false
) : AIProvider {

    override val providerName: String = "NVIDIA NIM"

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun createHttpClient(timeoutSeconds: Int): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
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
                IllegalArgumentException("NVIDIA API Key is missing. Please set it in Settings.")
            )
        }

        val url = if (endpoint.isNotBlank()) endpoint else DEFAULT_ENDPOINT
        val client = createHttpClient(timeoutSeconds)
        val requestJson = buildRequestBody(messages, tools, model)

        var lastException: Exception? = null
        val maxRetries = 2

        for (attempt in 0..maxRetries) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .post(requestJson.toString().toRequestBody(jsonMediaType))
                    .build()

                if (debugLogging) {
                    Log.d(TAG, "Sending request to NVIDIA endpoint: $url (Attempt ${attempt + 1})")
                }

                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string() ?: ""

                    if (!response.isSuccessful) {
                        val errorMsg = parseErrorMessage(response.code, responseBody)
                        if (response.code in 500..599 && attempt < maxRetries) {
                            delay(1000L * (attempt + 1))
                            lastException = IOException(errorMsg)
                            return@use // continue retry loop
                        }
                        return@withContext Result.failure(IOException(errorMsg))
                    }

                    val aiResponse = parseSuccessResponse(responseBody)
                    return@withContext Result.success(aiResponse)
                }
            } catch (e: IOException) {
                lastException = e
                if (debugLogging) {
                    Log.e(TAG, "Network error on attempt ${attempt + 1}: ${e.message}")
                }
                if (attempt < maxRetries) {
                    delay(1000L * (attempt + 1))
                }
            } catch (e: Exception) {
                return@withContext Result.failure(e)
            }
        }

        Result.failure(
            lastException ?: IOException("Failed to connect to NVIDIA API after $maxRetries retries.")
        )
    }

    override suspend fun testConnection(
        apiKey: String,
        model: String,
        endpoint: String,
        timeoutSeconds: Int
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Please enter an NVIDIA API Key."))
        }

        val testMessages = listOf(
            ChatMessage.user("Respond with exactly: 'JARVIS online, systems nominal.'")
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
                val reply = response.content?.trim() ?: "Connection successful"
                Result.success(reply)
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
        root.put("model", model)
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

        val content = message?.optString("content")?.takeIf { it.isNotBlank() && it != "null" }
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
            content = content,
            toolCalls = toolCallsList,
            finishReason = finishReason,
            rawResponse = responseBody
        )
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
            401 -> "Invalid NVIDIA API key. Please verify your credentials in Settings."
            403 -> "Access forbidden: Model unavailable or subscription permissions restricted."
            404 -> "Model or endpoint not found on NVIDIA NIM. Check the model ID in Settings."
            429 -> "Rate limit reached on NVIDIA API. Please try again in a few moments."
            in 500..599 -> "NVIDIA AI service is temporarily unavailable (HTTP $statusCode). $parsedDetail"
            else -> "NVIDIA API error (HTTP $statusCode): ${if (parsedDetail.isNotBlank()) parsedDetail else responseBody.take(120)}"
        }
    }

    companion object {
        private const val TAG = "NvidiaProvider"
        const val DEFAULT_ENDPOINT = "https://integrate.api.nvidia.com/v1/chat/completions"
    }
}
