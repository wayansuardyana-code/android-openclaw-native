package com.openclaw.android.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.openclaw.android.util.ServiceState
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * AccessibilityService that provides:
 * - Screen reading (get all visible UI elements as structured data)
 * - Tap/click any element by coordinates or text
 * - Swipe gestures
 * - Type text into any field
 * - Global actions (back, home, recents, notifications)
 * - Screenshot (API 30+)
 *
 * This is the "eyes and hands" of the AI agent on Android.
 */
class ScreenReaderService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        ServiceState.addLog("Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We primarily use on-demand reads rather than event-driven.
        // Events are available if needed for real-time monitoring.
    }

    override fun onInterrupt() {
        ServiceState.addLog("Accessibility service interrupted")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ── Screen Reading ──────────────────────────────────────

    /**
     * Dump the current screen's accessibility tree as JSON.
     * This gives the AI a structured view of everything on screen.
     */
    fun readScreen(): JsonObject {
        val root = rootInActiveWindow ?: return JsonObject().apply {
            addProperty("error", "No active window")
        }

        val result = JsonObject()
        result.addProperty("packageName", root.packageName?.toString() ?: "unknown")
        result.add("nodes", traverseNode(root, 0))
        root.recycle()
        return result
    }

    private fun traverseNode(node: AccessibilityNodeInfo, depth: Int): JsonArray {
        val nodes = JsonArray()
        val rect = Rect()
        node.getBoundsInScreen(rect)

        val obj = JsonObject().apply {
            addProperty("class", node.className?.toString() ?: "")
            addProperty("text", node.text?.toString() ?: "")
            addProperty("description", node.contentDescription?.toString() ?: "")
            addProperty("id", node.viewIdResourceName ?: "")
            addProperty("clickable", node.isClickable)
            addProperty("editable", node.isEditable)
            addProperty("checked", node.isChecked)
            addProperty("enabled", node.isEnabled)
            addProperty("focused", node.isFocused)
            addProperty("scrollable", node.isScrollable)
            add("bounds", JsonObject().apply {
                addProperty("left", rect.left)
                addProperty("top", rect.top)
                addProperty("right", rect.right)
                addProperty("bottom", rect.bottom)
            })
            addProperty("depth", depth)
        }

        // Skip empty non-interactive nodes to reduce noise
        val isRelevant = obj.get("text").asString.isNotEmpty() ||
            obj.get("description").asString.isNotEmpty() ||
            node.isClickable || node.isEditable || node.isScrollable

        if (isRelevant) {
            nodes.add(obj)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            nodes.addAll(traverseNode(child, depth + 1))
            child.recycle()
        }

        return nodes
    }

    // ── Tap / Click ─────────────────────────────────────────

    fun tap(x: Float, y: Float, callback: ((Boolean) -> Unit)? = null) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                callback?.invoke(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                callback?.invoke(false)
            }
        }, null)
    }

    // ── Swipe ───────────────────────────────────────────────

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300, callback: ((Boolean) -> Unit)? = null) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                callback?.invoke(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                callback?.invoke(false)
            }
        }, null)
    }

    // ── Type Text ───────────────────────────────────────────

    fun typeText(text: String): Boolean {
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val result = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        focused.recycle()
        return result
    }

    // ── Global Actions ──────────────────────────────────────

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun pressRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun openQuickSettings(): Boolean = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)

    fun lockScreen(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        } else false
    }

    fun takeScreenshot(callback: TakeScreenshotCallback) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY,
                mainExecutor, callback)
        }
    }

    companion object {
        var instance: ScreenReaderService? = null
            private set
    }
}
