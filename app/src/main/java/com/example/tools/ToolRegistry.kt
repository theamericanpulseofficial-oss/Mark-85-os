package com.example.tools

import android.content.Context
import com.example.ai.ToolDefinition

/**
 * Central registry holding all available PhoneTools for the JARVIS agent.
 */
class ToolRegistry(private val context: Context) {

    private val toolsMap = mutableMapOf<String, PhoneTool>()

    init {
        registerDefaultTools()
    }

    private fun registerDefaultTools() {
        register(OpenAppTool())
        register(LaunchUrlTool())
        register(PhoneCallTool())
        register(AlarmTool())
        register(TimerTool())
        register(MediaTool())
        register(NotificationTool())
        register(SettingsTool())
        register(ContactsTool())
        register(WebActionTool())
    }

    fun register(tool: PhoneTool) {
        toolsMap[tool.name] = tool
    }

    fun getTool(name: String): PhoneTool? = toolsMap[name]

    fun getAllDefinitions(): List<ToolDefinition> {
        return toolsMap.values.map { it.toDefinition() }
    }

    suspend fun executeTool(name: String, argumentsJson: String): ToolExecutionResult {
        val tool = toolsMap[name]
            ?: return ToolExecutionResult(
                success = false,
                message = "Tool '$name' is not registered.",
                speechResponse = "Sir, I do not have a capability for $name."
            )
        return tool.execute(context, argumentsJson)
    }
}
