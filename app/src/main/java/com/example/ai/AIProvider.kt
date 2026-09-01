package com.example.ai

/**
 * Interface abstracting AI providers (e.g. NvidiaProvider).
 * Enables plugging in other AI providers without altering application logic.
 */
interface AIProvider {
    val providerName: String

    suspend fun generateResponse(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        model: String,
        apiKey: String,
        endpoint: String = "",
        timeoutSeconds: Int = 30
    ): Result<AIResponse>

    suspend fun testConnection(
        apiKey: String,
        model: String,
        endpoint: String = "",
        timeoutSeconds: Int = 15
    ): Result<String>
}
