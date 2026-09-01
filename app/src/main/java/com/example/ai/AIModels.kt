package com.example.ai

/**
 * Chat message model representing system, user, assistant, and tool messages.
 */
data class ChatMessage(
    val role: String, // "system", "user", "assistant", "tool"
    val content: String? = null,
    val name: String? = null,
    val toolCallId: String? = null,
    val toolCalls: List<ToolCall>? = null
) {
    companion object {
        fun system(content: String) = ChatMessage(role = "system", content = content)
        fun user(content: String) = ChatMessage(role = "user", content = content)
        fun assistant(content: String, toolCalls: List<ToolCall>? = null) =
            ChatMessage(role = "assistant", content = content, toolCalls = toolCalls)
        fun tool(toolCallId: String, name: String, content: String) =
            ChatMessage(role = "tool", toolCallId = toolCallId, name = name, content = content)
    }
}

/**
 * Model for a tool call requested by the AI agent.
 */
data class ToolCall(
    val id: String,
    val type: String = "function",
    val functionName: String,
    val argumentsJson: String
)

/**
 * Model defining a tool's schema for function calling.
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parametersJson: String
)

/**
 * Response from an AI provider.
 */
data class AIResponse(
    val content: String?,
    val toolCalls: List<ToolCall> = emptyList(),
    val finishReason: String? = null,
    val rawResponse: String? = null
)
