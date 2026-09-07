package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Android Accessibility Service for hands-free system automation:
 * - Scrolling screens up/down/left/right in any foreground app (WhatsApp, YouTube, Instagram, Browser, etc.)
 * - Tapping UI elements, sending messages, pressing Home, Back, Recent Apps, Notifications.
 */
class JarvisAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "JarvisAccessibilityService connected and operational.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Can track active window/app if needed
    }

    override fun onInterrupt() {
        Log.w(TAG, "JarvisAccessibilityService interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        Log.i(TAG, "JarvisAccessibilityService destroyed.")
    }

    /**
     * Performs a vertical or horizontal scroll gesture on the current screen.
     * direction: "down" (scroll down / swipe up), "up" (scroll up / swipe down), "left", "right"
     */
    fun performScroll(direction: String, callback: ((Boolean) -> Unit)? = null): Boolean {
        val metrics: DisplayMetrics = resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()

        val startX: Float
        val startY: Float
        val endX: Float
        val endY: Float

        when (direction.lowercase()) {
            "down" -> {
                // Scroll down: finger moves from 75% down to 25% up
                startX = width / 2f
                startY = height * 0.75f
                endX = width / 2f
                endY = height * 0.25f
            }
            "up" -> {
                // Scroll up: finger moves from 25% up to 75% down
                startX = width / 2f
                startY = height * 0.25f
                endX = width / 2f
                endY = height * 0.75f
            }
            "left" -> {
                startX = width * 0.85f
                startY = height / 2f
                endX = width * 0.15f
                endY = height / 2f
            }
            "right" -> {
                startX = width * 0.15f
                startY = height / 2f
                endX = width * 0.85f
                endY = height / 2f
            }
            else -> {
                startX = width / 2f
                startY = height * 0.75f
                endX = width / 2f
                endY = height * 0.25f
            }
        }

        val swipePath = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(swipePath, 0, 350))
            .build()

        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d(TAG, "Scroll gesture completed: $direction")
                callback?.invoke(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.w(TAG, "Scroll gesture cancelled: $direction")
                callback?.invoke(false)
            }
        }, null)
    }

    /**
     * Performs a global hardware button action (Back, Home, Recents, Notifications, Power Dialog).
     */
    fun performGlobal(action: String): Boolean {
        val globalAction = when (action.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "lock_screen" -> GLOBAL_ACTION_LOCK_SCREEN
            else -> GLOBAL_ACTION_BACK
        }
        return performGlobalAction(globalAction)
    }

    companion object {
        private const val TAG = "JarvisA11yService"

        @Volatile
        var instance: JarvisAccessibilityService? = null
            private set

        fun isServiceRunning(): Boolean = instance != null

        fun openAccessibilitySettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
