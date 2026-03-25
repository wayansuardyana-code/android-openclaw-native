package com.openclaw.android.ai

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.openclaw.android.service.NotificationReaderService
import com.openclaw.android.service.ScreenReaderService
import com.openclaw.android.util.ServiceState
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
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
            description = "Read the current screen (max 60 nodes, compact format). Use find_element instead when you know what to look for. Each node: t=text, d=desc, c=clickable, e=editable, s=scrollable, b=[left,top,right,bottom]. Tap point = center of bounds.",
            inputSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        ),
        ToolDef(
            name = "find_element",
            description = "Find a specific UI element by text, description, or ID. MUCH cheaper than read_screen (~50 tokens vs ~2000). Returns matching elements with tap coordinates (x, y). Use this FIRST before read_screen.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf("query" to mapOf("type" to "string", "description" to "Text, description, or ID to search for (case-insensitive)")),
                "required" to listOf("query")
            )
        ),
        ToolDef(
            name = "read_region",
            description = "Read only UI elements in a specific screen region. Cheaper than full read_screen. Useful after knowing the layout.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "left" to mapOf("type" to "number"), "top" to mapOf("type" to "number"),
                    "right" to mapOf("type" to "number"), "bottom" to mapOf("type" to "number")
                ),
                "required" to listOf("left", "top", "right", "bottom")
            )
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
            name = "android_press_enter",
            description = "Press Enter/Search/Go key on the soft keyboard. Use after typing in a search box to submit the query. Also works as IME action (search, send, go, done).",
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
            name = "android_media_control",
            description = "Control media playback (YouTube, Spotify, music players, etc.) without needing to find UI buttons. Actions: play, pause, next, previous, stop. Much more reliable than trying to tap play/pause buttons.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "action" to mapOf("type" to "string", "description" to "One of: play, pause, next, previous, stop",
                        "enum" to listOf("play", "pause", "next", "previous", "stop"))
                ),
                "required" to listOf("action")
            )
        ),
        ToolDef(
            name = "android_volume",
            description = "Control device volume. Set absolute level (0-15) or adjust relative (up/down). Streams: music (media/YouTube), ring (ringtone), alarm, notification.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "action" to mapOf("type" to "string", "description" to "One of: set, up, down, mute, unmute",
                        "enum" to listOf("set", "up", "down", "mute", "unmute")),
                    "level" to mapOf("type" to "number", "description" to "Volume level 0-15 (only for 'set' action)"),
                    "stream" to mapOf("type" to "string", "description" to "Audio stream: music (default), ring, alarm, notification",
                        "enum" to listOf("music", "ring", "alarm", "notification"))
                ),
                "required" to listOf("action")
            )
        ),
        ToolDef(
            name = "android_read_notifications",
            description = "Read all current notifications on the device. Returns app name, title, text for each notification.",
            inputSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        ),
        ToolDef(
            name = "analyze_screenshot",
            description = "Take a screenshot and send it to a vision model (Gemini) to describe what's on screen. Returns a detailed text description of the UI — buttons, images, text, layout. MUCH better than read_screen for complex UIs, image-heavy apps, or when accessibility tree is incomplete. Requires a Gemini API key configured in Settings → Google/Gemini.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "question" to mapOf("type" to "string", "description" to "Optional: specific question about the screen, e.g. 'what hotels are shown?' or 'where is the search button?'")
                )
            )
        ),
        ToolDef(
            name = "take_screenshot",
            description = "Take a screenshot of the current screen and save it as a PNG file. Returns the file path. Requires Android 11+ (API 30) and Accessibility Service enabled. Use with send_telegram_photo to send screenshots to the user.",
            inputSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        ),
        ToolDef(
            name = "android_long_press",
            description = "Long press at specific screen coordinates (x, y) for 600ms. Use for: context menus, copy/paste handles, drag initiation, app icon options, selecting text.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "x" to mapOf("type" to "number", "description" to "X coordinate"),
                    "y" to mapOf("type" to "number", "description" to "Y coordinate"),
                    "duration_ms" to mapOf("type" to "number", "description" to "Hold duration in milliseconds (default 600, minimum 500)")
                ),
                "required" to listOf("x", "y")
            )
        ),
        ToolDef(
            name = "android_scroll_to_text",
            description = "Scroll the screen until a specific text element is visible. Tries up to 10 swipe-up gestures. Returns coordinates when found, or error if not found. Use when you know an element exists but it's off-screen.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "text" to mapOf("type" to "string", "description" to "Text to search for (case-insensitive)")
                ),
                "required" to listOf("text")
            )
        ),
        ToolDef(
            name = "android_set_brightness",
            description = "Set screen brightness level (0-255) or toggle auto-brightness. Requires WRITE_SETTINGS permission (grant via Shizuku if needed).",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "level" to mapOf("type" to "number", "description" to "Brightness level 0-255 (ignored when action is auto_on/auto_off)"),
                    "action" to mapOf("type" to "string", "description" to "One of: set (default), auto_on, auto_off",
                        "enum" to listOf("set", "auto_on", "auto_off"))
                )
            )
        ),
        ToolDef(
            name = "android_get_clipboard",
            description = "Read the current text content of the device clipboard. Returns the clipboard text or empty string if nothing is copied.",
            inputSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        ),
        ToolDef(
            name = "android_set_clipboard",
            description = "Write text to the device clipboard. Use before pasting into apps.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "text" to mapOf("type" to "string", "description" to "Text to copy to clipboard")
                ),
                "required" to listOf("text")
            )
        ),
        ToolDef(
            name = "android_wifi_toggle",
            description = "Open the WiFi settings panel (Android 10+ cannot toggle WiFi programmatically — opens the system WiFi panel instead). User can toggle from there, or use shizuku_command 'svc wifi enable/disable' for programmatic control.",
            inputSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        ),
        ToolDef(
            name = "android_launch_url",
            description = "Open a URL in the default browser. More reliable than opening Chrome manually and navigating. Supports http, https, and deep-link URLs.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "url" to mapOf("type" to "string", "description" to "URL to open (must include scheme, e.g. https://)")
                ),
                "required" to listOf("url")
            )
        ),
    )

    suspend fun executeTool(name: String, args: JsonObject): String {
        ServiceState.addLog("Tool call: $name")
        return try {
            when (name) {
                "android_read_screen" -> {
                    val reader = ScreenReaderService.instance
                        ?: return """{"error":"Accessibility service not enabled"}"""
                    reader.readScreen().toString()
                }
                "find_element" -> {
                    val reader = ScreenReaderService.instance
                        ?: return """{"error":"Accessibility service not enabled"}"""
                    val query = args.get("query")?.asString ?: ""
                    val results = reader.findElement(query)
                    """{"matches":${results.size()},"elements":$results}"""
                }
                "read_region" -> {
                    val reader = ScreenReaderService.instance
                        ?: return """{"error":"Accessibility service not enabled"}"""
                    val l = args.get("left")?.asInt ?: 0
                    val t = args.get("top")?.asInt ?: 0
                    val r = args.get("right")?.asInt ?: 1080
                    val b = args.get("bottom")?.asInt ?: 2400
                    val nodes = reader.readRegion(l, t, r, b)
                    """{"region":[$l,$t,$r,$b],"nodes":$nodes}"""
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
                "android_press_enter" -> {
                    val reader = ScreenReaderService.instance
                        ?: return """{"error":"Accessibility service not enabled"}"""
                    val success = reader.pressEnter()
                    """{"success":$success}"""
                }
                "android_media_control" -> {
                    val action = args.get("action")?.asString ?: "pause"
                    val context = com.openclaw.android.OpenClawApplication.instance
                    val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                    val keyCode = when (action) {
                        "play" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY
                        "pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
                        "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
                        "previous" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
                        "stop" -> android.view.KeyEvent.KEYCODE_MEDIA_STOP
                        else -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    }
                    val downEvent = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode)
                    val upEvent = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode)
                    audioManager.dispatchMediaKeyEvent(downEvent)
                    audioManager.dispatchMediaKeyEvent(upEvent)
                    """{"success":true,"action":"$action"}"""
                }
                "android_volume" -> {
                    val action = args.get("action")?.asString ?: "up"
                    val streamName = args.get("stream")?.asString ?: "music"
                    val context = com.openclaw.android.OpenClawApplication.instance
                    val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                    val stream = when (streamName) {
                        "ring" -> android.media.AudioManager.STREAM_RING
                        "alarm" -> android.media.AudioManager.STREAM_ALARM
                        "notification" -> android.media.AudioManager.STREAM_NOTIFICATION
                        else -> android.media.AudioManager.STREAM_MUSIC
                    }
                    val maxVol = audioManager.getStreamMaxVolume(stream)
                    val currentVol = audioManager.getStreamVolume(stream)
                    when (action) {
                        "set" -> {
                            val level = args.get("level")?.asInt?.coerceIn(0, maxVol) ?: currentVol
                            audioManager.setStreamVolume(stream, level, 0)
                        }
                        "up" -> audioManager.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_RAISE, 0)
                        "down" -> audioManager.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_LOWER, 0)
                        "mute" -> audioManager.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_MUTE, 0)
                        "unmute" -> audioManager.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_UNMUTE, 0)
                    }
                    val newVol = audioManager.getStreamVolume(stream)
                    """{"success":true,"stream":"$streamName","volume":$newVol,"max":$maxVol}"""
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
                "analyze_screenshot" -> {
                    // Phase 9: Vision model analysis of screenshot
                    // Step 1: Take screenshot
                    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
                        return """{"error":"Screenshot requires Android 11+"}"""
                    }
                    val reader = ScreenReaderService.instance
                        ?: return """{"error":"Accessibility service not enabled"}"""
                    val context = com.openclaw.android.OpenClawApplication.instance
                    val dir = java.io.File(context.filesDir, "screenshots")
                    dir.mkdirs()
                    val file = java.io.File(dir, "vision_${System.currentTimeMillis()}.png")
                    val ssResult = reader.captureScreenshot(file.absolutePath)

                    if (!file.exists() || file.length() == 0L) {
                        return """{"error":"Screenshot failed: $ssResult"}"""
                    }

                    // Step 2: Send to Gemini Vision API
                    val geminiKey = com.openclaw.android.ai.AgentConfig.getKeyForProvider("gemini")
                        .ifBlank { com.openclaw.android.ai.AgentConfig.getKeyForProvider("google") }
                    if (geminiKey.isBlank()) {
                        // Fallback: return screenshot path + accessibility tree instead
                        val tree = reader.readScreen().toString().take(3000)
                        return """{"fallback":true,"note":"No Gemini API key — returning screenshot path + accessibility tree. Add Gemini key in Settings for vision analysis.","screenshot":"${file.absolutePath}","accessibility_tree":${com.google.gson.Gson().toJson(tree)}}"""
                    }

                    // Encode screenshot as base64
                    val imageBytes = file.readBytes()
                    val base64Image = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)
                    val question = args.get("question")?.asString ?: "Describe everything you see on this Android screen in detail. List all visible UI elements, buttons, text, images, and their approximate positions (top/middle/bottom, left/center/right)."

                    // Call Gemini Vision API
                    val requestBody = com.google.gson.JsonObject().apply {
                        val contents = com.google.gson.JsonArray()
                        val content = com.google.gson.JsonObject().apply {
                            val parts = com.google.gson.JsonArray()
                            parts.add(com.google.gson.JsonObject().apply { addProperty("text", question) })
                            parts.add(com.google.gson.JsonObject().apply {
                                add("inline_data", com.google.gson.JsonObject().apply {
                                    addProperty("mime_type", "image/png")
                                    addProperty("data", base64Image)
                                })
                            })
                            add("parts", parts)
                        }
                        contents.add(content)
                        add("contents", contents)
                    }

                    try {
                        val client = io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp) {
                            engine { config { readTimeout(30, java.util.concurrent.TimeUnit.SECONDS) } }
                        }
                        val resp = client.post("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$geminiKey") {
                            contentType(ContentType.Application.Json)
                            setBody(requestBody.toString())
                        }
                        client.close()
                        val respText = resp.bodyAsText()
                        val respJson = com.google.gson.JsonParser.parseString(respText).asJsonObject
                        val text = respJson.getAsJsonArray("candidates")
                            ?.get(0)?.asJsonObject
                            ?.getAsJsonObject("content")
                            ?.getAsJsonArray("parts")
                            ?.get(0)?.asJsonObject
                            ?.get("text")?.asString ?: "Vision analysis failed"

                        ServiceState.addLog("Vision: analyzed screenshot (${text.length} chars)")
                        """{"success":true,"description":${com.google.gson.Gson().toJson(text)},"screenshot":"${file.absolutePath}"}"""
                    } catch (e: Exception) {
                        ServiceState.addLog("Vision error: ${e.message?.take(80)}")
                        """{"error":"Vision API failed: ${e.message?.take(100)?.replace("\"", "'")}","screenshot":"${file.absolutePath}"}"""
                    }
                }
                "take_screenshot" -> {
                    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
                        return """{"error":"Screenshot requires Android 11+"}"""
                    }
                    val reader = ScreenReaderService.instance
                        ?: return """{"error":"Accessibility service not enabled"}"""
                    val context = com.openclaw.android.OpenClawApplication.instance
                    val dir = java.io.File(context.filesDir, "screenshots")
                    dir.mkdirs()
                    val file = java.io.File(dir, "screenshot_${System.currentTimeMillis()}.png")

                    reader.captureScreenshot(file.absolutePath)
                }
                "android_long_press" -> {
                    val reader = ScreenReaderService.instance
                        ?: return """{"error":"Accessibility service not enabled"}"""
                    val x = args.get("x").asFloat
                    val y = args.get("y").asFloat
                    val duration = args.get("duration_ms")?.asLong?.coerceAtLeast(500L) ?: 600L
                    val success = suspendCancellableCoroutine<Boolean> { cont ->
                        reader.longPress(x, y, duration) { result -> cont.resume(result) }
                    }
                    """{"success":$success,"x":$x,"y":$y,"duration_ms":$duration}"""
                }
                "android_scroll_to_text" -> {
                    val reader = ScreenReaderService.instance
                        ?: return """{"error":"Accessibility service not enabled"}"""
                    val query = args.get("text")?.asString ?: ""
                    var found = false
                    var resultX = 0
                    var resultY = 0
                    for (attempt in 0 until 10) {
                        val matches = reader.findElement(query)
                        if (matches.size() > 0) {
                            val first = matches.get(0).asJsonObject
                            resultX = first.get("x")?.asInt ?: 0
                            resultY = first.get("y")?.asInt ?: 0
                            found = true
                            break
                        }
                        // Swipe up to scroll down (finger moves from bottom to top)
                        suspendCancellableCoroutine<Boolean> { cont ->
                            reader.swipe(540f, 1600f, 540f, 600f, 300) { result -> cont.resume(result) }
                        }
                        kotlinx.coroutines.delay(400)
                    }
                    if (found) {
                        """{"found":true,"text":"${query.replace("\"","'")}","x":$resultX,"y":$resultY}"""
                    } else {
                        """{"found":false,"error":"Text not found after 10 scroll attempts: ${query.replace("\"","'")}"}"""
                    }
                }
                "android_set_brightness" -> {
                    val context = com.openclaw.android.OpenClawApplication.instance
                    val action = args.get("action")?.asString ?: "set"
                    try {
                        when (action) {
                            "auto_on" -> {
                                android.provider.Settings.System.putInt(
                                    context.contentResolver,
                                    android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                                    android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                                )
                                """{"success":true,"action":"auto_on"}"""
                            }
                            "auto_off" -> {
                                android.provider.Settings.System.putInt(
                                    context.contentResolver,
                                    android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                                    android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                                )
                                """{"success":true,"action":"auto_off"}"""
                            }
                            else -> {
                                val level = args.get("level")?.asInt?.coerceIn(0, 255) ?: 128
                                // Disable auto-brightness first so manual level takes effect
                                android.provider.Settings.System.putInt(
                                    context.contentResolver,
                                    android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                                    android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                                )
                                android.provider.Settings.System.putInt(
                                    context.contentResolver,
                                    android.provider.Settings.System.SCREEN_BRIGHTNESS,
                                    level
                                )
                                """{"success":true,"action":"set","level":$level}"""
                            }
                        }
                    } catch (se: SecurityException) {
                        """{"error":"WRITE_SETTINGS permission denied. Grant via: shizuku_command 'pm grant com.openclaw.android android.permission.WRITE_SETTINGS'"}"""
                    }
                }
                "android_get_clipboard" -> {
                    val context = com.openclaw.android.OpenClawApplication.instance
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: ""
                    """{"text":${com.google.gson.Gson().toJson(text)}}"""
                }
                "android_set_clipboard" -> {
                    val context = com.openclaw.android.OpenClawApplication.instance
                    val text = args.get("text")?.asString ?: ""
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("openclaw", text)
                    cm.setPrimaryClip(clip)
                    """{"success":true,"length":${text.length}}"""
                }
                "android_wifi_toggle" -> {
                    val context = com.openclaw.android.OpenClawApplication.instance
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        val intent = android.content.Intent(android.provider.Settings.Panel.ACTION_WIFI)
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        """{"success":true,"note":"Opened WiFi settings panel (Android 10+ restriction). Use shizuku_command 'svc wifi enable' or 'svc wifi disable' for direct control."}"""
                    } else {
                        // Pre-Android 10: toggle via WifiManager
                        @Suppress("DEPRECATION")
                        val wm = context.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                        @Suppress("DEPRECATION")
                        val current = wm.isWifiEnabled
                        @Suppress("DEPRECATION")
                        wm.isWifiEnabled = !current
                        """{"success":true,"wifi_enabled":${!current}}"""
                    }
                }
                "android_launch_url" -> {
                    val context = com.openclaw.android.OpenClawApplication.instance
                    val url = args.get("url")?.asString ?: ""
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    """{"success":true,"url":${com.google.gson.Gson().toJson(url)}}"""
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
