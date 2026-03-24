package com.openclaw.android.ai

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.openclaw.android.service.NotificationReaderService
import com.openclaw.android.service.ScreenReaderService
import com.openclaw.android.util.ServiceState
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Android-native tools that the LLM can call.
 * Each tool maps to a bridge API capability but executes directly in-process.
 */
object AndroidTools {

    private val gson = Gson()

    fun getToolDefinitions(): List<ToolDef> = getAndroidTools() + UtilityTools.getToolDefinitions() + ServiceTools.getToolDefinitions() + PythonRuntime.getToolDefinitions()

    private fun getAndroidTools(): List<ToolDef> = listOf(
        ToolDef(
            name = "android_read_screen",
            description = "Read the current screen content. Returns a JSON accessibility tree with all visible UI elements, their text, descriptions, bounds, and interactive states.",
            inputSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        ),
        ToolDef(
            name = "android_tap",
            description = "Tap at specific screen coordinates (x, y). Use after reading the screen to tap buttons or elements.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "x" to mapOf("type" to "number", "description" to "X coordinate"),
                    "y" to mapOf("type" to "number", "description" to "Y coordinate")
                ),
                "required" to listOf("x", "y")
            )
        ),
        ToolDef(
            name = "android_swipe",
            description = "Swipe from (x1,y1) to (x2,y2). Use for scrolling or sliding.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "x1" to mapOf("type" to "number"), "y1" to mapOf("type" to "number"),
                    "x2" to mapOf("type" to "number"), "y2" to mapOf("type" to "number")
                ),
                "required" to listOf("x1", "y1", "x2", "y2")
            )
        ),
        ToolDef(
            name = "android_type_text",
            description = "Type text into the currently focused input field.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf("text" to mapOf("type" to "string", "description" to "Text to type")),
                "required" to listOf("text")
            )
        ),
        ToolDef(
            name = "android_press_back",
            description = "Press the Android back button.",
            inputSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        ),
        ToolDef(
            name = "android_press_home",
            description = "Press the Android home button to go to home screen.",
            inputSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        ),
        ToolDef(
            name = "android_open_app",
            description = "Open an app by its package name. Common packages: com.whatsapp, com.instagram.android, com.twitter.android, com.google.android.gm (Gmail), com.android.chrome.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf("packageName" to mapOf("type" to "string")),
                "required" to listOf("packageName")
            )
        ),
        ToolDef(
            name = "shizuku_command",
            description = "Run a shell command with ADB-level privileges via Shizuku. Can: install apps, change system settings, grant permissions, access hidden APIs. Requires Shizuku to be installed and running.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf("command" to mapOf("type" to "string", "description" to "Shell command to run with ADB privileges")),
                "required" to listOf("command")
            )
        ),
        ToolDef(
            name = "android_read_notifications",
            description = "Read all current notifications on the device. Returns app name, title, text for each notification.",
            inputSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        ),
    )

    suspend fun executeTool(name: String, args: JsonObject): String {
        ServiceState.addLog("Tool call: $name")
        return try {
            when (name) {
                "android_read_screen" -> {
                    val reader = ScreenReaderService.instance
                        ?: return """{"error":"Accessibility service not enabled"}"""
                    val tree = reader.readScreen().toString()
                    // Truncate to prevent LLM timeout on massive accessibility trees
                    if (tree.length > 8000) tree.take(8000) + "...(truncated, ${tree.length} total chars)" else tree
                }
                "android_tap" -> {
                    val reader = ScreenReaderService.instance
                        ?: return """{"error":"Accessibility service not enabled"}"""
                    val x = args.get("x").asFloat
                    val y = args.get("y").asFloat
                    val success = suspendCancellableCoroutine<Boolean> { cont ->
                        reader.tap(x, y) { result -> cont.resume(result) }
                    }
                    """{"success":$success,"x":$x,"y":$y}"""
                }
                "android_swipe" -> {
                    val reader = ScreenReaderService.instance
                        ?: return """{"error":"Accessibility service not enabled"}"""
                    val x1 = args.get("x1").asFloat
                    val y1 = args.get("y1").asFloat
                    val x2 = args.get("x2").asFloat
                    val y2 = args.get("y2").asFloat
                    val success = suspendCancellableCoroutine<Boolean> { cont ->
                        reader.swipe(x1, y1, x2, y2) { result -> cont.resume(result) }
                    }
                    """{"success":$success}"""
                }
                "android_type_text" -> {
                    val reader = ScreenReaderService.instance
                        ?: return """{"error":"Accessibility service not enabled"}"""
                    val text = args.get("text").asString
                    val success = reader.typeText(text)
                    """{"success":$success}"""
                }
                "android_press_back" -> {
                    val success = ScreenReaderService.instance?.pressBack() ?: false
                    """{"success":$success}"""
                }
                "android_press_home" -> {
                    val success = ScreenReaderService.instance?.pressHome() ?: false
                    """{"success":$success}"""
                }
                "android_open_app" -> {
                    val pkg = args.get("packageName").asString
                    val context = com.openclaw.android.OpenClawApplication.instance
                    val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                    if (intent != null) {
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        """{"success":true,"packageName":"$pkg"}"""
                    } else {
                        """{"error":"App not found: $pkg"}"""
                    }
                }
                "shizuku_command" -> {
                    val cmd = args.get("command").asString
                    val result = com.openclaw.android.util.ShizukuHelper.runShellCommand(cmd)
                    """{"output":${com.google.gson.Gson().toJson(result)}}"""
                }
                "android_read_notifications" -> {
                    val listener = NotificationReaderService.instance
                        ?: return """{"error":"Notification listener not enabled"}"""
                    listener.getActiveNotificationsJson().toString()
                }
                else -> {
                    val utilResult = UtilityTools.executeTool(name, args)
                    if (utilResult.contains("Unknown tool")) {
                        val svcResult = ServiceTools.executeTool(name, args)
                        if (svcResult.contains("Unknown service tool")) PythonRuntime.executeTool(name, args) else svcResult
                    } else utilResult
                }
            }
        } catch (e: Exception) {
            ServiceState.addLog("Tool error: $name — ${e.message}")
            """{"error":"${e.message?.replace("\"", "'")}"}"""
        }
    }
}
