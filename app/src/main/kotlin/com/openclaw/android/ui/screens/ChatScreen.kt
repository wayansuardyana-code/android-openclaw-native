package com.openclaw.android.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openclaw.android.ai.AgentConfig
import com.openclaw.android.ai.AgentLoop
import com.openclaw.android.ai.LlmClient
import kotlinx.coroutines.launch

private val BG = Color(0xFF0D1117)
private val SURFACE = Color(0xFF161B22)
private val BORDER = Color(0xFF30363D)
private val CYAN = Color(0xFF58A6FF)
private val GREEN = Color(0xFF3FB950)
private val RED = Color(0xFFF85149)
private val PURPLE = Color(0xFFD2A8FF)
private val TEXT = Color(0xFFF0F6FC)
private val TEXT2 = Color(0xFF8B949E)

data class ChatMessage(
    val role: String,      // "user", "assistant", "system"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isToolCall: Boolean = false
)

@Composable
fun ChatScreen() {
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var input by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val llmClient = remember { LlmClient() }
    val agentLoop = remember { AgentLoop(llmClient) }

    val config = AgentConfig.toLlmConfig()
    val hasApiKey = when (AgentConfig.activeProvider) {
        "anthropic" -> AgentConfig.anthropicKey.isNotBlank()
        "openai" -> AgentConfig.openaiKey.isNotBlank()
        "minimax" -> AgentConfig.minimaxKey.isNotBlank()
        "google" -> AgentConfig.googleKey.isNotBlank()
        "openrouter" -> AgentConfig.openrouterKey.isNotBlank()
        "ollama" -> true
        "custom" -> AgentConfig.customKey.isNotBlank()
        else -> false
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(
        modifier = Modifier.fillMaxSize().background(BG)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().background(SURFACE).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(10.dp).background(if (hasApiKey) GREEN else RED, CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("OpenClaw Chat", color = TEXT, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text(
                    "${AgentConfig.activeProvider} • ${config.model}",
                    color = TEXT2, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                )
            }
            // Clear chat
            if (messages.isNotEmpty()) {
                IconButton(onClick = { messages.clear() }) {
                    Icon(Icons.Default.Delete, "Clear", tint = TEXT2, modifier = Modifier.size(20.dp))
                }
            }
        }

        // No API key warning
        if (!hasApiKey) {
            Card(
                colors = CardDefaults.cardColors(containerColor = RED.copy(alpha = 0.15f)),
                shape = RoundedCornerShape(0.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "No API key set. Go to Settings → API Keys to add your ${AgentConfig.activeProvider} key.",
                    color = RED, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(12.dp)
                )
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
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 60.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("OpenClaw", color = TEXT2, fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(8.dp))
                        Text("AI with full device control", color = BORDER, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(24.dp))

                        // Quick prompts
                        listOf(
                            "Read my notifications",
                            "What's on my screen right now?",
                            "Open WhatsApp",
                            "What's my battery level?"
                        ).forEach { prompt ->
                            OutlinedButton(
                                onClick = {
                                    input = prompt
                                },
                                modifier = Modifier.fillMaxWidth(0.85f).padding(vertical = 3.dp),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, BORDER),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = TEXT2)
                            ) {
                                Text(prompt, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            items(messages) { msg ->
                MessageBubble(msg)
            }

            if (isLoading) {
                item {
                    Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = CYAN, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Thinking...", color = TEXT2, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            }
        }

        // Input bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SURFACE)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Message OpenClaw...", color = Color(0xFF484F58), fontFamily = FontFamily.Monospace, fontSize = 14.sp) },
                modifier = Modifier.weight(1f),
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = TEXT),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CYAN,
                    unfocusedBorderColor = BORDER,
                    cursorColor = CYAN,
                    focusedContainerColor = Color(0xFF010409),
                    unfocusedContainerColor = Color(0xFF010409)
                ),
                shape = RoundedCornerShape(12.dp),
                maxLines = 4,
                enabled = !isLoading
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = {
                    if (input.isBlank() || isLoading || !hasApiKey) return@IconButton
                    val userMsg = input.trim()
                    input = ""
                    messages.add(ChatMessage("user", userMsg))

                    scope.launch {
                        isLoading = true
                        val systemPrompt = """You are OpenClaw, an AI assistant running natively on an Android device.
You have direct control over the device through tools. You can read the screen, tap buttons, type text, open apps, and read notifications.
When the user asks you to do something on their phone, use the available tools to accomplish it.
Be concise and action-oriented. Execute tasks, don't just describe how to do them.
Respond in the same language as the user."""

                        val response = agentLoop.run(config, userMsg, systemPrompt)
                        messages.add(ChatMessage("assistant", response))
                        isLoading = false
                    }
                },
                enabled = input.isNotBlank() && !isLoading && hasApiKey,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (input.isNotBlank() && hasApiKey) CYAN else BORDER)
            ) {
                Icon(Icons.Default.Send, "Send", tint = if (input.isNotBlank() && hasApiKey) Color.White else TEXT2, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
fun MessageBubble(msg: ChatMessage) {
    val isUser = msg.role == "user"
    val bubbleColor = if (isUser) CYAN.copy(alpha = 0.15f) else SURFACE
    val textColor = if (isUser) TEXT else Color(0xFFC9D1D9)
    val align = if (isUser) Arrangement.End else Arrangement.Start

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = align
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = bubbleColor),
            shape = RoundedCornerShape(
                topStart = 12.dp, topEnd = 12.dp,
                bottomStart = if (isUser) 12.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 12.dp
            ),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                if (!isUser) {
                    Text("OpenClaw", color = CYAN, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(2.dp))
                }
                Text(
                    msg.content,
                    color = textColor,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 19.sp
                )
            }
        }
    }
}
