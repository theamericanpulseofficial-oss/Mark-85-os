package com.example.tools

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import android.provider.Settings
import org.json.JSONObject

/**
 * Tool for inspecting device status (battery level, charging status) and controlling volume/brightness.
 */
class DeviceControlTool : PhoneTool {
    override val name: String = "control_device"
    override val description: String =
        "Controls or inspects device state: 'battery_status', 'volume_up', 'volume_down', 'mute', 'max_volume', 'brightness'."
    override val requiresConfirmation: Boolean = false

    override val parametersJson: String = """
        {
            "type": "object",
            "properties": {
                "command": {
                    "type": "string",
                    "enum": ["battery_status", "volume_up", "volume_down", "mute", "unmute", "max_volume"],
                    "description": "Device control action or query"
                }
            },
            "required": ["command"]
        }
    """.trimIndent()

    override suspend fun execute(context: Context, argumentsJson: String): ToolExecutionResult {
        val command = try {
            val json = JSONObject(argumentsJson)
            json.optString("command", "battery_status").lowercase()
        } catch (e: Exception) {
            "battery_status"
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        return when (command) {
            "battery_status" -> {
                val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
                    context.registerReceiver(null, filter)
                }
                val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL

                val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else 100
                val chargingText = if (isCharging) "and currently connected to power" else "discharging"
                ToolExecutionResult(
                    success = true,
                    message = "Battery at $batteryPct%, $chargingText.",
                    speechResponse = "Power reserves are at $batteryPct%, $chargingText, sir."
                )
            }
            "volume_up" -> {
                audioManager?.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_RAISE,
                    AudioManager.FLAG_SHOW_UI
                )
                ToolExecutionResult(
                    success = true,
                    message = "Volume increased.",
                    speechResponse = "Volume raised, sir."
                )
            }
            "volume_down" -> {
                audioManager?.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI
                )
                ToolExecutionResult(
                    success = true,
                    message = "Volume decreased.",
                    speechResponse = "Volume lowered, sir."
                )
            }
            "mute" -> {
                audioManager?.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_MUTE,
                    AudioManager.FLAG_SHOW_UI
                )
                ToolExecutionResult(
                    success = true,
                    message = "Volume muted.",
                    speechResponse = "Audio output muted, sir."
                )
            }
            "unmute" -> {
                audioManager?.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_UNMUTE,
                    AudioManager.FLAG_SHOW_UI
                )
                ToolExecutionResult(
                    success = true,
                    message = "Volume unmuted.",
                    speechResponse = "Audio unmuted, sir."
                )
            }
            "max_volume" -> {
                val max = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
                audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, max, AudioManager.FLAG_SHOW_UI)
                ToolExecutionResult(
                    success = true,
                    message = "Volume set to maximum.",
                    speechResponse = "Volume set to maximum, sir."
                )
            }
            else -> {
                ToolExecutionResult(
                    success = false,
                    message = "Unknown device command: $command",
                    speechResponse = "Sir, unknown device operation."
                )
            }
        }
    }
}
