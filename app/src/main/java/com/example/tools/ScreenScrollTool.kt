package com.example.tools

import android.content.Context
import com.example.service.JarvisAccessibilityService
import org.json.JSONObject

/**
 * Tool for hands-free screen scrolling and navigation gestures in any foreground app
 * (e.g. WhatsApp chats, Instagram reels/feed, YouTube, web browsers, settings).
 */
class ScreenScrollTool : PhoneTool {
    override val name: String = "scroll_screen"
    override val description: String =
        "Scrolls the current open screen (direction: 'down', 'up', 'left', 'right') or performs navigation ('back', 'home', 'recents') in apps like WhatsApp, YouTube, Instagram, browser, etc."
    override val requiresConfirmation: Boolean = false

    override val parametersJson: String = """
        {
            "type": "object",
            "properties": {
                "direction": {
                    "type": "string",
                    "enum": ["down", "up", "left", "right", "back", "home", "recents"],
                    "description": "Direction to scroll or navigation action to execute"
                }
            },
            "required": ["direction"]
        }
    """.trimIndent()

    override suspend fun execute(context: Context, argumentsJson: String): ToolExecutionResult {
        val direction = try {
            val json = JSONObject(argumentsJson)
            json.optString("direction", "down").lowercase()
        } catch (e: Exception) {
            "down"
        }

        val a11yService = JarvisAccessibilityService.instance
        if (a11yService == null) {
            // Prompt user to enable Accessibility Service in Android Settings
            JarvisAccessibilityService.openAccessibilitySettings(context)
            return ToolExecutionResult(
                success = false,
                message = "Accessibility permission required to scroll screens. Opened Accessibility Settings.",
                speechResponse = "Sir, please enable Mark 85 OS in Accessibility Settings to allow automatic scrolling."
            )
        }

        return if (direction in listOf("back", "home", "recents")) {
            val success = a11yService.performGlobal(direction)
            ToolExecutionResult(
                success = success,
                message = "Performed $direction navigation action.",
                speechResponse = "Navigating $direction, sir."
            )
        } else {
            val success = a11yService.performScroll(direction)
            val friendly = when (direction) {
                "down" -> "down"
                "up" -> "up"
                "left" -> "left"
                "right" -> "right"
                else -> "down"
            }
            ToolExecutionResult(
                success = success,
                message = "Scrolled screen $friendly.",
                speechResponse = "Scrolling $friendly, sir."
            )
        }
    }
}
