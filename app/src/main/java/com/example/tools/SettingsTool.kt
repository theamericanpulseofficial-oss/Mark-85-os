package com.example.tools

import android.content.Context
import android.content.Intent
import android.provider.Settings
import org.json.JSONObject

/**
 * Tool for opening device setting screens (Wi-Fi, Bluetooth, Display, Battery, Sound, etc.).
 */
class SettingsTool : PhoneTool {
    override val name: String = "open_settings"
    override val description: String =
        "Opens device settings pages (types: 'wifi', 'bluetooth', 'display', 'battery', 'sound', 'apps', 'all')."
    override val requiresConfirmation: Boolean = false

    override val parametersJson: String = """
        {
            "type": "object",
            "properties": {
                "settingType": {
                    "type": "string",
                    "enum": ["wifi", "bluetooth", "display", "battery", "sound", "apps", "all"],
                    "description": "The category of system settings to open"
                }
            },
            "required": ["settingType"]
        }
    """.trimIndent()

    override suspend fun execute(context: Context, argumentsJson: String): ToolExecutionResult {
        val settingType = try {
            val json = JSONObject(argumentsJson)
            json.optString("settingType", "all").lowercase()
        } catch (e: Exception) {
            "all"
        }

        val (action, friendlyName) = when (settingType) {
            "wifi" -> Pair(Settings.ACTION_WIFI_SETTINGS, "Wi-Fi settings")
            "bluetooth" -> Pair(Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth settings")
            "display" -> Pair(Settings.ACTION_DISPLAY_SETTINGS, "Display settings")
            "battery" -> Pair(Settings.ACTION_BATTERY_SAVER_SETTINGS, "Battery settings")
            "sound" -> Pair(Settings.ACTION_SOUND_SETTINGS, "Sound settings")
            "apps" -> Pair(Settings.ACTION_APPLICATION_SETTINGS, "Application settings")
            else -> Pair(Settings.ACTION_SETTINGS, "Device settings")
        }

        return try {
            val intent = Intent(action).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)

            ToolExecutionResult(
                success = true,
                message = "Opened $friendlyName",
                speechResponse = "Opening $friendlyName, sir."
            )
        } catch (e: Exception) {
            ToolExecutionResult(
                success = false,
                message = "Failed to open $friendlyName: ${e.message}",
                speechResponse = "Sir, I couldn't open $friendlyName."
            )
        }
    }
}
