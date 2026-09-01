package com.example.tools

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import org.json.JSONObject

/**
 * Tool for controlling Android media playback (Play, Pause, Next, Previous, Stop).
 */
class MediaTool : PhoneTool {
    override val name: String = "control_media"
    override val description: String =
        "Controls media playback on the device (actions: 'play', 'pause', 'play_pause', 'next', 'previous', 'stop')."
    override val requiresConfirmation: Boolean = false

    override val parametersJson: String = """
        {
            "type": "object",
            "properties": {
                "action": {
                    "type": "string",
                    "enum": ["play", "pause", "play_pause", "next", "previous", "stop"],
                    "description": "The media control action to trigger"
                }
            },
            "required": ["action"]
        }
    """.trimIndent()

    override suspend fun execute(context: Context, argumentsJson: String): ToolExecutionResult {
        val action = try {
            val json = JSONObject(argumentsJson)
            json.optString("action", "play_pause").lowercase()
        } catch (e: Exception) {
            "play_pause"
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager == null) {
            return ToolExecutionResult(
                success = false,
                message = "Audio service unavailable.",
                speechResponse = "Sir, audio services are currently unavailable."
            )
        }

        val keyCode = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }

        return try {
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))

            val friendlyAction = when (action) {
                "play" -> "Playing media"
                "pause" -> "Pausing media"
                "next" -> "Skipping to next track"
                "previous" -> "Playing previous track"
                "stop" -> "Stopping media playback"
                else -> "Toggling playback"
            }

            ToolExecutionResult(
                success = true,
                message = "Dispatched media action: $action",
                speechResponse = "$friendlyAction, sir."
            )
        } catch (e: Exception) {
            ToolExecutionResult(
                success = false,
                message = "Failed to dispatch media key event: ${e.message}",
                speechResponse = "Sir, I couldn't adjust media playback."
            )
        }
    }
}
