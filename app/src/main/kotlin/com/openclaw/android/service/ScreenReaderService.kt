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
 * AccessibilityService — optimized for token efficiency.
 *
 * Key optimizations:
 * - Compact JSON: short keys (t=text, d=desc, b=bounds, c=clickable)
 * - Skip empty non-interactive nodes
 * - Max depth limit (skip deeply nested containers)
 * - Max nodes limit (stop after N useful nodes)
 * - find_element: search by text/desc without full tree dump
 * - Region-based reading: only read nodes in a screen region
 */
class ScreenReaderService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        ServiceState.addLog("Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ── COMPACT Screen Reading ──────────────────────────

    /**
     * Read screen with compact JSON format.
     * Each node: {t:"text", d:"desc", c:1, e:1, b:[l,t,r,b]}
     * Saves ~70% tokens vs verbose format.
     */
    fun readScreen(): JsonObject {
        val root = rootInActiveWindow ?: return JsonObject().apply {
            addProperty("error", "No active window")
        }

        val result = JsonObject()
        result.addProperty("pkg", root.packageName?.toString() ?: "?")
        val nodes = JsonArray()
        traverseCompact(root, 0, nodes, maxNodes = 60, maxDepth = 12)
        result.add("ui", nodes)
        result.addProperty("count", nodes.size())
        root.recycle()
        return result
    }

    private fun traverseCompact(node: AccessibilityNodeInfo, depth: Int, out: JsonArray, maxNodes: Int, maxDepth: Int) {
        if (out.size() >= maxNodes || depth > maxDepth) return

        val rect = Rect()
        node.getBoundsInScreen(rect)
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val id = node.viewIdResourceName?.substringAfterLast("/") ?: ""

        // Only include nodes that have useful info
        val hasText = text.isNotBlank()
        val hasDesc = desc.isNotBlank()
        val isInteractive = node.isClickable || node.isEditable || node.isScrollable
        val isVisible = rect.width() > 0 && rect.height() > 0

        if (isVisible && (hasText || hasDesc || isInteractive)) {
            val obj = JsonObject()
            if (hasText) obj.addProperty("t", text.take(80))  // truncate long text
            if (hasDesc) obj.addProperty("d", desc.take(80))
            if (id.isNotBlank()) obj.addProperty("id", id)
            if (node.isClickable) obj.addProperty("c", 1)     // clickable
            if (node.isEditable) obj.addProperty("e", 1)      // editable
            if (node.isScrollable) obj.addProperty("s", 1)    // scrollable
            // Compact bounds: [left, top, right, bottom]
            val b = JsonArray()
            b.add(rect.left); b.add(rect.top); b.add(rect.right); b.add(rect.bottom)
            obj.add("b", b)
            out.add(obj)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseCompact(child, depth + 1, out, maxNodes, maxDepth)
            child.recycle()
        }
    }

    // ── Find Element by Text ────────────────────────────

    /**
     * Find elements matching text/description. Returns only matches.
     * Much cheaper than full readScreen — ~50 tokens vs ~2000.
     */
    fun findElement(query: String): JsonArray {
        val root = rootInActiveWindow ?: return JsonArray()
        val results = JsonArray()
        searchNode(root, query.lowercase(), results, maxResults = 10)
        root.recycle()
        return results
    }

    private fun searchNode(node: AccessibilityNodeInfo, query: String, out: JsonArray, maxResults: Int) {
        if (out.size() >= maxResults) return

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val id = node.viewIdResourceName ?: ""

        if (text.lowercase().contains(query) || desc.lowercase().contains(query) || id.lowercase().contains(query)) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val obj = JsonObject()
            if (text.isNotBlank()) obj.addProperty("t", text.take(100))
            if (desc.isNotBlank()) obj.addProperty("d", desc.take(100))
            obj.addProperty("c", if (node.isClickable) 1 else 0)
            // Tap point: center of bounds
            obj.addProperty("x", (rect.left + rect.right) / 2)
            obj.addProperty("y", (rect.top + rect.bottom) / 2)
            out.add(obj)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            searchNode(child, query, out, maxResults)
            child.recycle()
        }
    }

    // ── Read Region Only ────────────────────────────────

    /**
     * Read only nodes within a screen region. Saves tokens for partial reads.
     */
    fun readRegion(left: Int, top: Int, right: Int, bottom: Int): JsonArray {
        val root = rootInActiveWindow ?: return JsonArray()
        val region = Rect(left, top, right, bottom)
        val nodes = JsonArray()
        readNodeInRegion(root, region, nodes, maxNodes = 30)
        root.recycle()
        return nodes
    }

    private fun readNodeInRegion(node: AccessibilityNodeInfo, region: Rect, out: JsonArray, maxNodes: Int) {
        if (out.size() >= maxNodes) return
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!Rect.intersects(rect, region)) return

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        if (text.isNotBlank() || desc.isNotBlank() || node.isClickable) {
            val obj = JsonObject()
            if (text.isNotBlank()) obj.addProperty("t", text.take(80))
            if (desc.isNotBlank()) obj.addProperty("d", desc.take(80))
            if (node.isClickable) obj.addProperty("c", 1)
            obj.addProperty("x", (rect.left + rect.right) / 2)
            obj.addProperty("y", (rect.top + rect.bottom) / 2)
            out.add(obj)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            readNodeInRegion(child, region, out, maxNodes)
            child.recycle()
        }
    }

    // ── Tap / Click ─────────────────────────────────────

    fun tap(x: Float, y: Float, callback: ((Boolean) -> Unit)? = null) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { callback?.invoke(true) }
            override fun onCancelled(g: GestureDescription?) { callback?.invoke(false) }
        }, null)
    }

    // ── Swipe ───────────────────────────────────────────

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300, callback: ((Boolean) -> Unit)? = null) {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { callback?.invoke(true) }
            override fun onCancelled(g: GestureDescription?) { callback?.invoke(false) }
        }, null)
    }

    // ── Type Text ───────────────────────────────────────

    fun typeText(text: String): Boolean {
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val result = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        focused.recycle()
        return result
    }

    // ── Global Actions ──────────────────────────────────

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun pressRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    fun lockScreen(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        } else false
    }

    fun takeScreenshot(callback: TakeScreenshotCallback) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, callback)
        }
    }

    /**
     * Capture screenshot and save to file. Returns JSON result string.
     * Wraps the callback-based API into a suspend function.
     */
    /**
     * Capture screenshot and save to PNG file. Returns JSON result.
     * Uses AccessibilityService API (Android 11+).
     */
    suspend fun captureScreenshot(outputPath: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return """{"error":"Screenshot requires Android 11+"}"""
        }
        return captureScreenshotApi30(outputPath)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private suspend fun captureScreenshotApi30(outputPath: String): String {
        return kotlin.coroutines.suspendCoroutine { cont ->
            val cb = ScreenshotCallback(outputPath, cont)
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, cb)
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private class ScreenshotCallback(
        private val outputPath: String,
        private val cont: kotlin.coroutines.Continuation<String>
    ) : AccessibilityService.TakeScreenshotCallback {
        override fun onSuccess(result: ScreenshotResult) {
            try {
                val bitmap = android.graphics.Bitmap.wrapHardwareBuffer(
                    result.hardwareBuffer, result.colorSpace
                )
                if (bitmap != null) {
                    val softBitmap = bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                    val file = java.io.File(outputPath)
                    java.io.FileOutputStream(file).use { out ->
                        softBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, out)
                    }
                    softBitmap.recycle()
                    bitmap.recycle()
                    result.hardwareBuffer.close()
                    cont.resumeWith(Result.success(
                        """{"success":true,"path":"$outputPath","size":${file.length()}}"""
                    ))
                } else {
                    cont.resumeWith(Result.success("""{"error":"Failed to create bitmap"}"""))
                }
            } catch (e: Exception) {
                cont.resumeWith(Result.success(
                    """{"error":"Screenshot save failed: ${e.message?.replace("\"", "'")}"}"""
                ))
            }
        }
        override fun onFailure(errorCode: Int) {
            cont.resumeWith(Result.success("""{"error":"Screenshot failed with code $errorCode"}"""))
        }
    }

    companion object {
        @Volatile var instance: ScreenReaderService? = null
            private set
    }
}
