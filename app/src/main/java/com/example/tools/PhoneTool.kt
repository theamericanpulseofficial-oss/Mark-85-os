package com.example.tools

import android.content.Context
import com.example.ai.ToolDefinition

/**
 * Result of executing a PhoneTool.
 */
data class ToolExecutionResult(
    val success: Boolean,
    val message: String,
    val speechResponse: String? = null,
    val requiresUserConfirmation: Boolean = false,
    val pendingActionPayload: String? = null
)

/**
 * Base interface for extensible phone agent tools.
 */
interface PhoneTool {
    val name: String
    val description: String
    val parametersJson: String
    val requiresConfirmation: Boolean

    /**
     * Executes the action with provided JSON arguments.
     */
    suspend fun execute(context: Context, argumentsJson: String): ToolExecutionResult

    fun toDefinition(): ToolDefinition {
        return ToolDefinition(
            name = name,
            description = description,
            parametersJson = parametersJson
        )
    }
}
