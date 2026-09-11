package com.example.tools

import android.content.Context
import android.os.Build
import com.example.service.JarvisAccessibilityService

/**
 * Tool for capturing full phone screenshots hands-free.
 * Uses Android AccessibilityService global action.
 * If accessibility permission is not granted, informs user and redirects to settings.
 */
class ScreenshotTool : PhoneTool {
    override val name: String = "take_screenshot"
    override val description: String =
        "Captures a full screenshot of the device display screen."
    override val requiresConfirmation: Boolean = false

    override val parametersJson: String = """
        {
            "type": "object",
            "properties": {},
            "required": []
        }
    """.trimIndent()

    override suspend fun execute(context: Context, argumentsJson: String): ToolExecutionResult {
        if (!JarvisAccessibilityService.isServiceRunning()) {
            JarvisAccessibilityService.openAccessibilitySettings(context)
            return ToolExecutionResult(
                success = false,
                message = "Accessibility service is disabled. Opened Accessibility Settings.",
                speechResponse = "Sir, screenshot lene ke liye accessibility permission allow kijiye."
            )
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return ToolExecutionResult(
                success = false,
                message = "System screenshot requires Android 9 (Pie) or higher.",
                speechResponse = "Sir, taking screenshots requires Android 9 or higher."
            )
        }

        val service = JarvisAccessibilityService.instance
        val taken = service?.takeScreenshot() ?: false

        return if (taken) {
            ToolExecutionResult(
                success = true,
                message = "Screenshot captured successfully.",
                speechResponse = "Taking screenshot now, sir."
            )
        } else {
            ToolExecutionResult(
                success = false,
                message = "Failed to trigger screenshot gesture.",
                speechResponse = "Sir, unable to capture screenshot right now."
            )
        }
    }
}
