package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.service.JarvisForegroundService
import com.example.settings.JarvisSettings

/**
 * BroadcastReceiver triggered on system boot, package updates, or quickboot.
 * Ensures the Jarvis Foreground Service is automatically restored in the background
 * so voice wake-word and task execution function without needing to manually launch the app.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "Received broadcast action: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            val settings = JarvisSettings.load(context)
            if (settings.wakeWordEnabled) {
                Log.i(TAG, "Starting JarvisForegroundService on device boot...")
                val serviceIntent = Intent(context, JarvisForegroundService::class.java).apply {
                    this.action = JarvisForegroundService.ACTION_START
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start service on boot: ${e.message}")
                }
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
