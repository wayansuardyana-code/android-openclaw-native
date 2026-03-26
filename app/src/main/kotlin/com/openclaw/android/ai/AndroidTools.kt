package com.openclaw.android.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.view.accessibility.AccessibilityNodeInfo
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
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

/**
 * Android-native tools that the LLM can call.
 * Each tool maps to a bridge API capability but executes directly in-process.
 */
object AndroidTools {

    private val gson = Gson()

    /** Last SoM analysis results, used by tap_som_element */
    @Volatile
    private var lastSomElements: List<SomElement> = emptyList()

    fun getToolDefinitions(): List<ToolDef> = getAndroidTools() + UtilityTools.getToolDefinitions() + ServiceTools.getToolDefinitions() + PythonRuntime.getToolDefinitions() + NodeRuntime.getToolDefinitions() + LinuxEnvironment.getToolDefinitions()

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
            description = "Open an app by package name OR app name. If exact package fails, searches installed apps by name. Examples: 'com.whatsapp' or 'pluang' or 'shopee'. Returns suggestions if no exact match. Common: com.whatsapp, com.instagram.android, com.google.android.gm, com.android.chrome.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf("packageName" to mapOf("type" to "string", "description" to "Package name (com.example.app) or app name (e.g. 'Pluang', 'Shopee')")),
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
            name = "analyze_screen_with_som",
            description = "Take a screenshot with numbered labels on every interactive element (Set-of-Mark). Returns the annotated image path and a mapping of numbers to elements. Agent can then say 'tap_som_element(id=3)' to tap element 3. Better than read_screen for visual UI navigation.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "send_to_vision" to mapOf("type" to "boolean", "description" to "Also send to Gemini Vision for description (default false)")
                )
            )
        ),
        ToolDef(
            name = "tap_som_element",
            description = "Tap an element by its SoM number from analyze_screen_with_som. Example: tap_som_element(id=3) taps element #3.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "id" to mapOf("type" to "number", "description" to "Element number from SoM overlay")
                ),
                "required" to listOf("id")
            )
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
        ToolDef(
            name = "look_and_find",
            description = "YOUR PRIMARY EYES — Take screenshot, ask Gemini Vision to locate a specific element, returns tap coordinates. Use this FIRST instead of read_screen/find_element. Works on ALL apps including Flutter/React Native. Example: look_and_find('search bar') → {x:540, y:190}. Then tap(540, 190).",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "target" to mapOf("type" to "string", "description" to "What to find: 'search bar', 'login button', 'Crypto tab', 'the red X button', etc."),
                    "context" to mapOf("type" to "string", "description" to "Optional: extra context like 'at the bottom of the screen' or 'in the navigation bar'")
                ),
                "required" to listOf("target")
            )
        ),
        ToolDef(
            name = "tap_element",
            description = "ALL-IN-ONE: Find element by description → tap it → verify tap worked. Does look_and_find + tap + verify in ONE call. Use this as your PRIMARY interaction tool. Example: tap_element('search bar') → finds it, taps it, confirms it opened. Much faster than calling look_and_find then android_tap separately.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "target" to mapOf("type" to "string", "description" to "What to tap: 'search bar', 'Buy button', 'Crypto tab', etc."),
                    "verify" to mapOf("type" to "string", "description" to "Optional: what to expect after tap (e.g. 'search input focused', 'product page')")
                ),
                "required" to listOf("target")
            )
        ),
        ToolDef(
            name = "type_and_submit",
            description = "ALL-IN-ONE: Find input field → tap it → type text → press enter. Does look_and_find + tap + type + enter in ONE call. Example: type_and_submit('flashdisk 16GB', 'search bar')",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "text" to mapOf("type" to "string", "description" to "Text to type"),
                    "field" to mapOf("type" to "string", "description" to "Optional: input field to find first (e.g. 'search bar'). If omitted, types into current focus.")
                ),
                "required" to listOf("text")
            )
        ),
        ToolDef(
            name = "look_and_describe",
            description = "Take screenshot and describe everything visible on screen. Returns full text description with element positions. Use when you need to understand the current screen layout before acting.",
            inputSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        ),
        ToolDef(
            name = "scroll_down",
            description = "Scroll down to see more content below. Equivalent to swiping up on the screen.",
            inputSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        ),
        ToolDef(
            name = "scroll_up",
            description = "Scroll up to see content above. Equivalent to swiping down on the screen.",
            inputSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        ),
        ToolDef(
            name = "swipe_left",
            description = "Swipe left — navigate to next tab, next page, dismiss panel, or next carousel item.",
            inputSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        ),
        ToolDef(
            name = "swipe_right",
            description = "Swipe right — navigate to previous tab, previous page, or open side drawer.",
            inputSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        ),
        ToolDef(
            name = "pull_to_refresh",
            description = "Pull to refresh — swipe down from top of content area to trigger refresh in lists/feeds.",
            inputSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        ),
        ToolDef(
            name = "explore_app",
            description = "Explore an unfamiliar app to learn its UI layout BEFORE attempting tasks. Opens the app, reads each screen, maps interactive elements and navigation patterns. Saves a knowledge map to notes. Use BEFORE interacting with an app you haven't used before. Returns a JSON map of the app's screens, buttons, navigation, and key elements.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "package_name" to mapOf("type" to "string", "description" to "App package name to explore (e.g. com.shopee.id)"),
                    "max_screens" to mapOf("type" to "number", "description" to "Max screens to explore (default 5, max 10)"),
                    "goal" to mapOf("type" to "string", "description" to "Optional: what you want to learn (e.g. 'find search bar', 'locate settings')")
                ),
                "required" to listOf("package_name")
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
                    val screenResult = reader.readScreen().toString()
                    // If 0 elements, hint that this app needs SoM/vision
                    if (screenResult.contains("\"count\":0") || screenResult.contains("\"count\": 0")) {
                        val pkg = try { reader.rootInActiveWindow?.packageName?.toString() ?: "unknown" } catch (_: Exception) { "unknown" }
                        """$screenResult
NOTE: This app ($pkg) has NO accessibility elements — it may use Flutter/React Native/WebView. Use analyze_screen_with_som or analyze_screenshot instead of read_screen. For PIN keypads, use analyze_screen_with_som then tap_som_element on each digit."""
                    } else screenResult
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
                    val x = args.get("x")?.asFloat ?: return """{"error":"Missing required parameter 'x'"}"""
                    val y = args.get("y")?.asFloat ?: return """{"error":"Missing required parameter 'y'"}"""
                    val success = suspendCancellableCoroutine<Boolean> { cont ->
                        reader.tap(x, y) { result -> cont.resume(result) }
                    }
                    """{"success":$success,"x":$x,"y":$y}"""
                }
                "android_swipe" -> {
                    val reader = ScreenReaderService.instance
                        ?: return """{"error":"Accessibility service not enabled"}"""
                    val x1 = args.get("x1")?.asFloat ?: return """{"error":"Missing required parameter 'x1'"}"""
                    val y1 = args.get("y1")?.asFloat ?: return """{"error":"Missing required parameter 'y1'"}"""
                    val x2 = args.get("x2")?.asFloat ?: return """{"error":"Missing required parameter 'x2'"}"""
                    val y2 = args.get("y2")?.asFloat ?: return """{"error":"Missing required parameter 'y2'"}"""
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
                // ── ALL-IN-ONE TOOLS (eyes + hands combined) ──
                "tap_element" -> {
                    val target = args.get("target")?.asString ?: return """{"error":"Missing 'target'"}"""
                    val verifyTarget = args.get("verify")?.asString

                    // Step 1: Find the element using look_and_find
                    val findArgs = JsonObject().apply { addProperty("target", target) }
                    val findResult = executeTool("look_and_find", findArgs)
                    ServiceState.addLog("tap_element: find → ${findResult.take(100)}")

                    val findJson = try { com.google.gson.JsonParser.parseString(findResult).asJsonObject } catch (_: Exception) {
                        return """{"success":false,"error":"Could not find '$target'","find_result":${gson.toJson(findResult.take(200))}}"""
                    }

                    val found = findJson.get("found")?.asBoolean ?: false
                    if (!found) {
                        val desc = findJson.get("description")?.asString ?: "Element not found"
                        return """{"success":false,"target":${gson.toJson(target)},"error":"Not found: $desc"}"""
                    }

                    val x = findJson.get("x")?.asFloat ?: return """{"success":false,"error":"No x coordinate"}"""
                    val y = findJson.get("y")?.asFloat ?: return """{"success":false,"error":"No y coordinate"}"""

                    // Step 2: Tap it
                    val reader = ScreenReaderService.instance ?: return """{"error":"Accessibility not enabled"}"""
                    val tapSuccess = suspendCancellableCoroutine<Boolean> { cont ->
                        reader.tap(x, y) { result -> cont.resume(result) }
                    }
                    ServiceState.addLog("tap_element: tapped ($x, $y) → $tapSuccess")

                    // Step 3: Brief delay for UI to respond
                    delay(500)

                    // Step 4: Verify (optional — if verify target provided, check if it appeared)
                    var verified = true
                    var verifyDesc = ""
                    if (verifyTarget != null) {
                        val verifyArgs = JsonObject().apply { addProperty("target", verifyTarget) }
                        val verifyResult = executeTool("look_and_find", verifyArgs)
                        val verifyJson = try { com.google.gson.JsonParser.parseString(verifyResult).asJsonObject } catch (_: Exception) { null }
                        verified = verifyJson?.get("found")?.asBoolean ?: false
                        verifyDesc = verifyJson?.get("description")?.asString ?: ""
                    }

                    """{"success":true,"target":${gson.toJson(target)},"x":$x,"y":$y,"tapped":$tapSuccess,"verified":$verified${if (verifyDesc.isNotBlank()) ",\"verify_desc\":${gson.toJson(verifyDesc)}" else ""}}"""
                }

                "type_and_submit" -> {
                    val text = args.get("text")?.asString ?: return """{"error":"Missing 'text'"}"""
                    val field = args.get("field")?.asString

                    val reader = ScreenReaderService.instance ?: return """{"error":"Accessibility not enabled"}"""

                    // Step 1: Find and tap the input field (if specified)
                    if (field != null) {
                        val findArgs = JsonObject().apply { addProperty("target", field) }
                        val findResult = executeTool("look_and_find", findArgs)
                        val findJson = try { com.google.gson.JsonParser.parseString(findResult).asJsonObject } catch (_: Exception) { null }
                        val found = findJson?.get("found")?.asBoolean ?: false
                        if (found) {
                            val x = findJson?.get("x")?.asFloat ?: 540f
                            val y = findJson?.get("y")?.asFloat ?: 190f
                            suspendCancellableCoroutine<Boolean> { cont ->
                                reader.tap(x, y) { result -> cont.resume(result) }
                            }
                            delay(300)
                        }
                    }

                    // Step 2: Type the text
                    val typeSuccess = reader.typeText(text)
                    delay(200)

                    // Step 3: Press enter
                    val enterSuccess = reader.pressEnter()

                    ServiceState.addLog("type_and_submit: '$text' → type=$typeSuccess, enter=$enterSuccess")
                    """{"success":true,"text":${gson.toJson(text)},"typed":$typeSuccess,"submitted":$enterSuccess}"""
                }

                // ── VISION-FIRST TOOLS (the "eyes") ──
                "look_and_find" -> {
                    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
                        return """{"error":"Requires Android 11+"}"""
                    }
                    val reader = ScreenReaderService.instance
                        ?: return """{"error":"Accessibility service not enabled"}"""
                    val target = args.get("target")?.asString ?: return """{"error":"Missing 'target' parameter"}"""
                    val extraContext = args.get("context")?.asString ?: ""

                    // Take screenshot
                    val context = com.openclaw.android.OpenClawApplication.instance
                    val dir = java.io.File(context.filesDir, "screenshots")
                    dir.mkdirs()
                    val file = java.io.File(dir, "look_${System.currentTimeMillis()}.png")
                    reader.captureScreenshot(file.absolutePath)
                    if (!file.exists() || file.length() == 0L) return """{"error":"Screenshot failed"}"""

                    val geminiKey = AgentConfig.getKeyForProvider("gemini").ifBlank { AgentConfig.getKeyForProvider("google") }
                    if (geminiKey.isBlank()) {
                        // Fallback: try accessibility tree
                        val elements = reader.findElement(target)
                        if (elements.size() > 0) {
                            val first = elements.get(0).asJsonObject
                            val x = first.get("x")?.asInt
                            val y = first.get("y")?.asInt
                            val warning = if (x == null || y == null) ""","warning":"coordinates estimated, may be inaccurate"""" else ""
                            return """{"found":true,"method":"accessibility","target":${gson.toJson(target)},"x":${x ?: 540},"y":${y ?: 960}$warning}"""
                        }
                        return """{"found":false,"error":"No Gemini API key and element not found in accessibility tree. Add Gemini key in Settings for vision-based search."}"""
                    }

                    // Get real screen dimensions
                    val dm = com.openclaw.android.OpenClawApplication.instance.resources.displayMetrics
                    val screenW = dm.widthPixels
                    val screenH = dm.heightPixels

                    // Send to Gemini Vision with coordinate extraction prompt
                    val imageBytes = file.readBytes()
                    val base64Image = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)

                    val prompt = """You are a screen coordinate finder for an Android phone (${screenW}x${screenH} resolution).
Look at this screenshot and find: "$target"${if (extraContext.isNotBlank()) " ($extraContext)" else ""}

RESPOND WITH ONLY THIS JSON FORMAT (no other text):
{"found":true,"x":540,"y":190,"description":"search bar at top center"}

If not found:
{"found":false,"description":"what you see instead"}

Rules:
- x is horizontal (0=left, ${screenW}=right)
- y is vertical (0=top, ${screenH}=bottom)
- Return the CENTER point of the element
- Be precise — coordinates must be tappable"""

                    val requestBody = com.google.gson.JsonObject().apply {
                        val contents = com.google.gson.JsonArray()
                        contents.add(com.google.gson.JsonObject().apply {
                            val parts = com.google.gson.JsonArray()
                            parts.add(com.google.gson.JsonObject().apply { addProperty("text", prompt) })
                            parts.add(com.google.gson.JsonObject().apply {
                                add("inline_data", com.google.gson.JsonObject().apply {
                                    addProperty("mime_type", "image/png")
                                    addProperty("data", base64Image)
                                })
                            })
                            add("parts", parts)
                        })
                        add("contents", contents)
                        add("generationConfig", com.google.gson.JsonObject().apply {
                            addProperty("temperature", 0.1)
                            addProperty("maxOutputTokens", 1024)
                            addProperty("responseMimeType", "application/json")
                        })
                    }

                    // Retry up to 2 times on timeout
                    var respText = ""
                    var lastError = ""
                    for (attempt in 1..2) {
                        val client = io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp) {
                            engine { config { readTimeout(30, java.util.concurrent.TimeUnit.SECONDS) } }
                        }
                        try {
                            val resp = client.post("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$geminiKey") {
                                contentType(ContentType.Application.Json)
                                setBody(requestBody.toString())
                            }
                            respText = resp.bodyAsText()
                            client.close()
                            break // Success
                        } catch (e: Exception) {
                            client.close()
                            lastError = e.message?.take(80) ?: "timeout"
                            ServiceState.addLog("look_and_find: attempt $attempt failed: $lastError")
                            if (attempt < 2) delay(1000) // Brief delay before retry
                        }
                    }
                    if (respText.isBlank()) {
                        return """{"found":false,"error":"Vision API failed after 2 attempts: $lastError"}"""
                    }

                    try {
                        val respJson = try { com.google.gson.JsonParser.parseString(respText).asJsonObject } catch (_: Exception) {
                            return """{"found":false,"error":"Vision API returned invalid response"}"""
                        }
                        if (respJson.has("error")) {
                            val errMsg = respJson.getAsJsonObject("error")?.get("message")?.asString ?: "Unknown"
                            return """{"found":false,"error":"Gemini: ${errMsg.take(150).replace("\"", "'")}"}"""
                        }

                        val text = respJson.getAsJsonArray("candidates")
                            ?.get(0)?.asJsonObject?.getAsJsonObject("content")
                            ?.getAsJsonArray("parts")?.get(0)?.asJsonObject
                            ?.get("text")?.asString ?: ""

                        ServiceState.addLog("look_and_find: target='$target' → ${text.take(200)}")

                        // Strategy 1: Try full JSON parse
                        val jsonMatch = Regex("\\{.*\\}", RegexOption.DOT_MATCHES_ALL).find(text)
                        if (jsonMatch != null) {
                            try {
                                val result = com.google.gson.JsonParser.parseString(jsonMatch.value).asJsonObject
                                if (result.has("x") && result.has("y")) {
                                    result.addProperty("method", "vision")
                                    result.addProperty("target", target)
                                    return result.toString()
                                }
                            } catch (_: Exception) {}
                        }

                        // Strategy 2: Extract x,y from partial/truncated JSON using regex
                        val xMatch = Regex("\"x\"\\s*:\\s*(\\d+)").find(text)
                        val yMatch = Regex("\"y\"\\s*:\\s*(\\d+)").find(text)
                        if (xMatch != null && yMatch != null) {
                            val x = xMatch.groupValues[1].toInt()
                            val y = yMatch.groupValues[1].toInt()
                            val desc = Regex("\"description\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: ""
                            ServiceState.addLog("look_and_find: extracted from partial JSON → x=$x, y=$y")
                            return """{"found":true,"x":$x,"y":$y,"description":${gson.toJson(desc)},"method":"vision","target":${gson.toJson(target)}}"""
                        }

                        // Strategy 3: Extract just x (y might be truncated)
                        if (xMatch != null) {
                            val x = xMatch.groupValues[1].toInt()
                            ServiceState.addLog("look_and_find: only x found ($x), y missing — retrying recommended")
                            return """{"found":true,"x":$x,"y":${screenH / 2},"warning":"y coordinate estimated (response truncated)","method":"vision","target":${gson.toJson(target)}}"""
                        }

                        // Strategy 4: Check if Gemini said "not found"
                        if (text.contains("not found", ignoreCase = true) || text.contains("\"found\":false") || text.contains("\"found\": false")) {
                            val desc = Regex("\"description\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: text.take(200)
                            return """{"found":false,"description":${gson.toJson(desc)},"method":"vision","target":${gson.toJson(target)}}"""
                        }

                        """{"found":false,"raw_response":${gson.toJson(text.take(300))},"error":"Could not parse coordinates from vision response"}"""
                    } catch (e: Exception) {
                        ServiceState.addLog("look_and_find error: ${e.message?.take(80)}")
                        """{"found":false,"error":"Vision API failed: ${e.message?.take(100)?.replace("\"", "'")}"}"""
                    } finally {
                        try { file.delete() } catch (_: Exception) {}  // Cleanup screenshot
                    }
                }

                "look_and_describe" -> {
                    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
                        return """{"error":"Requires Android 11+"}"""
                    }
                    val reader = ScreenReaderService.instance
                        ?: return """{"error":"Accessibility service not enabled"}"""
                    val context = com.openclaw.android.OpenClawApplication.instance
                    val dir = java.io.File(context.filesDir, "screenshots")
                    dir.mkdirs()
                    val file = java.io.File(dir, "describe_${System.currentTimeMillis()}.png")
                    reader.captureScreenshot(file.absolutePath)
                    if (!file.exists() || file.length() == 0L) return """{"error":"Screenshot failed"}"""

                    val geminiKey = AgentConfig.getKeyForProvider("gemini").ifBlank { AgentConfig.getKeyForProvider("google") }
                    if (geminiKey.isBlank()) {
                        val tree = reader.readScreen().toString().take(3000)
                        return """{"method":"accessibility","note":"No Gemini key — showing accessibility tree instead","screen":$tree}"""
                    }

                    val imageBytes = file.readBytes()
                    val base64Image = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)

                    val dm2 = com.openclaw.android.OpenClawApplication.instance.resources.displayMetrics
                    val prompt = """Describe this Android screen in detail for a blind AI agent that needs to interact with it.
Screen resolution: ${dm2.widthPixels}x${dm2.heightPixels}
For EACH interactive element (buttons, tabs, inputs, links, icons), provide:
- What it is (button, tab, input field, icon, text link)
- Its label/text
- Tap coordinates (x, y) where 0,0=top-left, ${dm2.widthPixels},${dm2.heightPixels}=bottom-right
- Whether it looks tappable

Format as a structured list. Be precise with coordinates — they will be used for tapping."""

                    val requestBody = com.google.gson.JsonObject().apply {
                        val contents = com.google.gson.JsonArray()
                        contents.add(com.google.gson.JsonObject().apply {
                            val parts = com.google.gson.JsonArray()
                            parts.add(com.google.gson.JsonObject().apply { addProperty("text", prompt) })
                            parts.add(com.google.gson.JsonObject().apply {
                                add("inline_data", com.google.gson.JsonObject().apply {
                                    addProperty("mime_type", "image/png")
                                    addProperty("data", base64Image)
                                })
                            })
                            add("parts", parts)
                        })
                        add("contents", contents)
                    }

                    val client = io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp) {
                        engine { config { readTimeout(60, java.util.concurrent.TimeUnit.SECONDS) } }
                    }
                    try {
                        val resp = client.post("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$geminiKey") {
                            contentType(ContentType.Application.Json)
                            setBody(requestBody.toString())
                        }
                        val respText = resp.bodyAsText()
                        val respJson = try { com.google.gson.JsonParser.parseString(respText).asJsonObject } catch (_: Exception) {
                            return """{"error":"Vision API returned invalid response: ${respText.take(100).replace("\"", "'")}"}"""
                        }
                        if (respJson.has("error")) {
                            val errMsg = respJson.getAsJsonObject("error")?.get("message")?.asString ?: "Unknown"
                            return """{"error":"Gemini: ${errMsg.take(200).replace("\"", "'")}"}"""
                        }
                        val text = respJson.getAsJsonArray("candidates")
                            ?.get(0)?.asJsonObject?.getAsJsonObject("content")
                            ?.getAsJsonArray("parts")?.get(0)?.asJsonObject
                            ?.get("text")?.asString ?: "No description returned"

                        ServiceState.addLog("look_and_describe: ${text.length} chars")
                        """{"method":"vision","description":${gson.toJson(text)},"screenshot":"${file.absolutePath}"}"""
                    } catch (e: Exception) {
                        """{"error":"Vision failed: ${e.message?.take(100)?.replace("\"", "'")}"}"""
                    } finally {
                        client.close()
                        try { file.delete() } catch (_: Exception) {}  // Cleanup screenshot
                    }
                }

                // ── CONVENIENCE GESTURES ──
                "scroll_down" -> {
                    val reader = ScreenReaderService.instance ?: return """{"error":"Accessibility service not enabled"}"""
                    val success = suspendCancellableCoroutine<Boolean> { cont ->
                        reader.swipe(540f, 1600f, 540f, 600f, 300) { result -> cont.resume(result) }
                    }
                    """{"success":$success,"gesture":"scroll_down"}"""
                }
                "scroll_up" -> {
                    val reader = ScreenReaderService.instance ?: return """{"error":"Accessibility service not enabled"}"""
                    val success = suspendCancellableCoroutine<Boolean> { cont ->
                        reader.swipe(540f, 600f, 540f, 1600f, 300) { result -> cont.resume(result) }
                    }
                    """{"success":$success,"gesture":"scroll_up"}"""
                }
                "swipe_left" -> {
                    val reader = ScreenReaderService.instance ?: return """{"error":"Accessibility service not enabled"}"""
                    val success = suspendCancellableCoroutine<Boolean> { cont ->
                        reader.swipe(900f, 1200f, 180f, 1200f, 250) { result -> cont.resume(result) }
                    }
                    """{"success":$success,"gesture":"swipe_left"}"""
                }
                "swipe_right" -> {
                    val reader = ScreenReaderService.instance ?: return """{"error":"Accessibility service not enabled"}"""
                    val success = suspendCancellableCoroutine<Boolean> { cont ->
                        reader.swipe(180f, 1200f, 900f, 1200f, 250) { result -> cont.resume(result) }
                    }
                    """{"success":$success,"gesture":"swipe_right"}"""
                }
                "pull_to_refresh" -> {
                    val reader = ScreenReaderService.instance ?: return """{"error":"Accessibility service not enabled"}"""
                    val success = suspendCancellableCoroutine<Boolean> { cont ->
                        reader.swipe(540f, 400f, 540f, 1400f, 400) { result -> cont.resume(result) }
                    }
                    """{"success":$success,"gesture":"pull_to_refresh"}"""
                }

                "android_open_app" -> {
                    val pkg = args.get("packageName").asString
                    val context = com.openclaw.android.OpenClawApplication.instance
                    val pm = context.packageManager

                    // Try exact package name first
                    var intent = pm.getLaunchIntentForPackage(pkg)

                    // If not found, search installed apps by label name
                    var resolvedPkg = pkg
                    if (intent == null) {
                        ServiceState.addLog("App not found by package: $pkg — searching by name...")
                        // Skip generic words that match everything
                        val genericWords = setOf("com", "app", "android", "mobile", "id", "org", "net", "io", "co", "main", "debug", "the", "my")
                        val searchWords = pkg.lowercase()
                            .split(".", " ", "_", "-")
                            .filter { it.length > 2 && it !in genericWords }
                        ServiceState.addLog("Search words: $searchWords")
                        val installed = pm.getInstalledApplications(0)
                        val match = installed.firstOrNull { appInfo ->
                            val label = pm.getApplicationLabel(appInfo).toString().lowercase()
                            val pkgLower = appInfo.packageName.lowercase()
                            // Match: any meaningful search word in label OR package name
                            searchWords.any { word -> label.contains(word) || pkgLower.contains(word) }
                        }
                        if (match != null) {
                            resolvedPkg = match.packageName
                            intent = pm.getLaunchIntentForPackage(resolvedPkg)
                            ServiceState.addLog("Found app by name: ${pm.getApplicationLabel(match)} ($resolvedPkg)")
                        }
                    }

                    // If still not found, list similar apps
                    if (intent == null) {
                        val allApps = pm.getInstalledApplications(0)
                        val suggestWords = pkg.lowercase().split(".", " ", "_", "-")
                            .filter { it.length > 2 && it !in setOf("com", "app", "android", "mobile", "id", "org", "net", "io", "co", "main", "debug", "the", "my") }
                        val suggestions = allApps.filter { appInfo ->
                            val label = pm.getApplicationLabel(appInfo).toString().lowercase()
                            val pkgLower = appInfo.packageName.lowercase()
                            suggestWords.any { word: String -> label.contains(word) || pkgLower.contains(word) }
                        }.take(5).map { appInfo ->
                            val label = pm.getApplicationLabel(appInfo).toString()
                            """{"name":"$label","package":"${appInfo.packageName}"}"""
                        }
                        if (suggestions.isNotEmpty()) {
                            return """{"error":"App not found: $pkg","hint":"Did you mean one of these?","suggestions":[${suggestions.joinToString(",")}]}"""
                        }
                        return """{"error":"App not found: $pkg. Use run_shell_command('pm list packages -3') to list all installed apps."}"""
                    }

                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    val label = try { pm.getApplicationLabel(pm.getApplicationInfo(resolvedPkg, 0)).toString() } catch (_: Exception) { resolvedPkg }
                    """{"success":true,"packageName":"$resolvedPkg","appName":"$label"}"""
                }
                "shizuku_command" -> {
                    val cmd = args.get("command").asString
                    val lowerCmd = cmd.lowercase()
                    val blocklist = listOf("rm -rf /", "rm -r /", "mkfs", "dd if=", "shared_prefs", "databases/", "curl ", "wget ", "nc ", "pm uninstall", "pm disable", "pm clear")
                    if (blocklist.any { lowerCmd.contains(it) }) {
                        return """{"error":"Command blocked for security"}"""
                    }
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

                    val visionClient = io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp) {
                        engine { config { readTimeout(60, java.util.concurrent.TimeUnit.SECONDS) } }
                    }
                    try {
                        val resp = visionClient.post("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$geminiKey") {
                            contentType(ContentType.Application.Json)
                            setBody(requestBody.toString())
                        }
                        val respText = resp.bodyAsText()
                        ServiceState.addLog("Vision API response: ${respText.take(200)}")
                        val respJson = try {
                            com.google.gson.JsonParser.parseString(respText).asJsonObject
                        } catch (e: Exception) {
                            return """{"error":"Vision API returned invalid JSON: ${respText.take(150).replace("\"", "'")}"}"""
                        }

                        // Check for error response
                        if (respJson.has("error")) {
                            val errMsg = respJson.getAsJsonObject("error")?.get("message")?.asString ?: "Unknown error"
                            return """{"error":"Gemini Vision error: ${errMsg.take(200).replace("\"", "'")}"}"""
                        }

                        val text = respJson.getAsJsonArray("candidates")
                            ?.get(0)?.asJsonObject
                            ?.getAsJsonObject("content")
                            ?.getAsJsonArray("parts")
                            ?.get(0)?.asJsonObject
                            ?.get("text")?.asString ?: "Vision analysis returned no text — response: ${respText.take(200)}"

                        ServiceState.addLog("Vision: analyzed screenshot (${text.length} chars)")
                        """{"success":true,"description":${com.google.gson.Gson().toJson(text)},"screenshot":"${file.absolutePath}"}"""
                    } catch (e: Exception) {
                        ServiceState.addLog("Vision error: ${e.message?.take(80)}")
                        """{"error":"Vision API failed: ${e.message?.take(100)?.replace("\"", "'")}","screenshot":"${file.absolutePath}"}"""
                    } finally {
                        visionClient.close()
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
                "analyze_screen_with_som" -> {
                    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
                        return """{"error":"Screenshot requires Android 11+"}"""
                    }
                    val service = ScreenReaderService.instance
                        ?: return """{"error":"Accessibility service not running"}"""

                    // Step 0: Clean up old SoM screenshots
                    val context = com.openclaw.android.OpenClawApplication.instance
                    val dir = File(context.filesDir, "screenshots")
                    dir.mkdirs()
                    dir.listFiles()?.filter { it.name.startsWith("som_") }?.forEach { it.delete() }

                    // Step 1: Take screenshot to temp file
                    val tempFile = File(dir, "som_temp_${System.currentTimeMillis()}.png")
                    val ssResult = service.captureScreenshot(tempFile.absolutePath)

                    if (!tempFile.exists() || tempFile.length() == 0L) {
                        return """{"error":"Screenshot failed: $ssResult"}"""
                    }

                    // Step 2: Load screenshot as mutable Bitmap
                    val screenshot = BitmapFactory.decodeFile(tempFile.absolutePath)
                        ?: return """{"error":"Failed to decode screenshot bitmap"}"""

                    // Step 3: Collect interactive elements from accessibility tree
                    val rootNode = service.rootInActiveWindow
                        ?: return """{"error":"No active window"}"""
                    val dm = context.resources.displayMetrics
                    val screenW = dm.widthPixels
                    val screenH = dm.heightPixels
                    val elements = mutableListOf<SomElement>()
                    collectInteractiveElements(rootNode, elements, screenW, screenH, maxElements = 30)
                    rootNode.recycle()

                    // Step 4: Draw SoM overlay
                    val annotated = drawSomOverlay(screenshot, elements)
                    screenshot.recycle()

                    // Step 5: Save annotated screenshot
                    val somFile = File(dir, "som_${System.currentTimeMillis()}.png")
                    FileOutputStream(somFile).use { annotated.compress(Bitmap.CompressFormat.PNG, 90, it) }
                    annotated.recycle()
                    tempFile.delete()

                    // Step 6: Store for tap_som_element
                    lastSomElements = elements.toList()

                    // Step 7: Build result mapping
                    val mapping = elements.mapIndexed { i, e ->
                        val id = i + 1
                        val cx = (e.left + e.right) / 2
                        val cy = (e.top + e.bottom) / 2
                        """{"id":$id,"text":${gson.toJson(e.text.take(50))},"type":"${e.className}","bounds":[${e.left},${e.top},${e.right},${e.bottom}],"center":[$cx,$cy],"clickable":${e.isClickable}}"""
                    }

                    ServiceState.addLog("SoM: annotated ${elements.size} elements")
                    """{"som_image":"${somFile.absolutePath}","elements_count":${elements.size},"elements":[${mapping.joinToString(",")}]}"""
                }
                "tap_som_element" -> {
                    val id = args.get("id")?.asInt
                        ?: return """{"error":"Missing 'id' parameter"}"""
                    val elements = lastSomElements
                    if (elements.isEmpty()) {
                        return """{"error":"No SoM data available. Run analyze_screen_with_som first."}"""
                    }
                    if (id < 1 || id > elements.size) {
                        return """{"error":"Invalid element id $id. Valid range: 1-${elements.size}"}"""
                    }
                    val element = elements[id - 1]
                    val cx = (element.left + element.right) / 2f
                    val cy = (element.top + element.bottom) / 2f
                    val service = ScreenReaderService.instance
                        ?: return """{"error":"Accessibility service not running"}"""
                    val success = suspendCancellableCoroutine<Boolean> { cont ->
                        service.tap(cx, cy) { result -> cont.resume(result) }
                    }
                    """{"success":$success,"id":$id,"text":${gson.toJson(element.text.take(50))},"tapped_at":[${cx.toInt()},${cy.toInt()}]}"""
                }
                "android_long_press" -> {
                    val reader = ScreenReaderService.instance
                        ?: return """{"error":"Accessibility service not enabled"}"""
                    val x = args.get("x")?.asFloat ?: return """{"error":"Missing required parameter 'x'"}"""
                    val y = args.get("y")?.asFloat ?: return """{"error":"Missing required parameter 'y'"}"""
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
                "explore_app" -> {
                    var pkgName = args.get("package_name").asString
                    val maxScreens = (args.get("max_screens")?.asInt ?: 5).coerceIn(1, 10)
                    val goal = args.get("goal")?.asString ?: ""

                    val reader = ScreenReaderService.instance
                        ?: return """{"error":"Accessibility service not enabled"}"""

                    // Open the app — try by name if package fails
                    val appContext = com.openclaw.android.OpenClawApplication.instance
                    val pm = appContext.packageManager
                    var launchIntent = pm.getLaunchIntentForPackage(pkgName)
                    if (launchIntent == null) {
                        // Search by name
                        val match = pm.getInstalledApplications(0).firstOrNull { appInfo ->
                            val label = pm.getApplicationLabel(appInfo).toString().lowercase()
                            label.contains(pkgName.lowercase()) || appInfo.packageName.lowercase().contains(pkgName.lowercase())
                        }
                        if (match != null) {
                            pkgName = match.packageName
                            launchIntent = pm.getLaunchIntentForPackage(pkgName)
                        }
                    }
                    if (launchIntent == null) return """{"error":"App not installed: $pkgName. Try run_shell_command('pm list packages -3') to find the correct package name."}"""
                    launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    appContext.startActivity(launchIntent)
                    delay(2000) // Wait for app to load

                    val exploredScreens = mutableListOf<Map<String, Any>>()
                    val visitedSignatures = mutableSetOf<String>()

                    for (screen in 0 until maxScreens) {
                        // Read current screen
                        val screenData = reader.readScreen()
                        val screenText = screenData.toString()

                        // Create a signature from key elements to detect duplicate screens
                        val signature = screenText.take(200).hashCode().toString()
                        if (signature in visitedSignatures) {
                            ServiceState.addLog("[Explore] Screen $screen duplicate, skipping")
                            break
                        }
                        visitedSignatures.add(signature)

                        // Parse interactive elements
                        val rootNode = reader.rootInActiveWindow
                        val elements = mutableListOf<Map<String, Any?>>()
                        if (rootNode != null) {
                            collectExploreElements(rootNode, elements, 0)
                            try { rootNode.recycle() } catch (_: Exception) {}
                        }

                        val screenInfo = mapOf(
                            "screen_index" to screen,
                            "package" to pkgName,
                            "element_count" to elements.size,
                            "interactive_elements" to elements.take(40),
                            "raw_summary" to screenText.take(500)
                        )
                        exploredScreens.add(screenInfo)
                        ServiceState.addLog("[Explore] Screen $screen: ${elements.size} elements found")

                        // Navigate to next screen (scroll down or tap first tab/menu)
                        if (screen < maxScreens - 1) {
                            // Try scrolling down to discover more content
                            val reader2 = ScreenReaderService.instance ?: break
                            reader2.swipe(540f, 1500f, 540f, 500f, 300)
                            delay(1000)
                        }
                    }

                    // Save exploration result as a note
                    val notesDir = java.io.File(
                        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS),
                        "OpenClaw/notes"
                    )
                    notesDir.mkdirs()
                    val appName = pkgName.substringAfterLast(".").replace(Regex("[^a-zA-Z0-9_-]"), "_")
                    val noteFile = java.io.File(notesDir, "app_map_$appName.md")

                    val noteContent = buildString {
                        appendLine("# App Map: $pkgName")
                        appendLine("Explored: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}")
                        if (goal.isNotBlank()) appendLine("Goal: $goal")
                        appendLine()
                        exploredScreens.forEach { screen ->
                            appendLine("## Screen ${screen["screen_index"]}")
                            appendLine("Elements: ${screen["element_count"]}")
                            @Suppress("UNCHECKED_CAST")
                            val elems = screen["interactive_elements"] as? List<Map<String, Any?>> ?: emptyList()
                            elems.forEach { el ->
                                val label = el["text"] ?: el["description"] ?: el["class_short"] ?: "?"
                                val role = el["role"] ?: ""
                                val cx = el["cx"]
                                val cy = el["cy"]
                                appendLine("- [$role] \"$label\" @ ($cx, $cy)")
                            }
                            appendLine()
                        }
                    }
                    noteFile.writeText(noteContent)

                    // Press back to return to previous state
                    ScreenReaderService.instance?.pressBack()

                    val result = mapOf(
                        "success" to true,
                        "app" to pkgName,
                        "screens_explored" to exploredScreens.size,
                        "note_saved" to noteFile.absolutePath,
                        "screens" to exploredScreens
                    )
                    gson.toJson(result)
                }
                else -> {
                    val utilResult = UtilityTools.executeTool(name, args)
                    if (utilResult.contains("Unknown tool")) {
                        val svcResult = ServiceTools.executeTool(name, args)
                        if (svcResult.contains("Unknown service tool")) {
                            val pyResult = PythonRuntime.executeTool(name, args)
                            if (pyResult.contains("Unknown python tool")) {
                                val nodeResult = NodeRuntime.executeTool(name, args)
                                if (nodeResult.contains("Unknown node tool")) LinuxEnvironment.executeTool(name, args) else nodeResult
                            } else pyResult
                        } else svcResult
                    } else utilResult
                }
            }
        } catch (e: Exception) {
            ServiceState.addLog("Tool error: $name — ${e.message}")
            """{"error":"${e.message?.replace("\"", "'")}"}"""
        }
    }

    // ── Set-of-Mark (SoM) Helpers ───────────────────────

    /**
     * Draw numbered circles and bounding boxes on a screenshot for each interactive element.
     */
    private fun drawSomOverlay(screenshot: Bitmap, elements: List<SomElement>): Bitmap {
        val result = screenshot.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        val circlePaint = Paint().apply {
            color = Color.RED
            style = Paint.Style.FILL
            alpha = 180
        }
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val borderPaint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 2f
            alpha = 120
        }

        elements.forEachIndexed { index, element ->
            val num = index + 1
            val cx = (element.left + element.right) / 2f
            val cy = (element.top + element.bottom) / 2f

            // Draw element border rectangle
            canvas.drawRect(
                element.left.toFloat(), element.top.toFloat(),
                element.right.toFloat(), element.bottom.toFloat(), borderPaint
            )

            // Draw numbered circle at center
            val radius = if (num < 10) 18f else 22f
            canvas.drawCircle(cx, cy, radius, circlePaint)
            canvas.drawText("$num", cx, cy + 9f, textPaint)
        }

        return result
    }

    /**
     * Walk the accessibility tree and collect interactive (clickable, checkable, editable)
     * elements that are on-screen and have valid bounds. Max [maxElements] to avoid clutter.
     */
    private fun collectInteractiveElements(
        node: AccessibilityNodeInfo,
        out: MutableList<SomElement>,
        screenW: Int,
        screenH: Int,
        maxElements: Int = 30,
        depth: Int = 0
    ) {
        if (out.size >= maxElements || depth > 15) return

        val rect = Rect()
        node.getBoundsInScreen(rect)

        val isInteractive = node.isClickable || node.isCheckable || node.isEditable
        val isVisible = rect.width() > 0 && rect.height() > 0
        val isOnScreen = rect.bottom > 0 && rect.top < screenH && rect.right > 0 && rect.left < screenW

        if (isInteractive && isVisible && isOnScreen) {
            val text = node.text?.toString()?.take(80) ?: ""
            val desc = node.contentDescription?.toString()?.take(80) ?: ""
            val displayText = text.ifBlank { desc }
            val cls = node.className?.toString()?.substringAfterLast(".") ?: "View"

            out.add(
                SomElement(
                    text = displayText,
                    className = cls,
                    left = rect.left.coerceAtLeast(0),
                    top = rect.top.coerceAtLeast(0),
                    right = rect.right.coerceAtMost(screenW),
                    bottom = rect.bottom.coerceAtMost(screenH),
                    isClickable = node.isClickable
                )
            )
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectInteractiveElements(child, out, screenW, screenH, maxElements, depth + 1)
            child.recycle()
        }
    }

    /**
     * Walk accessibility tree for explore_app — collects interactive elements with roles and coordinates.
     */
    private fun collectExploreElements(
        node: AccessibilityNodeInfo,
        out: MutableList<Map<String, Any?>>,
        depth: Int
    ) {
        if (out.size >= 60 || depth > 15) return

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val text = node.text?.toString()?.take(50) ?: ""
        val desc = node.contentDescription?.toString()?.take(50) ?: ""
        val className = node.className?.toString() ?: ""
        val classShort = className.substringAfterLast(".")

        val isInteractive = node.isClickable || node.isCheckable || node.isEditable || node.isScrollable

        if (isInteractive && bounds.width() > 10 && bounds.height() > 10) {
            val role = when {
                node.isEditable -> "input"
                classShort.contains("Button", true) -> "button"
                classShort.contains("EditText", true) -> "input"
                classShort.contains("Image", true) && node.isClickable -> "icon-button"
                classShort.contains("Tab", true) -> "tab"
                classShort.contains("Switch", true) || classShort.contains("Toggle", true) -> "toggle"
                classShort.contains("CheckBox", true) -> "checkbox"
                node.isScrollable -> "scrollable"
                node.isClickable -> "clickable"
                else -> "interactive"
            }

            out.add(mapOf(
                "text" to text.ifBlank { null },
                "description" to desc.ifBlank { null },
                "class_short" to classShort,
                "role" to role,
                "cx" to (bounds.left + bounds.right) / 2,
                "cy" to (bounds.top + bounds.bottom) / 2,
                "bounds" to listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)
            ))
        }

        for (i in 0 until node.childCount) {
            try {
                val child = node.getChild(i) ?: continue
                collectExploreElements(child, out, depth + 1)
                try { child.recycle() } catch (_: Exception) {}
            } catch (_: Exception) {}
        }
    }
}

/**
 * Represents an interactive UI element for Set-of-Mark overlay.
 */
data class SomElement(
    val text: String,
    val className: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val isClickable: Boolean
)
