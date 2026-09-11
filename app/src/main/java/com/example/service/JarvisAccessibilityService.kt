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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
     * Performs a global hardware button action (Back, Home, Recents, Notifications, Power Dialog, Screenshot).
     */
    fun performGlobal(action: String): Boolean {
        val globalAction = when (action.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "lock_screen" -> GLOBAL_ACTION_LOCK_SCREEN
            "screenshot" -> if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                GLOBAL_ACTION_TAKE_SCREENSHOT
            } else {
                GLOBAL_ACTION_BACK
            }
            else -> GLOBAL_ACTION_BACK
        }
        return performGlobalAction(globalAction)
    }

    /**
     * Takes a native system screenshot using AccessibilityService global action.
     */
    fun takeScreenshot(callback: ((Boolean) -> Unit)? = null): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val success = performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            callback?.invoke(success)
            success
        } else {
            callback?.invoke(false)
            false
        }
    }

    /**
     * Automatically finds and clicks the "Send" button in WhatsApp, SMS, or Telegram
     * when the user asks to send a message. Polls the active window for up to 4 seconds.
     */
    fun autoClickSendButton(maxAttempts: Int = 16, intervalMs: Long = 250L, onResult: ((Boolean) -> Unit)? = null) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            var clicked = false
            for (attempt in 1..maxAttempts) {
                kotlinx.coroutines.delay(intervalMs)
                try {
                    val root = rootInActiveWindow
                    if (root != null) {
                        clicked = findAndClickSendButtonNode(root)
                        root.recycle()
                        if (clicked) {
                            Log.i(TAG, "Successfully clicked Send button on attempt $attempt")
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error while scanning for Send button: ${e.message}")
                }
            }

            // Fallback gesture tap if accessibility node click was restricted by app security
            if (!clicked) {
                Log.d(TAG, "Attempting fallback coordinate gesture tap on Send button area...")
                val metrics: DisplayMetrics = resources.displayMetrics
                val width = metrics.widthPixels.toFloat()
                val height = metrics.heightPixels.toFloat()
                // Typical Send button location in WhatsApp/SMS is bottom right (92% width, 95% height)
                val sendTapPath = Path().apply {
                    moveTo(width * 0.92f, height * 0.94f)
                    lineTo(width * 0.92f, height * 0.94f)
                }
                val tapGesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(sendTapPath, 0, 50))
                    .build()
                dispatchGesture(tapGesture, null, null)
            }

            withContext(kotlinx.coroutines.Dispatchers.Main) {
                onResult?.invoke(clicked)
            }
        }
    }

    private fun findAndClickSendButtonNode(node: AccessibilityNodeInfo): Boolean {
        // 1. Check known view ID resource names for WhatsApp, Google Messages, Samsung Messages
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        if (viewId.contains("send") || viewId.contains("send_button") || viewId.contains("composer_send")) {
            if (performClickOnNodeOrParent(node)) return true
        }

        // 2. Check content description
        val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
        if (contentDesc == "send" || contentDesc.contains("send message") || contentDesc.contains("भेजें") || contentDesc.contains("send sms")) {
            if (performClickOnNodeOrParent(node)) return true
        }

        // 3. Check text
        val text = node.text?.toString()?.lowercase() ?: ""
        if (text == "send" || text == "भेजें") {
            if (performClickOnNodeOrParent(node)) return true
        }

        // 4. Recursively scan children
        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            if (findAndClickSendButtonNode(child)) {
                child.recycle()
                return true
            }
            child.recycle()
        }
        return false
    }

    private fun performClickOnNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable) {
                val result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                parent.recycle()
                return result
            }
            val grandParent = parent.parent
            parent.recycle()
            parent = grandParent
        }
        return false
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
