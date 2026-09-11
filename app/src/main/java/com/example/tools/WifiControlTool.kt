package com.example.tools

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import org.json.JSONObject

/**
 * Tool for toggling or controlling device Wi-Fi state.
 * Supports "on", "off", "toggle", and "status".
 */
class WifiControlTool : PhoneTool {
    override val name: String = "control_wifi"
    override val description: String =
        "Turns Wi-Fi on or off, toggles Wi-Fi state, or checks Wi-Fi connection (actions: 'on', 'off', 'toggle', 'status')."
    override val requiresConfirmation: Boolean = false

    override val parametersJson: String = """
        {
            "type": "object",
            "properties": {
                "action": {
                    "type": "string",
                    "enum": ["on", "off", "toggle", "status"],
                    "description": "Action to perform: 'on', 'off', 'toggle', or 'status'"
                }
            },
            "required": ["action"]
        }
    """.trimIndent()

    override suspend fun execute(context: Context, argumentsJson: String): ToolExecutionResult {
        val action = try {
            val json = JSONObject(argumentsJson)
            json.optString("action", "toggle").lowercase()
        } catch (e: Exception) {
            "toggle"
        }

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val isCurrentlyEnabled = wifiManager?.isWifiEnabled == true

        when (action) {
            "status" -> {
                val statusText = if (isCurrentlyEnabled) "connected or enabled" else "disabled"
                return ToolExecutionResult(
                    success = true,
                    message = "Wi-Fi is currently $statusText.",
                    speechResponse = "Wi-Fi is currently $statusText, sir."
                )
            }

            "on" -> {
                if (isCurrentlyEnabled) {
                    return ToolExecutionResult(
                        success = true,
                        message = "Wi-Fi is already enabled.",
                        speechResponse = "Wi-Fi is already enabled, sir."
                    )
                }
                return applyWifiState(context, wifiManager, targetEnabled = true)
            }

            "off" -> {
                if (!isCurrentlyEnabled) {
                    return ToolExecutionResult(
                        success = true,
                        message = "Wi-Fi is already turned off.",
                        speechResponse = "Wi-Fi is already turned off, sir."
                    )
                }
                return applyWifiState(context, wifiManager, targetEnabled = false)
            }

            "toggle" -> {
                return applyWifiState(context, wifiManager, targetEnabled = !isCurrentlyEnabled)
            }

            else -> {
                return ToolExecutionResult(
                    success = false,
                    message = "Unknown Wi-Fi command: $action",
                    speechResponse = "Sir, unknown Wi-Fi command."
                )
            }
        }
    }

    private fun applyWifiState(context: Context, wifiManager: WifiManager?, targetEnabled: Boolean): ToolExecutionResult {
        // On Android 9 (API 28) and below, direct hardware control is supported
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            val changed = wifiManager?.setWifiEnabled(targetEnabled) ?: false
            val verb = if (targetEnabled) "enabled" else "disabled"
            return if (changed) {
                ToolExecutionResult(
                    success = true,
                    message = "Wi-Fi $verb.",
                    speechResponse = "Wi-Fi $verb, sir."
                )
            } else {
                ToolExecutionResult(
                    success = false,
                    message = "Failed to switch Wi-Fi.",
                    speechResponse = "Sir, could not change Wi-Fi state."
                )
            }
        } else {
            // Android 10+ (API 29+): Launch the native rapid-toggle Wi-Fi sheet or settings
            val launched = try {
                val panelIntent = Intent(Settings.Panel.ACTION_WIFI).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(panelIntent)
                true
            } catch (e: Exception) {
                try {
                    val settingsIntent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(settingsIntent)
                    true
                } catch (_: Exception) {
                    false
                }
            }

            val targetVerb = if (targetEnabled) "turn on" else "turn off"
            return if (launched) {
                ToolExecutionResult(
                    success = true,
                    message = "Opened Wi-Fi panel to $targetVerb Wi-Fi.",
                    speechResponse = "Opening Wi-Fi control panel, sir."
                )
            } else {
                ToolExecutionResult(
                    success = false,
                    message = "Unable to open Wi-Fi settings.",
                    speechResponse = "Sir, unable to access Wi-Fi controls."
                )
            }
        }
    }
}
