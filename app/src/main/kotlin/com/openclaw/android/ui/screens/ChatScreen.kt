package com.openclaw.android.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.openclaw.android.ai.AgentConfig
import com.openclaw.android.ai.AgentLoop
import com.openclaw.android.ai.LlmClient
import com.openclaw.android.service.ScreenReaderService
import com.openclaw.android.util.ServiceState
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val BG = Color(0xFF0D1117)
private val SURFACE = Color(0xFF161B22)
private val BORDER = Color(0xFF30363D)
private val CYAN = Color(0xFF58A6FF)
private val GREEN = Color(0xFF3FB950)
private val RED = Color(0xFFF85149)
private val PURPLE = Color(0xFFD2A8FF)
private val ORANGE = Color(0xFFD29922)
private val TEXT = Color(0xFFF0F6FC)
private val TEXT2 = Color(0xFF8B949E)

data class ChatMessage(
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val attachmentName: String? = null
)

// Persistent chat state — Room SQLite DB + in-memory list
object ChatState {
    val messages = mutableStateListOf<ChatMessage>()
    private var loaded = false

    private fun db() = com.openclaw.android.data.AppDatabase.getInstance(
        com.openclaw.android.OpenClawApplication.instance
    ).chatMessageDao()

    suspend fun load() {
        if (loaded && messages.isNotEmpty()) return
        try {
            val saved = db().getRecent("default", 200).reversed() // oldest first
            messages.clear()
            messages.addAll(saved.map { ChatMessage(it.role, it.content, it.timestamp, it.attachmentName) })
            loaded = true
        } catch (_: Exception) {}
    }

    fun addMessage(msg: ChatMessage) {
        messages.add(msg)
        // Save to DB in background
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                db().insert(com.openclaw.android.data.entity.ChatMessageEntity(
                    role = msg.role, content = msg.content, timestamp = msg.timestamp,
                    attachmentName = msg.attachmentName
                ))
            } catch (_: Exception) {}
        }
    }

    fun clear() {
        messages.clear()
        com.openclaw.android.ai.ConversationManager.clear()
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try { db().clearSession("default") } catch (_: Exception) {}
        }
    }

    /** Search chat history by keyword */
    suspend fun search(query: String): List<ChatMessage> {
        return try {
            db().search(query, 20).map { ChatMessage(it.role, it.content, it.timestamp, it.attachmentName) }
        } catch (_: Exception) { emptyList() }
    }
}

// Slash commands — only for local actions (no LLM needed)
private val SLASH_COMMANDS = listOf(
    "/" to "Show all commands",
    "/help" to "Show available commands",
    "/tools" to "List all active tools",
    "/status" to "Show service & connection status",
    "/clear" to "Clear chat history",
    "/reload" to "Force reload all workspace files",
    "/identity" to "View identity.md",
    "/prompt" to "View system prompt",
    "/shell " to "Run a shell command directly",
)

@Composable
fun ChatScreen() {
    // Load chat history from disk on first open
    LaunchedEffect(Unit) { if (ChatState.messages.isEmpty()) ChatState.load() }

    val messages = ChatState.messages
    var input by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var attachedFile by remember { mutableStateOf<Pair<String, String>?>(null) }
    // Use GlobalScope so agent keeps running when user switches tabs
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()) }
    val listState = rememberLazyListState()
    val llmClient = remember { LlmClient() }
    val agentLoop = remember { AgentLoop(llmClient) }
    val context = LocalContext.current

    // Read fresh on each recomposition (SharedPrefs reads are cheap)
    val activeProvider = AgentConfig.activeProvider
    val config = AgentConfig.toLlmConfig()
    val hasApiKey = AgentConfig.getKeyForProvider(activeProvider).isNotBlank() || activeProvider == "ollama"

    // File picker
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            try {
                val cursor = context.contentResolver.query(it, null, null, null, null)
                val name = cursor?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    c.moveToFirst()
                    if (idx >= 0) c.getString(idx) else "file"
                } ?: "file"
                val content = context.contentResolver.openInputStream(it)?.bufferedReader()?.readText()?.take(4000) ?: "(binary file)"
                attachedFile = name to content
            } catch (e: Exception) {
                attachedFile = "error" to "Failed to read file: ${e.message}"
            }
        }
    }

    // Camera — uses ScreenReaderService.captureScreenshot() instead of ACTION_IMAGE_CAPTURE.
    // This avoids FileProvider configuration requirements and is more useful for the agent
    // (captures the current screen rather than opening the device camera).
    var isTakingScreenshot by remember { mutableStateOf(false) }

    fun takeScreenshotAsAttachment() {
        val reader = ScreenReaderService.instance
        if (reader == null) {
            ServiceState.addLog("[Camera] ScreenReaderService not connected — enable Accessibility Service first")
            android.widget.Toast.makeText(context, "Accessibility Service not enabled. Enable it in Settings.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        isTakingScreenshot = true
        ServiceState.addLog("[Camera] Capturing screenshot for chat attachment")
        scope.launch(Dispatchers.IO) {
            try {
                val dir = File(context.filesDir, "screenshots")
                dir.mkdirs()
                val file = File(dir, "chat_screenshot_${System.currentTimeMillis()}.png")
                val result = reader.captureScreenshot(file.absolutePath)
                withContext(Dispatchers.Main) {
                    if (file.exists() && file.length() > 0) {
                        // Read first 200 bytes as base64 header hint; store path as context for agent
                        val sizeKb = file.length() / 1024
                        attachedFile = "screenshot.png" to "[Screenshot captured: ${file.absolutePath} (${sizeKb}KB). The agent can read this file using read_file or send it via send_telegram_photo.]"
                        ServiceState.addLog("[Camera] Screenshot saved: ${file.absolutePath} (${sizeKb}KB)")
                        android.widget.Toast.makeText(context, "Screenshot captured (${sizeKb}KB)", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        ServiceState.addLog("[Camera] Screenshot failed — result: $result")
                        android.widget.Toast.makeText(context, "Screenshot failed. Android 11+ required.", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                ServiceState.addLog("[Camera] Screenshot exception: ${e.message}")
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Screenshot error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            } finally {
                withContext(Dispatchers.Main) { isTakingScreenshot = false }
            }
        }
    }

    // Voice input
    var isRecording by remember { mutableStateOf(false) }
    var hasMicPermission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasMicPermission = granted
        if (granted) {
            ServiceState.addLog("[Mic] RECORD_AUDIO permission granted")
        } else {
            ServiceState.addLog("[Mic] RECORD_AUDIO permission denied")
        }
    }

    // SpeechRecognizer must be created and destroyed on the main thread.
    // Use a var + DisposableEffect so it is properly released when the composable leaves composition.
    var speechRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    DisposableEffect(Unit) {
        val available = try { SpeechRecognizer.isRecognitionAvailable(context) } catch (_: Exception) { false }
        if (available) {
            speechRecognizer = try {
                SpeechRecognizer.createSpeechRecognizer(context).also {
                    ServiceState.addLog("[Mic] SpeechRecognizer created")
                }
            } catch (e: Exception) {
                ServiceState.addLog("[Mic] SpeechRecognizer creation failed: ${e.message}")
                null
            }
        } else {
            ServiceState.addLog("[Mic] SpeechRecognizer not available on this device")
        }
        onDispose {
            try {
                speechRecognizer?.destroy()
                ServiceState.addLog("[Mic] SpeechRecognizer destroyed")
            } catch (_: Exception) {}
            speechRecognizer = null
        }
    }

    fun startListening() {
        if (!hasMicPermission) {
            ServiceState.addLog("[Mic] No RECORD_AUDIO permission — requesting")
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val recognizer = speechRecognizer
        if (recognizer == null) {
            ServiceState.addLog("[Mic] SpeechRecognizer is null — not available on this device")
            android.widget.Toast.makeText(context, "Speech recognition not available on this device", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        ServiceState.addLog("[Mic] Starting speech recognition (id-ID)")
        isRecording = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "id-ID")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    ServiceState.addLog("[Mic] Ready for speech")
                }
                override fun onBeginningOfSpeech() {
                    ServiceState.addLog("[Mic] Speech started")
                }
                override fun onPartialResults(partial: Bundle?) {
                    val text = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                    if (text.isNotBlank()) input = text
                }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                    ServiceState.addLog("[Mic] Final result: \"$text\"")
                    if (text.isNotBlank()) input = text
                    isRecording = false
                }
                override fun onError(error: Int) {
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                        SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                        SpeechRecognizer.ERROR_NETWORK -> "Network error"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                        SpeechRecognizer.ERROR_SERVER -> "Server error"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                        else -> "Unknown error ($error)"
                    }
                    ServiceState.addLog("[Mic] Error: $msg")
                    if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        android.widget.Toast.makeText(context, "Mic: $msg", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    isRecording = false
                }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { ServiceState.addLog("[Mic] End of speech") }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            recognizer.startListening(intent)
        } catch (e: Exception) {
            ServiceState.addLog("[Mic] startListening exception: ${e.message}")
            android.widget.Toast.makeText(context, "Mic error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            isRecording = false
        }
    }

    // Slash menu: derive from input state directly (no LaunchedEffect)
    val showSlashMenu = input.startsWith("/") && !input.contains(" ") && input.length < 15

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    fun sendMessage(text: String) {
        if (text.isBlank() || isLoading) return

        // Handle slash commands locally
        when {
            text == "/clear" -> { ChatState.clear(); com.openclaw.android.ai.ConversationManager.clear(); return }
            text == "/status" -> {
                ChatState.addMessage(ChatMessage("system", "Provider: ${AgentConfig.activeProvider}\nModel: ${config.model}\nAPI Key: ${if (hasApiKey) "Set" else "Not set"}\nTools: 17 (8 device + 9 utility)"))
                return
            }
            text == "/tools" -> {
                ChatState.addMessage(ChatMessage("system", "Android: read_screen, tap, swipe, type_text, press_back, press_home, open_app, read_notifications\n\nUtility: run_shell_command, web_scrape, web_search, calculator, read_file, write_file, list_files, generate_csv, http_request"))
                return
            }
            text == "/help" || text == "/" -> {
                ChatState.addMessage(ChatMessage("system", SLASH_COMMANDS.joinToString("\n") { "${it.first}  —  ${it.second}" }))
                return
            }
            text == "/reload" -> {
                val files = listOf("SOUL.md", "USER.md", "TOOLS.md", "skills.md", "memory.md", "identity.md", "system_prompt.md", "HEARTBEAT.md", "AGENTS.md", "bootstrap.md")
                val status = files.map { name ->
                    val content = com.openclaw.android.ai.Bootstrap.readFile(name)
                    val size = if (content.isBlank()) "empty" else "${content.length} chars"
                    "$name: $size"
                }
                // Also clear conversation history so next message uses fresh context
                com.openclaw.android.ai.ConversationManager.clear()
                ChatState.addMessage(ChatMessage("system", "Workspace files reloaded. Conversation context reset.\n\n${status.joinToString("\n")}"))
                return
            }
            text == "/identity" -> {
                val f = File(com.openclaw.android.OpenClawApplication.instance.filesDir, "agent_config/identity.md")
                ChatState.addMessage(ChatMessage("system", if (f.exists()) f.readText() else "(no identity.md yet — edit in Files tab)"))
                return
            }
            text == "/prompt" -> {
                val f = File(com.openclaw.android.OpenClawApplication.instance.filesDir, "agent_config/system_prompt.md")
                ChatState.addMessage(ChatMessage("system", if (f.exists()) f.readText() else "(no system_prompt.md yet — edit in Files tab)"))
                return
            }
        }

        // /shell runs directly without LLM, everything else goes to AI
        val actualMessage = if (text.startsWith("/shell ")) {
            "Run this shell command: ${text.removePrefix("/shell ")}"
        } else text

        val displayMsg = if (attachedFile != null) "$actualMessage\n[Attached: ${attachedFile!!.first}]" else actualMessage
        ChatState.addMessage(ChatMessage("user", displayMsg, attachmentName = attachedFile?.first))

        val fileContext = attachedFile?.let { "\n\n--- ATTACHED FILE: ${it.first} ---\n${it.second}" } ?: ""
        attachedFile = null

        scope.launch {
            isLoading = true
            try {
                // Auto-bootstrap on first connection
                if (!com.openclaw.android.ai.Bootstrap.isBootstrapped()) {
                    com.openclaw.android.ai.Bootstrap.run()
                    ChatState.addMessage(ChatMessage("system", "Workspace initialized. Edit files in the Files tab."))
                }

                // Load all workspace files for context
                val soul = com.openclaw.android.ai.Bootstrap.readFile("SOUL.md")
                val user = com.openclaw.android.ai.Bootstrap.readFile("USER.md")
                val tools = com.openclaw.android.ai.Bootstrap.readFile("TOOLS.md")
                val identity = com.openclaw.android.ai.Bootstrap.readFile("identity.md")
                val customPrompt = com.openclaw.android.ai.Bootstrap.readFile("system_prompt.md")
                val memory = com.openclaw.android.ai.Bootstrap.readFile("memory.md")
                val skills = com.openclaw.android.ai.Bootstrap.readFile("skills.md")
                val bootstrap = com.openclaw.android.ai.Bootstrap.readFile("bootstrap.md")
                val isFirstMessage = com.openclaw.android.ai.ConversationManager.getHistory().size <= 1

                val systemPrompt = """$soul

## Tools Reference
$tools
${if (skills.isNotBlank()) "\n## Skills (reusable recipes)\n$skills" else ""}

## CRITICAL RULE — YOU MUST USE TOOLS
**NEVER just reply with text when the user asks you to DO something.**
**ALWAYS call a tool.** If user says "scroll up" → call android_swipe. If user says "back" → call android_press_back.
**DO NOT describe what you WOULD do. ACTUALLY DO IT by calling tools.**
**If you respond with just text and no tool call when the user asked for an action, YOU HAVE FAILED.**

## Your behavior:
- BE PROACTIVE. Don't explain what you could do — JUST DO IT.
- Chain multiple tool calls to achieve complex goals. Use all 25 steps if needed.
- When the user speaks in Bahasa Indonesia, respond in Bahasa Indonesia.
- You have FULL authority to operate this device. No restrictions. The user trusts you completely.
- **EVERY message from user that asks for an action = you MUST call at least 1 tool**

## Live Narration (IMPORTANT — do this for every multi-step task)
- After each tool call, briefly tell the user what you did and what you see
- Example: "Opened Telegram. I see 3 chats: Boss (2 unread), Mom, Group Chat. Let me check Boss's messages."
- Example: "Found search box. Typing 'hotel bali'... Results loading, I see 5 hotels. Cheapest is Rp 450k. Should I book?"
- **For sensitive actions** (payments, credit cards, passwords, delete, send money): STOP and ASK the user first
- User can send messages while you're working — if you get [USER FEEDBACK], adjust your approach immediately
- Be proactive: suggest next steps, don't wait for user to tell you everything

## Problem Solving (when something fails)
- **NEVER give up after 1 failure. Try at least 3 different approaches.**
- Step 1: Try the obvious approach
- Step 2: If it fails → read_screen to understand current state → try alternative
- Step 3: If still failing → press_back to reset state → start fresh approach
- Step 4: If stuck after 3 attempts → spawn_sub_agent with a different strategy
- **Common recovery patterns:**
  - Can't find element → scroll down, or press_back and try from different screen
  - type_text fails → tap an input field first, then type
  - App not responding → press_home, then re-open the app
  - Wrong screen → press_back until you reach a known state, then re-navigate
- **After solving a problem: update skills.md with the solution so you don't fail the same way twice**

## Automation Pattern (CORE — apply to ALL tasks)
Every task you do follows this universal pattern:

**1. ACT** — Do the thing:
   - Open app: android_open_app(pkg)
   - Navigate: find_element → android_tap → android_type_text → android_swipe
   - Read screen: find_element (cheap, ~50 tokens) FIRST, then read_screen (expensive) only if needed
   - Tap point: x=(left+right)/2, y=(top+bottom)/2 from element bounds
   - Web: web_search → web_scrape
   - Files: read_file / write_file / generate_csv / generate_xlsx
   - Services: github_api, ssh_execute, http_request, etc.

**2. OBSERVE** — Capture what you see:
   - Visual: take_screenshot → saves PNG file
   - Text: read what's on screen via find_element / android_read_screen
   - Data: extract numbers, text, lists from the screen or web

**3. REPORT** — Send results to the user's chosen gateway:
   - **This chat**: just reply with text (default if no gateway specified)
   - **Telegram**: send_telegram_photo(file, caption) for images, send_telegram_message(text) for text
   - **File**: write_file to save report as CSV/PDF/XLSX
   - Format depends on what user asked: screenshot = visual proof, text = summary/data, both = screenshot + caption
   - If user doesn't specify format, use BOTH: screenshot + brief text summary

**4. LEARN** — Save everything to SQLite memory:
   - ALWAYS use memory_store for facts, preferences, task outcomes, discoveries
   - memory_store(content="user prefers Bahasa Indonesia", type="preference", importance=0.8)
   - memory_store(content="YouTube: must press_back before searching new video", type="skill", importance=0.9)
   - Use memory_search BEFORE starting tasks to recall relevant past experience
   - Also save skills to skills.md for system prompt injection
   - Don't ask permission to save — just do it

## Workspace
- Read/update your config files (SOUL.md, USER.md, memory.md, skills.md, etc.) via read_workspace_file / update_workspace_file
- Skills in skills.md: when user request matches a saved skill, follow its steps
${if (isFirstMessage && bootstrap.isNotBlank()) "\n--- BOOTSTRAP (first message) ---\n$bootstrap" else ""}
${if (user.isNotBlank()) "\n--- USER PROFILE ---\n$user" else ""}
${if (identity.isNotBlank()) "\n--- IDENTITY ---\n$identity" else ""}
${if (memory.isNotBlank()) "\n--- MEMORY ---\n$memory" else ""}
${if (customPrompt.isNotBlank()) "\n--- CUSTOM INSTRUCTIONS ---\n$customPrompt" else ""}"""

                val response = agentLoop.run(config, actualMessage + fileContext, systemPrompt)
                ChatState.addMessage(ChatMessage("assistant", response))
            } catch (e: Exception) {
                ChatState.addMessage(ChatMessage("system", "Error: ${e.message}"))
            } finally {
                isLoading = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(BG)) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().background(SURFACE).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(10.dp).background(if (hasApiKey) GREEN else RED, CircleShape))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("OpenClaw Chat", color = TEXT, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Row {
                    Text("$activeProvider • ${config.model}", color = TEXT2, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(8.dp))
                    val tokenDisplay = com.openclaw.android.ai.ConversationManager.getContextDisplay(activeProvider)
                    Text(tokenDisplay, color = CYAN, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
            if (messages.isNotEmpty()) {
                IconButton(onClick = { ChatState.clear() }) {
                    Icon(Icons.Default.Delete, "Clear", tint = TEXT2, modifier = Modifier.size(20.dp))
                }
            }
        }

        if (!hasApiKey) {
            Card(colors = CardDefaults.cardColors(containerColor = RED.copy(alpha = 0.15f)), shape = RoundedCornerShape(0.dp), modifier = Modifier.fillMaxWidth()) {
                Text("No API key set. Go to Settings tab.", color = RED, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(12.dp))
            }
        }

        // Messages
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("OpenClaw", color = TEXT2, fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(4.dp))
                        Text("AI with full device control", color = BORDER, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(4.dp))
                        Text("Type / to see commands", color = CYAN.copy(alpha = 0.6f), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(20.dp))
                        listOf("Read my notifications", "What's on my screen?", "Open WhatsApp", "Search the web for weather Jakarta").forEach { prompt ->
                            OutlinedButton(
                                onClick = { input = prompt },
                                modifier = Modifier.fillMaxWidth(0.85f).padding(vertical = 3.dp),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, BORDER),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = TEXT2)
                            ) { Text(prompt, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                        }
                    }
                }
            }

            items(messages) { msg -> MessageBubble(msg) }

            if (isLoading) {
                item {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("●", color = CYAN, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.width(8.dp))
                        Text("Thinking...", color = TEXT2, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            }
        }

        // Slash command menu (simple if, no animation)
        if (showSlashMenu) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1C2333)),
                shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()).padding(8.dp)) {
                    val filtered = SLASH_COMMANDS.filter { it.first.startsWith(input) || input == "/" }
                    filtered.forEach { (cmd, desc) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                input = cmd
                                if (!cmd.endsWith(" ")) sendMessage(cmd)
                            }.padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(cmd, color = CYAN, fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(12.dp))
                            Text(desc, color = TEXT2, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Attached file indicator
        if (attachedFile != null) {
            Row(
                modifier = Modifier.fillMaxWidth().background(SURFACE).padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.AttachFile, null, tint = CYAN, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(attachedFile!!.first, color = CYAN, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { attachedFile = null }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, "Remove", tint = TEXT2, modifier = Modifier.size(14.dp))
                }
            }
        }

        // Input bar
        Row(
            modifier = Modifier.fillMaxWidth().background(SURFACE).padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Attach button
            IconButton(onClick = { filePicker.launch("*/*") }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.AttachFile, "Attach", tint = TEXT2, modifier = Modifier.size(16.dp))
            }
            // Camera button — captures current screen via AccessibilityService screenshot
            IconButton(
                onClick = { if (!isTakingScreenshot) takeScreenshotAsAttachment() },
                modifier = Modifier.size(30.dp),
                enabled = !isTakingScreenshot
            ) {
                Icon(
                    Icons.Default.CameraAlt,
                    "Screenshot",
                    tint = if (isTakingScreenshot) CYAN else TEXT2,
                    modifier = Modifier.size(16.dp)
                )
            }

            // Mic button
            IconButton(
                onClick = {
                    if (isRecording) {
                        try {
                            speechRecognizer?.stopListening()
                            ServiceState.addLog("[Mic] Stopped by user")
                        } catch (e: Exception) {
                            ServiceState.addLog("[Mic] stopListening error: ${e.message}")
                        }
                        isRecording = false
                    } else {
                        startListening()
                    }
                },
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    Icons.Default.Mic,
                    "Voice",
                    tint = if (isRecording) RED else TEXT2,
                    modifier = Modifier.size(18.dp)
                )
            }

            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Message or /command...", color = Color(0xFF484F58), fontFamily = FontFamily.Monospace, fontSize = 14.sp) },
                modifier = Modifier.weight(1f),
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = TEXT),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CYAN, unfocusedBorderColor = BORDER, cursorColor = CYAN, focusedContainerColor = Color(0xFF010409), unfocusedContainerColor = Color(0xFF010409)),
                shape = RoundedCornerShape(12.dp),
                maxLines = 4,
                enabled = !isLoading
            )
            Spacer(Modifier.width(4.dp))
            IconButton(
                onClick = { val msg = input.trim(); input = ""; sendMessage(msg) },
                enabled = input.isNotBlank() && !isLoading && (hasApiKey || input.startsWith("/")),
                modifier = Modifier.size(40.dp).clip(CircleShape).background(if (input.isNotBlank() && (hasApiKey || input.startsWith("/"))) CYAN else BORDER)
            ) {
                Icon(Icons.Default.Send, "Send", tint = if (input.isNotBlank()) Color.White else TEXT2, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
fun MessageBubble(msg: ChatMessage) {
    val context = LocalContext.current
    val isUser = msg.role == "user"
    val isSystem = msg.role == "system"
    val bubbleColor = when {
        isUser -> CYAN.copy(alpha = 0.15f)
        isSystem -> ORANGE.copy(alpha = 0.1f)
        else -> SURFACE
    }
    val textColor = when {
        isSystem -> ORANGE
        isUser -> TEXT
        else -> Color(0xFFC9D1D9)
    }
    val align = if (isUser) Arrangement.End else Arrangement.Start
    val label = when {
        isSystem -> "System"
        isUser -> null
        else -> "OpenClaw"
    }
    val labelColor = when {
        isSystem -> ORANGE
        else -> CYAN
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = align) {
        Card(
            colors = CardDefaults.cardColors(containerColor = bubbleColor),
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = if (isUser) 12.dp else 4.dp, bottomEnd = if (isUser) 4.dp else 12.dp),
            modifier = Modifier.widthIn(max = 320.dp).clickable {
                val clip = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clip.setPrimaryClip(android.content.ClipData.newPlainText("msg", msg.content))
                android.widget.Toast.makeText(context, "Copied!", android.widget.Toast.LENGTH_SHORT).show()
            }
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                if (label != null) {
                    Text(label, color = labelColor, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(2.dp))
                }
                Text(msg.content, color = textColor, fontSize = 13.sp, fontFamily = FontFamily.Monospace, lineHeight = 19.sp)
            }
        }
    }
}
