package com.example.tools

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import org.json.JSONObject

/**
 * Tool for setting countdown timers on Android devices.
 */
class TimerTool : PhoneTool {
    override val name: String = "set_timer"
    override val description: String =
        "Sets a countdown timer for a specified duration in seconds or minutes (e.g. '10 minutes', '30 seconds')."
    override val requiresConfirmation: Boolean = false

    override val parametersJson: String = """
        {
            "type": "object",
            "properties": {
                "durationSeconds": {
                    "type": "integer",
                    "description": "Total duration of the timer in seconds (e.g. 600 for 10 minutes)"
                },
                "label": {
                    "type": "string",
                    "description": "Optional label for the timer, e.g. 'Pasta' or 'Workout'"
                },
                "skipUi": {
                    "type": "boolean",
                    "description": "Whether to start the timer immediately without opening clock UI"
                }
            },
            "required": ["durationSeconds"]
        }
    """.trimIndent()

    override suspend fun execute(context: Context, argumentsJson: String): ToolExecutionResult {
        val (durationSeconds, label, skipUi) = try {
            val json = JSONObject(argumentsJson)
            val sec = json.getInt("durationSeconds")
            val lbl = json.optString("label", "JARVIS Timer")
            val skip = json.optBoolean("skipUi", true)
            Triple(sec, lbl, skip)
        } catch (e: Exception) {
            return ToolExecutionResult(
                success = false,
                message = "Invalid timer arguments: ${e.message}",
                speechResponse = "Sir, for how long should I set the timer?"
            )
        }

        if (durationSeconds <= 0) {
            return ToolExecutionResult(
                success = false,
                message = "Duration must be greater than zero.",
                speechResponse = "Sir, the timer duration must be positive."
            )
        }

        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, durationSeconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)

            val minutes = durationSeconds / 60
            val remainingSec = durationSeconds % 60
            val durationText = when {
                minutes > 0 && remainingSec > 0 -> "$minutes minute${if (minutes > 1) "s" else ""} and $remainingSec seconds"
                minutes > 0 -> "$minutes minute${if (minutes > 1) "s" else ""}"
                else -> "$remainingSec seconds"
            }

            ToolExecutionResult(
                success = true,
                message = "Timer set for $durationText ($label).",
                speechResponse = "Setting a $durationText timer, sir."
            )
        } catch (e: Exception) {
            ToolExecutionResult(
                success = false,
                message = "Failed to start timer: ${e.message}",
                speechResponse = "Sir, I couldn't start the timer."
            )
        }
    }
}
