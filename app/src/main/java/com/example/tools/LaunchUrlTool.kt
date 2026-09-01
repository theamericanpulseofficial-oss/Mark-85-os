package com.example.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject

/**
 * Tool for launching legitimate URLs and websites safely using official Android Intents.
 */
class LaunchUrlTool : PhoneTool {
    override val name: String = "launch_url"
    override val description: String =
        "Opens a valid URL or website in the default Android browser (e.g. 'https://youtube.com', 'https://github.com')."
    override val requiresConfirmation: Boolean = false

    override val parametersJson: String = """
        {
            "type": "object",
            "properties": {
                "url": {
                    "type": "string",
                    "description": "Full HTTPS URL to open, e.g. 'https://github.com'"
                }
            },
            "required": ["url"]
        }
    """.trimIndent()

    override suspend fun execute(context: Context, argumentsJson: String): ToolExecutionResult {
        var url = try {
            val json = JSONObject(argumentsJson)
            json.optString("url", "")
        } catch (e: Exception) {
            ""
        }

        if (url.isBlank()) {
            return ToolExecutionResult(
                success = false,
                message = "URL parameter was not provided.",
                speechResponse = "Sir, what web address should I open?"
            )
        }

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }

        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolExecutionResult(
                success = true,
                message = "Opened URL: $url",
                speechResponse = "Opening the webpage now, sir."
            )
        } catch (e: Exception) {
            ToolExecutionResult(
                success = false,
                message = "Failed to open URL: ${e.message}",
                speechResponse = "Sir, I couldn't open that URL."
            )
        }
    }
}
