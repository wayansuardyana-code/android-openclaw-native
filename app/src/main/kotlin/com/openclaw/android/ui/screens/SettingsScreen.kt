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
private val ORANGE = Color(0xFFD29922)
private val TEXT = Color(0xFFF0F6FC)
private val TEXT2 = Color(0xFF8B949E)

// All available providers (for dropdown when adding)
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

// Available services (for "+" button) — Triple(id, name, description)
data class ServiceDef(val id: String, val name: String, val desc: String)
private val AVAILABLE_SERVICES = listOf(
    ServiceDef("github", "GitHub", "Repos, issues, PRs, code search"),
    ServiceDef("vercel", "Vercel", "Deployments, projects, domains"),
    ServiceDef("supabase", "Supabase", "Database queries via PostgREST"),
    ServiceDef("google_workspace", "Google Workspace", "Drive, Sheets, Gmail, Calendar"),
    ServiceDef("ssh", "SSH Remote", "Connect to remote servers"),
    ServiceDef("postgres", "PostgreSQL", "SQL queries via SSH tunnel"),
    ServiceDef("cloudflare", "Cloudflare", "DNS, Workers, Pages"),
    ServiceDef("notion", "Notion", "Pages, databases, blocks"),
    ServiceDef("linear", "Linear", "Issues, projects, cycles"),
    ServiceDef("slack", "Slack", "Messages, channels"),
    ServiceDef("discord", "Discord", "Bot, messages, channels"),
    ServiceDef("telegram", "Telegram Bot", "Messages, groups"),
    ServiceDef("stripe", "Stripe", "Payments, customers"),
    ServiceDef("resend", "Resend", "Email sending"),
    ServiceDef("upstash", "Upstash Redis", "Key-value cache"),
)

@Composable
fun SettingsScreen(
    onStartService: () -> Unit = {},
    onStopService: () -> Unit = {}
) {
    val isRunning by ServiceState.isRunning.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Dynamic LLM list — only shows providers with saved tokens
    val savedLlms = remember { mutableStateListOf<Pair<String, String>>() } // id to token
    var activeProvider by remember { mutableStateOf(AgentConfig.activeProvider) }

    // Dynamic services list
    val connectedServices = remember { mutableStateListOf<String>() }

    // Load saved data
    LaunchedEffect(Unit) {
        savedLlms.clear()
        ALL_PROVIDERS.forEach { (id, _) ->
            val key = AgentConfig.getKeyForProvider(id)
            if (key.isNotBlank() || id == "ollama") savedLlms.add(id to key)
        }
        connectedServices.clear()
        AVAILABLE_SERVICES.forEach { svc ->
            val key = AgentConfig.getKeyForProvider(svc.id)
            if (key.isNotBlank()) connectedServices.add(svc.id)
        }
    }

    // Add LLM dialog
    var showAddLlm by remember { mutableStateOf(false) }
    var addLlmProvider by remember { mutableStateOf("") }
    var addLlmToken by remember { mutableStateOf("") }
    var addLlmDropdown by remember { mutableStateOf(false) }

    // Add service dialog
    var showAddService by remember { mutableStateOf(false) }
    var addServiceId by remember { mutableStateOf("") }
    var addServiceToken by remember { mutableStateOf("") }
    var addServiceDropdown by remember { mutableStateOf(false) }

    // Model selector
    var showModelDropdown by remember { mutableStateOf(false) }
    var selectedModel by remember { mutableStateOf(AgentConfig.getModelForProvider(activeProvider)) }

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

        // ── LLM PROVIDERS (dynamic) ──
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("LLM PROVIDERS")
                IconButton(onClick = { showAddLlm = true; addLlmProvider = ""; addLlmToken = "" }) {
                    Icon(Icons.Default.Add, "Add LLM", tint = CYAN, modifier = Modifier.size(20.dp))
                }
            }
        }

        if (savedLlms.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = SURFACE), shape = RoundedCornerShape(10.dp)) {
                    Text("No LLM configured. Tap + to add a provider.", color = TEXT2, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(16.dp))
                }
            }
        }

        items(savedLlms) { (id, token) ->
            val name = ALL_PROVIDERS.find { it.first == id }?.second ?: id
            val isActive = activeProvider == id
            SavedTokenCard(
                name = name,
                token = token,
                isActive = isActive,
                context = context,
                onActivate = {
                    activeProvider = id
                    AgentConfig.activeProvider = id
                    selectedModel = AgentConfig.getModelForProvider(id)
                },
                onRemove = {
                    AgentConfig.setKeyForProvider(id, "")
                    savedLlms.removeAll { it.first == id }
                    if (activeProvider == id) { activeProvider = savedLlms.firstOrNull()?.first ?: ""; AgentConfig.activeProvider = activeProvider }
                }
            )
        }

        // Model selector (only if active provider set)
        if (activeProvider.isNotBlank()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = SURFACE), shape = RoundedCornerShape(10.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Model", color = TEXT2, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(4.dp))
                        val models = ModelRegistry.getModelsForProvider(activeProvider)
                        if (models.isNotEmpty()) {
                            Box {
                                OutlinedButton(onClick = { showModelDropdown = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, BORDER), colors = ButtonDefaults.outlinedButtonColors(contentColor = TEXT)) {
                                    Text(models.find { it.id == selectedModel }?.displayName ?: selectedModel.ifBlank { "Select..." }, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                    Icon(Icons.Default.ArrowDropDown, null)
                                }
                                DropdownMenu(expanded = showModelDropdown, onDismissRequest = { showModelDropdown = false }, modifier = Modifier.background(Color(0xFF1C2333)).heightIn(max = 300.dp)) {
                                    models.forEach { m ->
                                        DropdownMenuItem(text = { Text(m.displayName, color = if (m.id == selectedModel) CYAN else TEXT, fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
                                            onClick = { selectedModel = m.id; AgentConfig.setModelForProvider(activeProvider, m.id); showModelDropdown = false })
                                    }
                                }
                            }
                        } else {
                            OutlinedTextField(value = selectedModel, onValueChange = { selectedModel = it; AgentConfig.setModelForProvider(activeProvider, it) },
                                placeholder = { Text("model-id", color = Color(0xFF484F58), fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                                modifier = Modifier.fillMaxWidth(), singleLine = true,
                                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = TEXT),
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CYAN, unfocusedBorderColor = BORDER, cursorColor = CYAN, focusedLabelColor = CYAN, unfocusedLabelColor = TEXT2))
                        }
                    }
                }
            }
        }

        // ── CONNECTED SERVICES (dynamic) ──
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("SERVICES")
                IconButton(onClick = { showAddService = true; addServiceId = ""; addServiceToken = "" }) {
                    Icon(Icons.Default.Add, "Add Service", tint = CYAN, modifier = Modifier.size(20.dp))
                }
            }
        }

        if (connectedServices.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = SURFACE), shape = RoundedCornerShape(10.dp)) {
                    Text("No services connected. Tap + to add.", color = TEXT2, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(16.dp))
                }
            }
        }

        items(connectedServices) { id ->
            val info = AVAILABLE_SERVICES.find { it.id == id }
            val name = info?.name ?: id
            val token = AgentConfig.getKeyForProvider(id)
            SavedTokenCard(name = name, token = token, isActive = false, context = context,
                onActivate = {}, onRemove = { AgentConfig.setKeyForProvider(id, ""); connectedServices.remove(id) })
        }

        // ── NOTIFICATIONS ──
        item { SectionLabel("NOTIFICATIONS") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SURFACE), shape = RoundedCornerShape(10.dp)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column { Text("Push Notifications", color = TEXT, fontSize = 14.sp, fontFamily = FontFamily.Monospace); Text("Agent completions & errors", color = TEXT2, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                    var notif by remember { mutableStateOf(AgentConfig.pushNotificationsEnabled) }
                    Switch(checked = notif, onCheckedChange = { notif = it; AgentConfig.pushNotificationsEnabled = it }, colors = SwitchDefaults.colors(checkedTrackColor = GREEN, checkedThumbColor = TEXT))
                }
            }
        }

        // ── UPDATES + ABOUT ──
        item { SectionLabel("ABOUT") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SURFACE), shape = RoundedCornerShape(10.dp)) {
                Column(Modifier.padding(16.dp)) {
                    AboutRow("Version", "1.0.1-alpha"); AboutRow("Tools", "28"); AboutRow("Device", android.os.Build.MODEL); AboutRow("Android", "API ${android.os.Build.VERSION.SDK_INT}")
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        scope.launch { kotlinx.coroutines.delay(1000); context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/wayansuardyana-code/android-openclaw-native/releases")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) }
                    }, colors = ButtonDefaults.buttonColors(containerColor = CYAN), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.SystemUpdate, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Check Updates", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }

    // ── ADD LLM DIALOG ──
    if (showAddLlm) {
        AlertDialog(
            onDismissRequest = { showAddLlm = false },
            containerColor = SURFACE,
            title = { Text("Add LLM Provider", color = TEXT, fontFamily = FontFamily.Monospace) },
            text = {
                Column {
                    Box {
                        OutlinedButton(onClick = { addLlmDropdown = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, BORDER), colors = ButtonDefaults.outlinedButtonColors(contentColor = TEXT)) {
                            Text(ALL_PROVIDERS.find { it.first == addLlmProvider }?.second ?: "Select provider...", fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Icon(Icons.Default.ArrowDropDown, null)
                        }
                        DropdownMenu(expanded = addLlmDropdown, onDismissRequest = { addLlmDropdown = false }, modifier = Modifier.background(Color(0xFF1C2333)).heightIn(max = 300.dp)) {
                            ALL_PROVIDERS.filter { p -> savedLlms.none { it.first == p.first } }.forEach { (id, name) ->
                                DropdownMenuItem(text = { Text(name, color = TEXT, fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
                                    onClick = { addLlmProvider = id; addLlmDropdown = false })
                            }
                        }
                    }
                    if (addLlmProvider.isNotBlank() && addLlmProvider != "ollama") {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = addLlmToken, onValueChange = { addLlmToken = it },
                            placeholder = { Text("Paste API token...", color = Color(0xFF484F58), fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation(),
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = TEXT),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CYAN, unfocusedBorderColor = BORDER, cursorColor = CYAN))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (addLlmProvider.isNotBlank() && (addLlmToken.isNotBlank() || addLlmProvider == "ollama")) {
                        AgentConfig.setKeyForProvider(addLlmProvider, addLlmToken)
                        savedLlms.add(addLlmProvider to addLlmToken)
                        if (savedLlms.size == 1) { activeProvider = addLlmProvider; AgentConfig.activeProvider = addLlmProvider; selectedModel = AgentConfig.getModelForProvider(addLlmProvider) }
                        showAddLlm = false
                        Toast.makeText(context, "Added!", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Add", color = GREEN, fontFamily = FontFamily.Monospace) }
            },
            dismissButton = { TextButton(onClick = { showAddLlm = false }) { Text("Cancel", color = TEXT2, fontFamily = FontFamily.Monospace) } }
        )
    }

    // ── ADD SERVICE DIALOG ──
    if (showAddService) {
        AlertDialog(
            onDismissRequest = { showAddService = false },
            containerColor = SURFACE,
            title = { Text("Connect Service", color = TEXT, fontFamily = FontFamily.Monospace) },
            text = {
                Column {
                    Box {
                        OutlinedButton(onClick = { addServiceDropdown = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, BORDER), colors = ButtonDefaults.outlinedButtonColors(contentColor = TEXT)) {
                            val svcName = AVAILABLE_SERVICES.find { it.id == addServiceId }?.name ?: "Select service..."
                            Text(svcName, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Icon(Icons.Default.ArrowDropDown, null)
                        }
                        DropdownMenu(expanded = addServiceDropdown, onDismissRequest = { addServiceDropdown = false }, modifier = Modifier.background(Color(0xFF1C2333)).heightIn(max = 300.dp)) {
                            AVAILABLE_SERVICES.filter { svc -> connectedServices.none { it == svc.id } }.forEach { svc ->
                                DropdownMenuItem(text = { Column { Text(svc.name, color = TEXT, fontFamily = FontFamily.Monospace, fontSize = 13.sp); Text(svc.desc, color = TEXT2, fontFamily = FontFamily.Monospace, fontSize = 10.sp) } },
                                    onClick = { addServiceId = svc.id; addServiceDropdown = false })
                            }
                        }
                    }
                    if (addServiceId.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = addServiceToken, onValueChange = { addServiceToken = it },
                            placeholder = { Text("API token / key...", color = Color(0xFF484F58), fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation(),
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = TEXT),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CYAN, unfocusedBorderColor = BORDER, cursorColor = CYAN))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (addServiceId.isNotBlank() && addServiceToken.isNotBlank()) {
                        AgentConfig.setKeyForProvider(addServiceId, addServiceToken)
                        connectedServices.add(addServiceId)
                        showAddService = false
                        Toast.makeText(context, "Connected!", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Connect", color = GREEN, fontFamily = FontFamily.Monospace) }
            },
            dismissButton = { TextButton(onClick = { showAddService = false }) { Text("Cancel", color = TEXT2, fontFamily = FontFamily.Monospace) } }
        )
    }
}

@Composable
private fun SavedTokenCard(name: String, token: String, isActive: Boolean, context: Context, onActivate: () -> Unit, onRemove: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = if (isActive) Color(0xFF1C2333) else SURFACE),
        shape = RoundedCornerShape(8.dp),
        border = if (isActive) BorderStroke(1.dp, CYAN) else null,
        modifier = Modifier.fillMaxWidth().clickable { onActivate() }
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (isActive) { Box(Modifier.size(8.dp).background(GREEN, RoundedCornerShape(4.dp))); Spacer(Modifier.width(8.dp)) }
            Column(Modifier.weight(1f)) {
                Text(name, color = if (isActive) CYAN else TEXT, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                if (token.isNotBlank()) {
                    Text(if (visible) token else token.take(8) + "•".repeat(minOf(16, token.length - 8).coerceAtLeast(0)),
                        color = TEXT2, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                }
            }
            IconButton(onClick = { visible = !visible }, modifier = Modifier.size(32.dp)) { Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Toggle", tint = TEXT2, modifier = Modifier.size(16.dp)) }
            IconButton(onClick = { val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager; clip.setPrimaryClip(ClipData.newPlainText("token", token)); Toast.makeText(context, "Copied!", Toast.LENGTH_SHORT).show() }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.ContentCopy, "Copy", tint = TEXT2, modifier = Modifier.size(16.dp)) }
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Delete, "Remove", tint = RED, modifier = Modifier.size(16.dp)) }
        }
    }
}

@Composable private fun SectionLabel(text: String) { Text(text, color = TEXT2, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp, modifier = Modifier.padding(top = 8.dp)) }
@Composable private fun AboutRow(label: String, value: String) { Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, color = TEXT2, fontSize = 12.sp, fontFamily = FontFamily.Monospace); Text(value, color = TEXT, fontSize = 12.sp, fontFamily = FontFamily.Monospace) } }
