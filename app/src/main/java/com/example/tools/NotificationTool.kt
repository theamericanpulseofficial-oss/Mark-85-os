package com.example.tools

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.example.R
import org.json.JSONObject

/**
 * Tool for creating assistant notifications and reminders on the device.
 */
class NotificationTool : PhoneTool {
    override val name: String = "create_notification"
    override val description: String =
        "Posts an alert, note, or reminder notification directly in the Android notification shade."
    override val requiresConfirmation: Boolean = false

    override val parametersJson: String = """
        {
            "type": "object",
            "properties": {
                "title": {
                    "type": "string",
                    "description": "Title of the notification, e.g. 'JARVIS Reminder'"
                },
                "content": {
                    "type": "string",
                    "description": "Body message or details of the reminder"
                }
            },
            "required": ["title", "content"]
        }
    """.trimIndent()

    override suspend fun execute(context: Context, argumentsJson: String): ToolExecutionResult {
        val (title, content) = try {
            val json = JSONObject(argumentsJson)
            Pair(
                json.optString("title", "JARVIS Reminder"),
                json.optString("content", "")
            )
        } catch (e: Exception) {
            Pair("JARVIS Reminder", "")
        }

        if (content.isBlank()) {
            return ToolExecutionResult(
                success = false,
                message = "Notification content is empty.",
                speechResponse = "Sir, what should the notification say?"
            )
        }

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (notificationManager == null) {
            return ToolExecutionResult(
                success = false,
                message = "NotificationManager not available.",
                speechResponse = "Sir, notification services are unavailable."
            )
        }

        val notificationId = (System.currentTimeMillis() % 10000).toInt()
        val notification = NotificationCompat.Builder(context, "jarvis_assistant_channel")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(notificationId, notification)

        return ToolExecutionResult(
            success = true,
            message = "Posted notification: $title - $content",
            speechResponse = "I've posted that to your notifications, sir."
        )
    }
}
