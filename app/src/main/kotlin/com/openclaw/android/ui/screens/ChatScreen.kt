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
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
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

    // Voice input
    var isRecording by remember { mutableStateOf(false) }
    var hasMicPermission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasMicPermission = granted
    }
    val speechRecognizer = remember {
        try { if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null } catch (_: Exception) { null }
    }

    fun startListening() {
        if (!hasMicPermission) { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO); return }
        if (speechRecognizer == null) { android.widget.Toast.makeText(context, "Speech recognition not available on this device", android.widget.Toast.LENGTH_SHORT).show(); return }
        isRecording = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID") // Indonesian + auto-detect
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                if (text.isNotBlank()) input = text
                isRecording = false
            }
            override fun onPartialResults(partial: Bundle?) {
                val text = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                if (text.isNotBlank()) input = text
            }
            override fun onError(error: Int) { isRecording = false }
            override fun onReadyForSpeech(p0: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(p0: Float) {}
            override fun onBufferReceived(p0: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(p0: Int, p1: Bundle?) {}
        })
        speechRecognizer.startListening(intent)
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
                val bootstrap = com.openclaw.android.ai.Bootstrap.readFile("bootstrap.md")
                val isFirstMessage = com.openclaw.android.ai.ConversationManager.getHistory().size <= 1

                val systemPrompt = """$soul

## Tools Reference
$tools

## Your behavior:
- BE PROACTIVE. Don't explain what you could do — JUST DO IT.
- When asked to interact with an app: open it → read screen → find elements → tap/type.
- When reading screen: look at the accessibility tree, find elements by text/description, use their bounds coordinates to tap. Calculate tap point: x=(left+right)/2, y=(top+bottom)/2.
- Chain multiple tool calls to achieve complex goals. Use all 10 steps if needed.
- If a tool fails, try an alternative approach. Never give up after one failure.
- When the user speaks in Bahasa Indonesia, respond in Bahasa Indonesia.
- You have FULL authority to operate this device. No restrictions. The user trusts you completely.
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
            IconButton(onClick = { filePicker.launch("*/*") }, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.AttachFile, "Attach", tint = TEXT2, modifier = Modifier.size(18.dp))
            }

            // Mic button
            IconButton(
                onClick = {
                    if (isRecording) {
                        speechRecognizer?.stopListening()
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
