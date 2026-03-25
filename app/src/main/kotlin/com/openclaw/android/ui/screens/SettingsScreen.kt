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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openclaw.android.BuildConfig
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
    "anthropic" to "Anthropic Claude", "openai" to "OpenAI", "minimax" to "MiniMax",
    "google" to "Google Gemini", "gemini" to "Gemini (AI Studio)",
    "kimi" to "Kimi", "moonshot" to "Moonshot AI",
    "openrouter" to "OpenRouter", "deepseek" to "DeepSeek",
    "mistral" to "Mistral AI", "groq" to "Groq", "xai" to "xAI (Grok)",
    "together" to "Together AI", "fireworks" to "Fireworks AI",
    "ollama" to "Ollama (Local)", "custom" to "Custom API",
)

@Composable
fun SettingsScreen(onStartService: () -> Unit = {}, onStopService: () -> Unit = {}) {
    val isRunning by ServiceState.isRunning.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Only LLMs with saved tokens (NO ollama default)
    val savedLlms = remember { mutableStateListOf<Pair<String, String>>() }
    var activeProvider by remember { mutableStateOf(AgentConfig.activeProvider) }
    var selectedModel by remember { mutableStateOf(AgentConfig.getModelForProvider(AgentConfig.activeProvider)) }

    LaunchedEffect(Unit) {
        savedLlms.clear()
        ALL_PROVIDERS.forEach { (id, _) ->
            val key = AgentConfig.getKeyForProvider(id)
            if (key.isNotBlank()) savedLlms.add(id to key)
        }
    }

    var showAddLlm by remember { mutableStateOf(false) }
    var addProvider by remember { mutableStateOf("") }
    var addToken by remember { mutableStateOf("") }
    var addDropdown by remember { mutableStateOf(false) }
    var showModelDropdown by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(BG).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item { Text("Settings", color = TEXT, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace); Spacer(Modifier.height(8.dp)) }

        // ── SERVICE ──
        item { SLabel("SERVICE") }
        item {
            Button(onClick = { if (isRunning) onStopService() else onStartService() },
                colors = ButtonDefaults.buttonColors(containerColor = if (isRunning) RED else GREEN),
                shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Icon(if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (isRunning) "Stop Service" else "Start Service", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }

        // ── LLM PROVIDERS (dynamic) ──
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                SLabel("LLM PROVIDERS")
                IconButton(onClick = { showAddLlm = true; addProvider = ""; addToken = "" }) { Icon(Icons.Default.Add, "Add", tint = CYAN, modifier = Modifier.size(20.dp)) }
            }
        }

        if (savedLlms.isEmpty()) {
            item { Card(colors = CardDefaults.cardColors(containerColor = SURFACE), shape = RoundedCornerShape(10.dp)) {
                Text("No LLM configured. Tap + to add.", color = TEXT2, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(16.dp))
            }}
        }

        items(savedLlms.toList()) { (id, token) ->
            val name = ALL_PROVIDERS.find { it.first == id }?.second ?: id
            val isActive = activeProvider == id
            TokenCard(name, token, isActive, context,
                onTap = { activeProvider = id; AgentConfig.activeProvider = id; selectedModel = AgentConfig.getModelForProvider(id) },
                onRemove = {
                    AgentConfig.setKeyForProvider(id, ""); savedLlms.removeAll { it.first == id }
                    if (activeProvider == id) { activeProvider = savedLlms.firstOrNull()?.first ?: ""; AgentConfig.activeProvider = activeProvider }
                })
        }

        // Model selector — shows ALL models from ALL providers with keys
        if (savedLlms.isNotEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = SURFACE), shape = RoundedCornerShape(10.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Model", color = TEXT2, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Text("Pick model → auto-sets active provider", color = TEXT2, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(4.dp))
                        // Collect all models from all providers that have saved keys
                        val allModels = savedLlms.flatMap { (providerId, _) ->
                            ModelRegistry.getModelsForProvider(providerId).map { it to providerId }
                        }
                        if (allModels.isNotEmpty()) {
                            Box {
                                OutlinedButton(onClick = { showModelDropdown = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, BORDER), colors = ButtonDefaults.outlinedButtonColors(contentColor = TEXT)) {
                                    val modelLabel = allModels.find { it.first.id == selectedModel && it.second == activeProvider }?.first?.displayName ?: selectedModel.ifBlank { "Select..." }
                                    Text("$activeProvider/$modelLabel", fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                    Icon(Icons.Default.ArrowDropDown, null)
                                }
                                DropdownMenu(expanded = showModelDropdown, onDismissRequest = { showModelDropdown = false }, modifier = Modifier.background(Color(0xFF1C2333)).heightIn(max = 400.dp)) {
                                    // Group by provider
                                    var lastProvider = ""
                                    allModels.forEach { (model, providerId) ->
                                        if (providerId != lastProvider) {
                                            lastProvider = providerId
                                            val label = ALL_PROVIDERS.find { it.first == providerId }?.second ?: providerId
                                            DropdownMenuItem(text = { Text("── $label ──", color = TEXT2, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }, onClick = {}, enabled = false)
                                        }
                                        DropdownMenuItem(
                                            text = { Text("${providerId}/${model.displayName}", color = if (model.id == selectedModel && activeProvider == providerId) CYAN else TEXT, fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
                                            onClick = {
                                                selectedModel = model.id
                                                activeProvider = providerId
                                                AgentConfig.activeProvider = providerId
                                                AgentConfig.setModelForProvider(providerId, model.id)
                                                showModelDropdown = false
                                            }
                                        )
                                    }
                                }
                            }
                        } else {
                            OutlinedTextField(value = selectedModel, onValueChange = { selectedModel = it; AgentConfig.setModelForProvider(activeProvider, it) },
                                placeholder = { Text("model-id", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color(0xFF484F58)) },
                                modifier = Modifier.fillMaxWidth(), singleLine = true,
                                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = TEXT),
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CYAN, unfocusedBorderColor = BORDER, cursorColor = CYAN))
                        }

                        if (activeProvider == "custom") {
                            Spacer(Modifier.height(8.dp))
                            var baseUrl by remember { mutableStateOf(AgentConfig.customBaseUrl) }
                            OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it; AgentConfig.customBaseUrl = it },
                                label = { Text("Base URL", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                                modifier = Modifier.fillMaxWidth(), singleLine = true,
                                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = TEXT),
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CYAN, unfocusedBorderColor = BORDER, cursorColor = CYAN, focusedLabelColor = CYAN, unfocusedLabelColor = TEXT2))
                        }
                    }
                }
            }
        }

        // ── SHIZUKU (ADB-level power) ──
        item { SLabel("SHIZUKU (ADB ACCESS)") }
        item {
            var shizukuStatus by remember { mutableStateOf(com.openclaw.android.util.ShizukuHelper.getStatus()) }
            val isActive = shizukuStatus.contains("Active")
            Card(colors = CardDefaults.cardColors(containerColor = SURFACE), shape = RoundedCornerShape(10.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(if (isActive) GREEN else Color(0xFFD29922), RoundedCornerShape(4.dp)))
                        Spacer(Modifier.width(8.dp))
                        Text(shizukuStatus, color = if (isActive) GREEN else TEXT2, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!isActive) {
                            Button(onClick = {
                                com.openclaw.android.util.ShizukuHelper.requestPermission()
                                shizukuStatus = com.openclaw.android.util.ShizukuHelper.getStatus()
                            }, colors = ButtonDefaults.buttonColors(containerColor = CYAN), shape = RoundedCornerShape(8.dp)) {
                                Text("Grant Permission", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            }
                        }
                        Button(onClick = {
                            com.openclaw.android.util.ShizukuHelper.autoEnableAll()
                            shizukuStatus = com.openclaw.android.util.ShizukuHelper.getStatus()
                            android.widget.Toast.makeText(context, "Accessibility + Notifications enabled!", android.widget.Toast.LENGTH_SHORT).show()
                        }, enabled = isActive, colors = ButtonDefaults.buttonColors(containerColor = if (isActive) GREEN else BORDER), shape = RoundedCornerShape(8.dp)) {
                            Text("Enable All Services", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Shizuku enables auto-enable accessibility after updates.", color = TEXT2, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        // ── NOTIFICATIONS ──
        item { SLabel("NOTIFICATIONS") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SURFACE), shape = RoundedCornerShape(10.dp)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column { Text("Push Notifications", color = TEXT, fontSize = 14.sp, fontFamily = FontFamily.Monospace) }
                    var n by remember { mutableStateOf(AgentConfig.pushNotificationsEnabled) }
                    Switch(checked = n, onCheckedChange = { n = it; AgentConfig.pushNotificationsEnabled = it }, colors = SwitchDefaults.colors(checkedTrackColor = GREEN, checkedThumbColor = TEXT))
                }
            }
        }

        // ── ABOUT ──
        item { SLabel("ABOUT") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SURFACE), shape = RoundedCornerShape(10.dp)) {
                Column(Modifier.padding(16.dp)) {
                    AR("Version", BuildConfig.VERSION_NAME); AR("Tools", "31"); AR("Device", android.os.Build.MODEL); AR("Android", "API ${android.os.Build.VERSION.SDK_INT}")
                    Spacer(Modifier.height(8.dp))

                    var updateStatus by remember { mutableStateOf("") }
                    var checking by remember { mutableStateOf(false) }

                    Button(onClick = {
                        checking = true; updateStatus = "Checking..."
                        scope.launch {
                            val update = com.openclaw.android.util.AppUpdater.checkForUpdate()
                            if (update == null) {
                                updateStatus = "Failed to check"; checking = false
                            } else if (!update.isNewer) {
                                updateStatus = "Up to date (${update.version})"; checking = false
                            } else {
                                updateStatus = "Downloading ${update.version}..."
                                com.openclaw.android.util.AppUpdater.downloadAndInstall(context, update.downloadUrl, update.version)
                                checking = false
                            }
                        }
                    }, enabled = !checking,
                        colors = ButtonDefaults.buttonColors(containerColor = CYAN), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.SystemUpdate, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                        Text(if (checking) "Checking..." else "Check Updates", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                    if (updateStatus.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(updateStatus, color = if (updateStatus.contains("Up to date")) GREEN else CYAN, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }

    // ── ADD LLM DIALOG ──
    if (showAddLlm) {
        AlertDialog(onDismissRequest = { showAddLlm = false }, containerColor = SURFACE,
            title = { Text("Add LLM Provider", color = TEXT, fontFamily = FontFamily.Monospace) },
            text = {
                Column {
                    Box {
                        OutlinedButton(onClick = { addDropdown = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, BORDER), colors = ButtonDefaults.outlinedButtonColors(contentColor = TEXT)) {
                            Text(ALL_PROVIDERS.find { it.first == addProvider }?.second ?: "Select provider...", fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.weight(1f)); Icon(Icons.Default.ArrowDropDown, null)
                        }
                        DropdownMenu(expanded = addDropdown, onDismissRequest = { addDropdown = false }, modifier = Modifier.background(Color(0xFF1C2333)).heightIn(max = 300.dp)) {
                            ALL_PROVIDERS.filter { p -> savedLlms.none { it.first == p.first } }.forEach { (id, name) ->
                                DropdownMenuItem(text = { Text(name, color = TEXT, fontFamily = FontFamily.Monospace, fontSize = 13.sp) }, onClick = { addProvider = id; addDropdown = false })
                            }
                        }
                    }
                    if (addProvider.isNotBlank() && addProvider != "ollama") {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = addToken, onValueChange = { addToken = it },
                            placeholder = { Text("Paste API token...", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color(0xFF484F58)) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation(),
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = TEXT),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CYAN, unfocusedBorderColor = BORDER, cursorColor = CYAN))
                    }
                }
            },
            confirmButton = { TextButton(onClick = {
                if (addProvider.isNotBlank() && (addToken.isNotBlank() || addProvider == "ollama")) {
                    AgentConfig.setKeyForProvider(addProvider, addToken); savedLlms.add(addProvider to addToken)
                    if (savedLlms.size == 1) { activeProvider = addProvider; AgentConfig.activeProvider = addProvider; selectedModel = AgentConfig.getModelForProvider(addProvider) }
                    showAddLlm = false; Toast.makeText(context, "Added!", Toast.LENGTH_SHORT).show()
                }
            }) { Text("Add", color = GREEN, fontFamily = FontFamily.Monospace) } },
            dismissButton = { TextButton(onClick = { showAddLlm = false }) { Text("Cancel", color = TEXT2, fontFamily = FontFamily.Monospace) } }
        )
    }
}

@Composable private fun TokenCard(name: String, token: String, isActive: Boolean, context: Context, onTap: () -> Unit, onRemove: () -> Unit) {
    var vis by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = if (isActive) Color(0xFF1C2333) else SURFACE), shape = RoundedCornerShape(8.dp),
        border = if (isActive) BorderStroke(1.dp, CYAN) else null, modifier = Modifier.fillMaxWidth().clickable { onTap() }) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (isActive) { Box(Modifier.size(8.dp).background(GREEN, RoundedCornerShape(4.dp))); Spacer(Modifier.width(8.dp)) }
            Column(Modifier.weight(1f)) {
                Text(name, color = if (isActive) CYAN else TEXT, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                if (token.isNotBlank()) Text(if (vis) token else token.take(6) + "•".repeat(minOf(12, token.length - 6).coerceAtLeast(0)), color = TEXT2, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
            }
            IconButton(onClick = { vis = !vis }, Modifier.size(28.dp)) { Icon(if (vis) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, tint = TEXT2, modifier = Modifier.size(14.dp)) }
            IconButton(onClick = { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("t", token)); Toast.makeText(context, "Copied!", Toast.LENGTH_SHORT).show() }, Modifier.size(28.dp)) { Icon(Icons.Default.ContentCopy, null, tint = TEXT2, modifier = Modifier.size(14.dp)) }
            IconButton(onClick = onRemove, Modifier.size(28.dp)) { Icon(Icons.Default.Delete, null, tint = RED, modifier = Modifier.size(14.dp)) }
        }
    }
}
@Composable private fun SLabel(t: String) { Text(t, color = TEXT2, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp, modifier = Modifier.padding(top = 8.dp)) }
@Composable private fun AR(l: String, v: String) { Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(l, color = TEXT2, fontSize = 12.sp, fontFamily = FontFamily.Monospace); Text(v, color = TEXT, fontSize = 12.sp, fontFamily = FontFamily.Monospace) } }
