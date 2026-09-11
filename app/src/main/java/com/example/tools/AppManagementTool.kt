package com.example.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import com.example.service.JarvisAccessibilityService
import org.json.JSONObject

/**
 * Tool for task switching, clearing recent apps, closing apps, uninstalling/deleting apps,
 * and navigating application settings.
 */
class AppManagementTool : PhoneTool {
    override val name: String = "app_management"
    override val description: String =
        "Manages apps and system tasks: opens recents screen ('recents'), clears all recent apps ('clear_all'), closes a running app ('close_app'), uninstalls/deletes an app ('uninstall_app'), or opens app storage/info settings ('app_settings')."
    override val requiresConfirmation: Boolean = false

    override val parametersJson: String = """
        {
            "type": "object",
            "properties": {
                "action": {
                    "type": "string",
                    "enum": ["recents", "clear_all", "close_app", "uninstall_app", "app_settings"],
                    "description": "The task management action to perform"
                },
                "appName": {
                    "type": "string",
                    "description": "Name of the target app (e.g. 'chrome', 'whatsapp', 'facebook', 'youtube'), required for close_app, uninstall_app, or app_settings"
                }
            },
            "required": ["action"]
        }
    """.trimIndent()

    override suspend fun execute(context: Context, argumentsJson: String): ToolExecutionResult {
        val json = try {
            JSONObject(argumentsJson)
        } catch (e: Exception) {
            JSONObject()
        }

        val action = json.optString("action", "recents").lowercase().trim()
        val appName = json.optString("appName", "").trim()

        val a11yService = JarvisAccessibilityService.instance

        return when (action) {
            "recents", "open_recents", "overview" -> {
                if (a11yService != null) {
                    a11yService.performGlobal("recents")
                    ToolExecutionResult(
                        success = true,
                        message = "Opened recent apps overview screen.",
                        speechResponse = "Opening recent apps, sir."
                    )
                } else {
                    JarvisAccessibilityService.openAccessibilitySettings(context)
                    ToolExecutionResult(
                        success = false,
                        message = "Accessibility permission required to access recents overview.",
                        speechResponse = "Sir, please enable Mark 85 OS in Accessibility Settings to control recent apps."
                    )
                }
            }

            "clear_all", "close_all", "clear_all_apps", "dismiss_all" -> {
                if (a11yService != null) {
                    a11yService.clearAllRecentApps()
                    ToolExecutionResult(
                        success = true,
                        message = "Cleared all recent applications.",
                        speechResponse = "Cleared all recent apps, sir."
                    )
                } else {
                    JarvisAccessibilityService.openAccessibilitySettings(context)
                    ToolExecutionResult(
                        success = false,
                        message = "Accessibility permission required to clear all recent apps.",
                        speechResponse = "Sir, please enable Mark 85 OS in Accessibility Settings to clear recent apps."
                    )
                }
            }

            "close_app", "clear_app" -> {
                val targetName = if (appName.isNotBlank()) appName else "the application"
                if (a11yService != null) {
                    a11yService.dismissAppFromRecents(targetName)
                    ToolExecutionResult(
                        success = true,
                        message = "Closed and dismissed $targetName.",
                        speechResponse = "Closed $targetName, sir."
                    )
                } else {
                    val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(homeIntent)
                    ToolExecutionResult(
                        success = true,
                        message = "Navigated to home screen to exit $targetName.",
                        speechResponse = "Closed $targetName, sir."
                    )
                }
            }

            "uninstall_app", "delete_app" -> {
                if (appName.isBlank()) {
                    ToolExecutionResult(
                        success = false,
                        message = "App name to uninstall was not specified.",
                        speechResponse = "Sir, which app would you like me to uninstall?"
                    )
                } else {
                    uninstallApp(context, appName)
                }
            }

            "app_settings", "clear_data", "app_info" -> {
                if (appName.isBlank()) {
                    ToolExecutionResult(
                        success = false,
                        message = "App name was not specified.",
                        speechResponse = "Sir, which app's settings would you like to open?"
                    )
                } else {
                    openAppSettings(context, appName)
                }
            }

            else -> {
                ToolExecutionResult(
                    success = false,
                    message = "Unknown app management action: $action",
                    speechResponse = "Sir, I didn't recognize that task action."
                )
            }
        }
    }

    private fun uninstallApp(context: Context, appName: String): ToolExecutionResult {
        val targetPackage = findPackageForAppName(context, appName)
        if (targetPackage == null) {
            return ToolExecutionResult(
                success = false,
                message = "Could not find app '$appName' installed on this device.",
                speechResponse = "Sir, I couldn't find an app named $appName installed on your device."
            )
        }

        val uninstallIntent = Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:$targetPackage")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(uninstallIntent)
            ToolExecutionResult(
                success = true,
                message = "Launched system uninstallation prompt for $appName ($targetPackage).",
                speechResponse = "Opening uninstallation prompt for $appName, sir."
            )
        } catch (e: Exception) {
            ToolExecutionResult(
                success = false,
                message = "Failed to launch uninstallation prompt: ${e.message}",
                speechResponse = "Sir, unable to trigger uninstallation for $appName."
            )
        }
    }

    private fun openAppSettings(context: Context, appName: String): ToolExecutionResult {
        val targetPackage = findPackageForAppName(context, appName)
        if (targetPackage == null) {
            return ToolExecutionResult(
                success = false,
                message = "Could not find app '$appName'.",
                speechResponse = "Sir, I couldn't find $appName on your device."
            )
        }

        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$targetPackage")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            ToolExecutionResult(
                success = true,
                message = "Opened application details and storage settings for $appName.",
                speechResponse = "Opening $appName settings for storage and clear data, sir."
            )
        } catch (e: Exception) {
            ToolExecutionResult(
                success = false,
                message = "Could not open app settings: ${e.message}",
                speechResponse = "Sir, unable to open settings for $appName."
            )
        }
    }

    companion object {
        fun findPackageForAppName(context: Context, appName: String): String? {
            val pm = context.packageManager
            val knownPackages = mapOf(
                "chrome" to "com.android.chrome",
                "google chrome" to "com.android.chrome",
                "browser" to "com.android.chrome",
                "whatsapp" to "com.whatsapp",
                "youtube" to "com.google.android.youtube",
                "youtube studio" to "com.google.android.apps.youtube.creator",
                "yt studio" to "com.google.android.apps.youtube.creator",
                "camera" to "com.android.camera",
                "maps" to "com.google.android.apps.maps",
                "spotify" to "com.spotify.music",
                "instagram" to "com.instagram.android",
                "settings" to "com.android.settings",
                "gmail" to "com.google.android.gm",
                "photos" to "com.google.android.apps.photos",
                "play store" to "com.android.vending",
                "calculator" to "com.google.android.calculator",
                "facebook" to "com.facebook.katana",
                "telegram" to "org.telegram.messenger"
            )
            val normalized = appName.trim().lowercase()
            knownPackages[normalized]?.let { return it }

            try {
                val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                for (app in installed) {
                    val label = pm.getApplicationLabel(app).toString().lowercase()
                    if (label == normalized || label.contains(normalized) || app.packageName.lowercase().contains(normalized)) {
                        return app.packageName
                    }
                }
            } catch (e: Exception) {
                // Ignore package manager query error
            }
            return null
        }
    }
}
