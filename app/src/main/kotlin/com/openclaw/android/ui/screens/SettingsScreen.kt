package com.openclaw.android.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openclaw.android.ai.AgentConfig
import com.openclaw.android.ai.ModelRegistry
import com.openclaw.android.util.ServiceState
import kotlinx.coroutines.launch

private val BG = Color(0xFF0D1117)
private val SURFACE = Color(0xFF161B22)
private val BORDER = Color(0xFF30363D)
private val CYAN = Color(0xFF58A6FF)
private val GREEN = Color(0xFF3FB950)
private val RED = Color(0xFFF85149)
private val TEXT = Color(0xFFF0F6FC)
private val TEXT2 = Color(0xFF8B949E)

private val ALL_PROVIDERS = listOf(
    "anthropic" to "Anthropic Claude",
    "openai" to "OpenAI",
    "minimax" to "MiniMax",
    "google" to "Google Gemini",
    "openrouter" to "OpenRouter",
    "deepseek" to "DeepSeek",
    "mistral" to "Mistral AI",
    "groq" to "Groq",
    "xai" to "xAI (Grok)",
    "together" to "Together AI",
    "fireworks" to "Fireworks AI",
    "ollama" to "Ollama (Local)",
    "custom" to "Custom API",
)

@Composable
fun SettingsScreen(
    onStartService: () -> Unit = {},
    onStopService: () -> Unit = {}
) {
    val isRunning by ServiceState.isRunning.collectAsState()
    var activeProvider by remember { mutableStateOf(AgentConfig.activeProvider) }
    var selectedModel by remember { mutableStateOf(AgentConfig.getModelForProvider(AgentConfig.activeProvider)) }
    var tokenInput by remember { mutableStateOf("") }
    var providerDropdown by remember { mutableStateOf(false) }
    var modelDropdown by remember { mutableStateOf(false) }
    var checkingUpdate by remember { mutableStateOf(false) }
    // Saved tokens list - reload from AgentConfig
    val savedTokens = remember { mutableStateListOf<Pair<String, String>>() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Load saved tokens on init
    LaunchedEffect(Unit) {
        savedTokens.clear()
        ALL_PROVIDERS.forEach { (id, name) ->
            val key = AgentConfig.getKeyForProvider(id)
            if (key.isNotBlank()) savedTokens.add(id to key)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(BG).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            Text("Settings", color = TEXT, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(8.dp))
        }

        // ── SERVICE TOGGLE ──
        item { SectionLabel("SERVICE") }
        item {
            Button(
                onClick = { if (isRunning) onStopService() else onStartService() },
                colors = ButtonDefaults.buttonColors(containerColor = if (isRunning) RED else GREEN),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Icon(if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (isRunning) "Stop Service" else "Start Service", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }

        // ── LLM PROVIDER + MODEL ──
        item { SectionLabel("LLM CONFIGURATION") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SURFACE), shape = RoundedCornerShape(10.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // Provider dropdown
                    Text("Provider", color = TEXT2, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(4.dp))
                    Box {
                        OutlinedButton(
                            onClick = { providerDropdown = true },
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, BORDER),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TEXT)
                        ) {
                            Text(ALL_PROVIDERS.find { it.first == activeProvider }?.second ?: activeProvider,
                                fontFamily = FontFamily.Monospace, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Icon(Icons.Default.ArrowDropDown, null)
                        }
                        DropdownMenu(expanded = providerDropdown, onDismissRequest = { providerDropdown = false },
                            modifier = Modifier.background(Color(0xFF1C2333))) {
                            ALL_PROVIDERS.forEach { (id, name) ->
                                DropdownMenuItem(
                                    text = { Text(name, color = if (id == activeProvider) CYAN else TEXT, fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
                                    onClick = {
                                        activeProvider = id; AgentConfig.activeProvider = id
                                        selectedModel = AgentConfig.getModelForProvider(id)
                                        tokenInput = ""
                                        providerDropdown = false
                                    },
                                    leadingIcon = { if (id == activeProvider) Icon(Icons.Default.Check, null, tint = CYAN, modifier = Modifier.size(16.dp)) }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Model DROPDOWN
                    Text("Model", color = TEXT2, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(4.dp))
                    val providerModels = ModelRegistry.getModelsForProvider(activeProvider)
                    if (providerModels.isNotEmpty()) {
                        Box {
                            OutlinedButton(
                                onClick = { modelDropdown = true },
                                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, BORDER),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = TEXT)
                            ) {
                                val displayName = providerModels.find { it.id == selectedModel }?.displayName ?: selectedModel
                                Text(displayName.ifBlank { "Select model..." }, fontFamily = FontFamily.Monospace, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                Icon(Icons.Default.ArrowDropDown, null)
                            }
                            DropdownMenu(expanded = modelDropdown, onDismissRequest = { modelDropdown = false },
                                modifier = Modifier.background(Color(0xFF1C2333)).heightIn(max = 300.dp)) {
                                providerModels.forEach { model ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(model.displayName, color = if (model.id == selectedModel) CYAN else TEXT, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                                                Text(model.id, color = TEXT2, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                                            }
                                        },
                                        onClick = {
                                            selectedModel = model.id
                                            AgentConfig.setModelForProvider(activeProvider, model.id)
                                            modelDropdown = false
                                        }
                                    )
                                }
                            }
                        }
                    } else {
                        // Custom provider — free text
                        OutlinedTextField(
                            value = selectedModel, onValueChange = { selectedModel = it; AgentConfig.setModelForProvider(activeProvider, it) },
                            label = { Text("Model ID", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = TEXT),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CYAN, unfocusedBorderColor = BORDER, cursorColor = CYAN, focusedLabelColor = CYAN, unfocusedLabelColor = TEXT2)
                        )
                    }

                    if (activeProvider == "custom") {
                        Spacer(Modifier.height(8.dp))
                        var baseUrl by remember { mutableStateOf(AgentConfig.customBaseUrl) }
                        OutlinedTextField(
                            value = baseUrl, onValueChange = { baseUrl = it; AgentConfig.customBaseUrl = it },
                            label = { Text("Base URL", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = TEXT),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CYAN, unfocusedBorderColor = BORDER, cursorColor = CYAN, focusedLabelColor = CYAN, unfocusedLabelColor = TEXT2)
                        )
                    }
                }
            }
        }

        // ── API TOKEN INPUT ──
        if (activeProvider != "ollama") {
            item { SectionLabel("API TOKEN") }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = SURFACE), shape = RoundedCornerShape(10.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = tokenInput, onValueChange = { tokenInput = it },
                                placeholder = { Text("Paste API token here...", color = Color(0xFF484F58), fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                                modifier = Modifier.weight(1f), singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = TEXT),
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CYAN, unfocusedBorderColor = BORDER, cursorColor = CYAN, focusedContainerColor = Color(0xFF010409), unfocusedContainerColor = Color(0xFF010409)),
                                shape = RoundedCornerShape(8.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    if (tokenInput.isNotBlank()) {
                                        AgentConfig.setKeyForProvider(activeProvider, tokenInput)
                                        // Update saved list
                                        savedTokens.removeAll { it.first == activeProvider }
                                        savedTokens.add(0, activeProvider to tokenInput)
                                        tokenInput = ""
                                        Toast.makeText(context, "Token saved for ${ALL_PROVIDERS.find { it.first == activeProvider }?.second}", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                enabled = tokenInput.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = if (tokenInput.isNotBlank()) GREEN else BORDER),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                            ) {
                                Text("Input", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // ── SAVED TOKENS LIST ──
        if (savedTokens.isNotEmpty()) {
            item { SectionLabel("SAVED TOKENS") }
            items(savedTokens) { (providerId, token) ->
                val providerName = ALL_PROVIDERS.find { it.first == providerId }?.second ?: providerId
                SavedTokenRow(providerName, providerId, token, context) { removedId ->
                    AgentConfig.setKeyForProvider(removedId, "")
                    savedTokens.removeAll { it.first == removedId }
                }
            }
        }

        // ── NOTIFICATIONS ──
        item { SectionLabel("NOTIFICATIONS") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SURFACE), shape = RoundedCornerShape(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("Push Notifications", color = TEXT, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                        Text("Agent task completions & errors", color = TEXT2, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    var notif by remember { mutableStateOf(AgentConfig.pushNotificationsEnabled) }
                    Switch(checked = notif, onCheckedChange = { notif = it; AgentConfig.pushNotificationsEnabled = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = GREEN, checkedThumbColor = TEXT))
                }
            }
        }

        // ── UPDATES ──
        item { SectionLabel("UPDATES") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SURFACE), shape = RoundedCornerShape(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("OpenClaw Android", color = TEXT, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                        Text("v0.9.0-alpha", color = TEXT2, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    Button(
                        onClick = {
                            checkingUpdate = true
                            scope.launch {
                                kotlinx.coroutines.delay(1500)
                                checkingUpdate = false
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/wayansuardyana-code/android-openclaw-native/releases")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                            }
                        },
                        enabled = !checkingUpdate, colors = ButtonDefaults.buttonColors(containerColor = CYAN), shape = RoundedCornerShape(8.dp)
                    ) {
                        if (checkingUpdate) Text("...", color = TEXT, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                        else { Icon(Icons.Default.SystemUpdate, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Check Updates", fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                    }
                }
            }
        }

        // ── ABOUT ──
        item { SectionLabel("ABOUT") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SURFACE), shape = RoundedCornerShape(10.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    AboutRow("Version", "0.9.0-alpha")
                    AboutRow("Tools", "22 (8 device + 9 utility + 5 service)")
                    AboutRow("Providers", "13")
                    AboutRow("Device", android.os.Build.MODEL)
                    AboutRow("Android", "API ${android.os.Build.VERSION.SDK_INT}")
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun SavedTokenRow(providerName: String, providerId: String, token: String, context: Context, onRemove: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = SURFACE), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(providerName, color = CYAN, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text(
                    if (visible) token else token.take(8) + "•".repeat(minOf(20, token.length - 8).coerceAtLeast(0)),
                    color = TEXT2, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1
                )
            }
            // Eye toggle
            IconButton(onClick = { visible = !visible }, modifier = Modifier.size(32.dp)) {
                Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Toggle", tint = TEXT2, modifier = Modifier.size(16.dp))
            }
            // Copy
            IconButton(onClick = {
                val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clip.setPrimaryClip(ClipData.newPlainText("token", token))
                Toast.makeText(context, "Copied!", Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.ContentCopy, "Copy", tint = TEXT2, modifier = Modifier.size(16.dp))
            }
            // Delete
            IconButton(onClick = { onRemove(providerId) }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Delete, "Remove", tint = Color(0xFFF85149), modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = TEXT2, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TEXT2, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Text(value, color = TEXT, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}
