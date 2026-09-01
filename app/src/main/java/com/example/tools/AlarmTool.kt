package com.example.tools

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import org.json.JSONObject

/**
 * Tool for setting alarms using Android's AlarmClock API.
 */
class AlarmTool : PhoneTool {
    override val name: String = "set_alarm"
    override val description: String =
        "Sets an alarm on the Android device for a specific hour and minute."
    override val requiresConfirmation: Boolean = false

    override val parametersJson: String = """
        {
            "type": "object",
            "properties": {
                "hour": {
                    "type": "integer",
                    "description": "Hour of the day (0-23) in 24-hour format"
                },
                "minutes": {
                    "type": "integer",
                    "description": "Minute of the hour (0-59)"
                },
                "message": {
                    "type": "string",
                    "description": "Optional alarm label or message, e.g. 'Wake up' or 'Meeting'"
                },
                "skipUi": {
                    "type": "boolean",
                    "description": "Whether to set the alarm silently without showing the clock app UI"
                }
            },
            "required": ["hour", "minutes"]
        }
    """.trimIndent()

    override suspend fun execute(context: Context, argumentsJson: String): ToolExecutionResult {
        val (hour, minutes, message, skipUi) = try {
            val json = JSONObject(argumentsJson)
            val h = json.getInt("hour")
            val m = json.optInt("minutes", 0)
            val msg = json.optString("message", "JARVIS Alarm")
            val skip = json.optBoolean("skipUi", true)
            Quadruple(h, m, msg, skip)
        } catch (e: Exception) {
            return ToolExecutionResult(
                success = false,
                message = "Invalid alarm parameters: ${e.message}",
                speechResponse = "Sir, please specify the hour and minute for the alarm."
            )
        }

        if (hour !in 0..23 || minutes !in 0..59) {
            return ToolExecutionResult(
                success = false,
                message = "Invalid time format: $hour:$minutes",
                speechResponse = "Sir, that time is invalid."
            )
        }

        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minutes)
                putExtra(AlarmClock.EXTRA_MESSAGE, message)
                putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)

            val formattedTime = String.format("%02d:%02d", hour, minutes)
            ToolExecutionResult(
                success = true,
                message = "Alarm set for $formattedTime ($message).",
                speechResponse = "Alarm set for $formattedTime, sir."
            )
        } catch (e: Exception) {
            ToolExecutionResult(
                success = false,
                message = "Could not set alarm: ${e.message}",
                speechResponse = "Sir, I was unable to set the alarm."
            )
        }
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
