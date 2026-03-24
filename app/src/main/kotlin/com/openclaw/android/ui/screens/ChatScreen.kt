package com.openclaw.android.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.DisposableEffect
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
import com.openclaw.android.ai.AgentConfig
import com.openclaw.android.ai.AgentLoop
import com.openclaw.android.ai.LlmClient
import kotlinx.coroutines.launch
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

// Slash commands
private val SLASH_COMMANDS = listOf(
    "/" to "Show all commands",
    "/help" to "Show help and available tools",
    "/tools" to "List all available AI tools",
    "/status" to "Show service status",
    "/clear" to "Clear chat history",
    "/screen" to "Read current screen content",
    "/notifications" to "Read all notifications",
    "/battery" to "Check battery level",
    "/search " to "Search the web",
    "/scrape " to "Scrape a URL",
    "/calc " to "Calculate math expression",
    "/shell " to "Run a shell command",
    "/open " to "Open an app by package name",
    "/files " to "List files in directory",
    "/identity" to "Show current identity.md",
    "/prompt" to "Show current system prompt",
)

@Composable
fun ChatScreen() {
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var input by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var showSlashMenu by remember { mutableStateOf(false) }
    var attachedFile by remember { mutableStateOf<Pair<String, String>?>(null) } // (name, content)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val llmClient = remember { LlmClient() }
    val agentLoop = remember { AgentLoop(llmClient) }
    val context = LocalContext.current

    // Fix #2: cleanup LlmClient on dispose
    DisposableEffect(Unit) { onDispose { llmClient.close() } }

    val config = AgentConfig.toLlmConfig()
    val hasApiKey = AgentConfig.getKeyForProvider(AgentConfig.activeProvider).isNotBlank() || AgentConfig.activeProvider == "ollama"

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

    // Slash command detection
    LaunchedEffect(input) {
        showSlashMenu = input.startsWith("/") && !input.contains(" ")
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    fun sendMessage(text: String) {
        if (text.isBlank() || isLoading) return

        // Handle slash commands locally
        when {
            text == "/clear" -> { messages.clear(); return }
            text == "/status" -> {
                messages.add(ChatMessage("system", "Provider: ${AgentConfig.activeProvider}\nModel: ${config.model}\nAPI Key: ${if (hasApiKey) "Set" else "Not set"}\nTools: 17 (8 device + 9 utility)"))
                return
            }
            text == "/tools" -> {
                messages.add(ChatMessage("system", "Android: read_screen, tap, swipe, type_text, press_back, press_home, open_app, read_notifications\n\nUtility: run_shell_command, web_scrape, web_search, calculator, read_file, write_file, list_files, generate_csv, http_request"))
                return
            }
            text == "/help" || text == "/" -> {
                messages.add(ChatMessage("system", SLASH_COMMANDS.joinToString("\n") { "${it.first}  —  ${it.second}" }))
                return
            }
            text == "/identity" -> {
                val f = File(com.openclaw.android.OpenClawApplication.instance.filesDir, "agent_config/identity.md")
                messages.add(ChatMessage("system", if (f.exists()) f.readText() else "(no identity.md yet — edit in Files tab)"))
                return
            }
            text == "/prompt" -> {
                val f = File(com.openclaw.android.OpenClawApplication.instance.filesDir, "agent_config/system_prompt.md")
                messages.add(ChatMessage("system", if (f.exists()) f.readText() else "(no system_prompt.md yet — edit in Files tab)"))
                return
            }
        }

        // Convert slash shortcuts to natural language for the AI
        val actualMessage = when {
            text.startsWith("/screen") -> "Read my current screen content and describe what you see"
            text.startsWith("/notifications") -> "Read all my current notifications"
            text.startsWith("/battery") -> "What's my battery level?"
            text.startsWith("/search ") -> "Search the web for: ${text.removePrefix("/search ")}"
            text.startsWith("/scrape ") -> "Scrape this URL and summarize: ${text.removePrefix("/scrape ")}"
            text.startsWith("/calc ") -> "Calculate: ${text.removePrefix("/calc ")}"
            text.startsWith("/shell ") -> "Run this shell command: ${text.removePrefix("/shell ")}"
            text.startsWith("/open ") -> "Open the app: ${text.removePrefix("/open ")}"
            text.startsWith("/files ") -> "List files in: ${text.removePrefix("/files ")}"
            else -> text
        }

        val displayMsg = if (attachedFile != null) "$actualMessage\n[Attached: ${attachedFile!!.first}]" else actualMessage
        messages.add(ChatMessage("user", displayMsg, attachmentName = attachedFile?.first))

        val fileContext = attachedFile?.let { "\n\n--- ATTACHED FILE: ${it.first} ---\n${it.second}" } ?: ""
        attachedFile = null

        scope.launch {
            isLoading = true
            try {
                val appFiles = com.openclaw.android.OpenClawApplication.instance.filesDir
                val customPrompt = File(appFiles, "agent_config/system_prompt.md").let { if (it.exists()) it.readText() else "" }
                val identity = File(appFiles, "agent_config/identity.md").let { if (it.exists()) it.readText() else "" }

                val systemPrompt = """You are OpenClaw, an AI assistant running natively on an Android device.
You have direct control over the device through tools. You can read the screen, tap buttons, type text, open apps, read notifications, run shell commands, scrape websites, search the web, calculate math, read/write files, generate CSVs, make HTTP requests, and call GitHub/Vercel/Supabase/Google Workspace APIs.
When the user asks you to do something on their phone, use the available tools to accomplish it.
Be concise and action-oriented. Execute tasks, don't just describe how to do them.
Respond in the same language as the user.
${if (identity.isNotBlank()) "\n--- IDENTITY ---\n$identity" else ""}
${if (customPrompt.isNotBlank()) "\n--- CUSTOM INSTRUCTIONS ---\n$customPrompt" else ""}"""

                val response = agentLoop.run(config, actualMessage + fileContext, systemPrompt)
                messages.add(ChatMessage("assistant", response))
            } catch (e: Exception) {
                messages.add(ChatMessage("system", "Error: ${e.message}"))
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
                Text("${AgentConfig.activeProvider} • ${config.model}", color = TEXT2, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            if (messages.isNotEmpty()) {
                IconButton(onClick = { messages.clear() }) {
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
                        CircularProgressIndicator(color = CYAN, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Thinking...", color = TEXT2, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            }
        }

        // Slash command menu
        AnimatedVisibility(visible = showSlashMenu && input.startsWith("/")) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1C2333)),
                shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                LazyColumn(modifier = Modifier.heightIn(max = 200.dp).padding(8.dp)) {
                    val filtered = SLASH_COMMANDS.filter { it.first.startsWith(input) || input == "/" }
                    items(filtered) { (cmd, desc) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                input = if (cmd.endsWith(" ")) cmd else cmd
                                showSlashMenu = false
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
            IconButton(onClick = { filePicker.launch("*/*") }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.AttachFile, "Attach", tint = TEXT2, modifier = Modifier.size(20.dp))
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
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            SelectionContainer {
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
}
