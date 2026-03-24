package com.openclaw.android.ui.screens

import android.content.Intent
import android.net.Uri
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

// All providers with their display names and base URLs
private val ALL_PROVIDERS = listOf(
    Triple("anthropic", "Anthropic Claude", "https://api.anthropic.com"),
    Triple("openai", "OpenAI", "https://api.openai.com"),
    Triple("minimax", "MiniMax", "https://api.minimax.io/anthropic"),
    Triple("google", "Google Gemini", "https://generativelanguage.googleapis.com"),
    Triple("openrouter", "OpenRouter", "https://openrouter.ai/api"),
    Triple("deepseek", "DeepSeek", "https://api.deepseek.com"),
    Triple("mistral", "Mistral AI", "https://api.mistral.ai"),
    Triple("groq", "Groq", "https://api.groq.com/openai"),
    Triple("xai", "xAI (Grok)", "https://api.x.ai"),
    Triple("together", "Together AI", "https://api.together.xyz"),
    Triple("fireworks", "Fireworks AI", "https://api.fireworks.ai/inference"),
    Triple("ollama", "Ollama (Local)", "http://localhost:11434"),
    Triple("custom", "Custom API", ""),
)

@Composable
fun SettingsScreen(
    onStartService: () -> Unit = {},
    onStopService: () -> Unit = {}
) {
    val isRunning by ServiceState.isRunning.collectAsState()
    var activeProvider by remember { mutableStateOf(AgentConfig.activeProvider) }
    var apiKey by remember { mutableStateOf(AgentConfig.getKeyForProvider(AgentConfig.activeProvider)) }
    var modelName by remember { mutableStateOf(AgentConfig.getModelForProvider(AgentConfig.activeProvider)) }
    var providerDropdownExpanded by remember { mutableStateOf(false) }
    var checkingUpdate by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(BG).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            Text("Settings", color = TEXT, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(8.dp))
        }

        // ── 1) SERVICE TOGGLE ──────────────────────────
        item { SectionLabel("SERVICE") }
        item {
            Button(
                onClick = { if (isRunning) onStopService() else onStartService() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) RED else GREEN
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Icon(
                    if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                    null, Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isRunning) "Stop Service" else "Start Service",
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
                )
            }
        }

        // ── 2) LLM PROVIDER (dropdown + single API key) ──
        item { SectionLabel("LLM PROVIDER") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SURFACE), shape = RoundedCornerShape(10.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // Provider dropdown
                    Text("Provider", color = TEXT2, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(4.dp))
                    Box {
                        OutlinedButton(
                            onClick = { providerDropdownExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, BORDER),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TEXT)
                        ) {
                            val displayName = ALL_PROVIDERS.find { it.first == activeProvider }?.second ?: activeProvider
                            Text(displayName, fontFamily = FontFamily.Monospace, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Icon(Icons.Default.ArrowDropDown, null)
                        }
                        DropdownMenu(
                            expanded = providerDropdownExpanded,
                            onDismissRequest = { providerDropdownExpanded = false },
                            modifier = Modifier.background(Color(0xFF1C2333))
                        ) {
                            ALL_PROVIDERS.forEach { (id, name, _) ->
                                DropdownMenuItem(
                                    text = { Text(name, color = if (id == activeProvider) CYAN else TEXT, fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
                                    onClick = {
                                        activeProvider = id
                                        AgentConfig.activeProvider = id
                                        apiKey = AgentConfig.getKeyForProvider(id)
                                        modelName = AgentConfig.getModelForProvider(id)
                                        providerDropdownExpanded = false
                                    },
                                    leadingIcon = {
                                        if (id == activeProvider) Icon(Icons.Default.Check, null, tint = CYAN, modifier = Modifier.size(16.dp))
                                    }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // API Key (single field for active provider)
                    if (activeProvider != "ollama") {
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = {
                                apiKey = it
                                AgentConfig.setKeyForProvider(activeProvider, it)
                            },
                            label = { Text("API Key", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                            visualTransformation = if (apiKey.isNotEmpty()) PasswordVisualTransformation() else VisualTransformation.None,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = TEXT),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CYAN, unfocusedBorderColor = BORDER, cursorColor = CYAN, focusedLabelColor = CYAN, unfocusedLabelColor = TEXT2),
                            singleLine = true,
                            trailingIcon = {
                                if (apiKey.isNotEmpty()) {
                                    Icon(Icons.Default.Check, null, tint = GREEN, modifier = Modifier.size(16.dp))
                                }
                            }
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // Model name (editable)
                    OutlinedTextField(
                        value = modelName,
                        onValueChange = {
                            modelName = it
                            AgentConfig.setModelForProvider(activeProvider, it)
                        },
                        label = { Text("Model", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = TEXT),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CYAN, unfocusedBorderColor = BORDER, cursorColor = CYAN, focusedLabelColor = CYAN, unfocusedLabelColor = TEXT2),
                        singleLine = true
                    )

                    // Base URL for custom provider
                    if (activeProvider == "custom") {
                        Spacer(Modifier.height(8.dp))
                        var baseUrl by remember { mutableStateOf(AgentConfig.customBaseUrl) }
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it; AgentConfig.customBaseUrl = it },
                            label = { Text("Base URL", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = TEXT),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CYAN, unfocusedBorderColor = BORDER, cursorColor = CYAN, focusedLabelColor = CYAN, unfocusedLabelColor = TEXT2),
                            singleLine = true
                        )
                    }
                }
            }
        }

        // ── 5) NOTIFICATIONS ──────────────────────────
        item { SectionLabel("NOTIFICATIONS") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SURFACE), shape = RoundedCornerShape(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Push Notifications", color = TEXT, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                        Text("Get notified when agent completes tasks", color = TEXT2, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    var notifEnabled by remember { mutableStateOf(AgentConfig.pushNotificationsEnabled) }
                    Switch(
                        checked = notifEnabled,
                        onCheckedChange = { notifEnabled = it; AgentConfig.pushNotificationsEnabled = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = GREEN, checkedThumbColor = TEXT)
                    )
                }
            }
        }

        // ── 5) CHECK FOR UPDATES ──────────────────────
        item { SectionLabel("UPDATES") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SURFACE), shape = RoundedCornerShape(10.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("OpenClaw Android", color = TEXT, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                            Text("v0.5.2-alpha", color = TEXT2, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                        Button(
                            onClick = {
                                checkingUpdate = true
                                scope.launch {
                                    kotlinx.coroutines.delay(2000)
                                    checkingUpdate = false
                                    // Open GitHub releases page
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/wayansuardyana-code/android-openclaw-native/releases"))
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(intent)
                                }
                            },
                            enabled = !checkingUpdate,
                            colors = ButtonDefaults.buttonColors(containerColor = CYAN),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            if (checkingUpdate) {
                                CircularProgressIndicator(color = TEXT, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.SystemUpdate, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Check Updates", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // ── ABOUT ──────────────────────────────────
        item { SectionLabel("ABOUT") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SURFACE), shape = RoundedCornerShape(10.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    AboutRow("App", "OpenClaw Android Native")
                    AboutRow("Version", "0.6.0-alpha")
                    AboutRow("Bridge", "localhost:18790")
                    AboutRow("Tools", "17 (8 device + 9 utility)")
                    AboutRow("Database", "SQLite + Vector Search")
                    AboutRow("Device", android.os.Build.MODEL)
                    AboutRow("Android", "API ${android.os.Build.VERSION.SDK_INT}")
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
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
