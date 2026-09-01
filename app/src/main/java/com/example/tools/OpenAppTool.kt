package com.example.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import org.json.JSONObject

/**
 * Tool for opening installed applications such as YouTube, YouTube Studio, Camera, Spotify, etc.
 */
class OpenAppTool : PhoneTool {
    override val name: String = "open_app"
    override val description: String =
        "Opens an installed Android app by name or package (e.g., 'YouTube', 'YouTube Studio', 'Camera', 'Spotify', 'Maps', 'Settings')."
    override val requiresConfirmation: Boolean = false

    override val parametersJson: String = """
        {
            "type": "object",
            "properties": {
                "appName": {
                    "type": "string",
                    "description": "Name or common title of the application to open, e.g. 'YouTube', 'YouTube Studio', 'Camera', 'WhatsApp'"
                }
            },
            "required": ["appName"]
        }
    """.trimIndent()

    override suspend fun execute(context: Context, argumentsJson: String): ToolExecutionResult {
        val appName = try {
            val json = JSONObject(argumentsJson)
            json.optString("appName", "")
        } catch (e: Exception) {
            ""
        }

        if (appName.isBlank()) {
            return ToolExecutionResult(
                success = false,
                message = "App name was not provided.",
                speechResponse = "Sir, which app would you like me to open?"
            )
        }

        val pm = context.packageManager

        // Known package mapping for quick accurate launches
        val knownPackages = mapOf(
            "youtube" to "com.google.android.youtube",
            "youtube studio" to "com.google.android.apps.youtube.creator",
            "yt studio" to "com.google.android.apps.youtube.creator",
            "camera" to "com.android.camera",
            "chrome" to "com.android.chrome",
            "browser" to "com.android.chrome",
            "maps" to "com.google.android.apps.maps",
            "spotify" to "com.spotify.music",
            "whatsapp" to "com.whatsapp",
            "settings" to "com.android.settings",
            "gmail" to "com.google.android.gm",
            "photos" to "com.google.android.apps.photos",
            "play store" to "com.android.vending",
            "calculator" to "com.google.android.calculator"
        )

        val normalizedQuery = appName.trim().lowercase()
        var targetPackage = knownPackages[normalizedQuery]

        // If not in known list or if not installed, search installed apps
        if (targetPackage == null || !isPackageInstalled(pm, targetPackage)) {
            val installedPackages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (appInfo in installedPackages) {
                val label = pm.getApplicationLabel(appInfo).toString().lowercase()
                if (label == normalizedQuery || label.contains(normalizedQuery)) {
                    targetPackage = appInfo.packageName
                    break
                }
            }
        }

        if (targetPackage == null) {
            return ToolExecutionResult(
                success = false,
                message = "Could not find app '$appName' installed on this device.",
                speechResponse = "Sir, I couldn't find $appName installed on your device."
            )
        }

        val launchIntent = pm.getLaunchIntentForPackage(targetPackage)
        return if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            ToolExecutionResult(
                success = true,
                message = "Successfully opened $appName ($targetPackage).",
                speechResponse = "Opening $appName now, sir."
            )
        } else {
            ToolExecutionResult(
                success = false,
                message = "App $appName cannot be launched directly.",
                speechResponse = "Sir, I was unable to launch $appName."
            )
        }
    }

    private fun isPackageInstalled(pm: PackageManager, packageName: String): Boolean {
        return try {
            pm.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }
}
