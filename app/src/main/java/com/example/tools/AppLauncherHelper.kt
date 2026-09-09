package com.example.tools

import android.app.ActivityOptions
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.R
import com.example.service.JarvisAccessibilityService

/**
 * Robust helper for launching applications, placing calls, and opening URLs
 * from a background Service even when the main app Activity is closed or removed from recents.
 *
 * Employs multiple Android-compliant escalation strategies:
 * 1. JarvisAccessibilityService (which has system privileges to start activities from background)
 * 2. PendingIntent with ActivityOptions background activity start allowance (Android 14+)
 * 3. Direct startActivity with FLAG_ACTIVITY_NEW_TASK
 * 4. High-priority heads-up full-screen notification trigger
 */
object AppLauncherHelper {
    private const val TAG = "AppLauncherHelper"
    private const val LAUNCH_CHANNEL_ID = "jarvis_task_launch_channel"
    private const val NOTIFICATION_ID = 2002

    fun launchIntent(context: Context, intent: Intent, taskTitle: String = "Action"): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

        // 1. If Accessibility Service is running, use it to launch intent without background restrictions
        val accessibilityService = JarvisAccessibilityService.instance
        if (accessibilityService != null) {
            try {
                Log.d(TAG, "Launching via JarvisAccessibilityService: $taskTitle")
                accessibilityService.startActivity(intent)
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Accessibility launch failed, falling back: ${e.message}")
            }
        }

        // 2. Try direct PendingIntent launch with background activity start allowed (Android 14+)
        val requestCode = (System.currentTimeMillis() % 10000).toInt()
        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val options = ActivityOptions.makeBasic()
                options.setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
                pendingIntent.send(context, 0, null, null, null, null, options.toBundle())
            } else {
                pendingIntent.send()
            }
            Log.d(TAG, "Launched via PendingIntent: $taskTitle")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "PendingIntent send failed: ${e.message}")
        }

        // 3. Direct context.startActivity
        return try {
            context.startActivity(intent)
            Log.d(TAG, "Launched via context.startActivity: $taskTitle")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Direct startActivity failed: ${e.message}")
            // 4. Fallback for background launch restrictions on Android 10+:
            // Post high-priority full-screen intent notification
            postLaunchNotification(context, pendingIntent, taskTitle)
            false
        }
    }

    private fun postLaunchNotification(context: Context, pendingIntent: PendingIntent, taskTitle: String) {
        try {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    LAUNCH_CHANNEL_ID,
                    "JARVIS Task Launcher",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Urgent task and app execution alerts"
                    setBypassDnd(true)
                }
                notificationManager.createNotificationChannel(channel)
            }

            val notification = NotificationCompat.Builder(context, LAUNCH_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Mark OS - $taskTitle")
                .setContentText("Opening $taskTitle now...")
                .setContentIntent(pendingIntent)
                .setFullScreenIntent(pendingIntent, true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post launch notification: ${e.message}")
        }
    }
}
