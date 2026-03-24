package com.openclaw.android.ui.screens

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val BG = Color(0xFF0D1117)
private val SURFACE = Color(0xFF161B22)
private val BORDER = Color(0xFF30363D)
private val CYAN = Color(0xFF58A6FF)
private val GREEN = Color(0xFF3FB950)
private val TEXT = Color(0xFFF0F6FC)
private val TEXT2 = Color(0xFF8B949E)

@Composable
fun SettingsScreen(
    onStartService: () -> Unit = {},
    onStopService: () -> Unit = {}
) {
    var activeProvider by remember { mutableStateOf(com.openclaw.android.ai.AgentConfig.activeProvider) }
    var anthropicKey by remember { mutableStateOf(com.openclaw.android.ai.AgentConfig.anthropicKey) }
    var openaiKey by remember { mutableStateOf(com.openclaw.android.ai.AgentConfig.openaiKey) }
    var minimaxKey by remember { mutableStateOf(com.openclaw.android.ai.AgentConfig.minimaxKey) }
    var openrouterKey by remember { mutableStateOf(com.openclaw.android.ai.AgentConfig.openrouterKey) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(BG).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            Text("Settings", color = TEXT, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(8.dp))
        }

        // Service Control
        item { SectionLabel("SERVICE") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SURFACE), shape = RoundedCornerShape(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onStartService, colors = ButtonDefaults.buttonColors(containerColor = GREEN), shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Start", fontFamily = FontFamily.Monospace)
                    }
                    Button(onClick = onStopService, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF85149)), shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Stop, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Stop", fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        // Active LLM Provider
        item { SectionLabel("ACTIVE LLM PROVIDER") }
        item {
            val providers = listOf(
                "anthropic" to "Anthropic Claude",
                "openai" to "OpenAI GPT",
                "minimax" to "MiniMax M2.5",
                "google" to "Google Gemini",
                "openrouter" to "OpenRouter",
                "ollama" to "Ollama (Local)",
                "custom" to "Custom API"
            )
            Card(colors = CardDefaults.cardColors(containerColor = SURFACE), shape = RoundedCornerShape(10.dp)) {
                Column(modifier = Modifier.padding(8.dp)) {
                    providers.forEach { (id, name) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { activeProvider = id; com.openclaw.android.ai.AgentConfig.activeProvider = id }.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = activeProvider == id, onClick = { activeProvider = id },
                                colors = RadioButtonDefaults.colors(selectedColor = CYAN, unselectedColor = BORDER))
                            Spacer(Modifier.width(8.dp))
                            Text(name, color = if (activeProvider == id) TEXT else TEXT2, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }

        // API Keys
        item { SectionLabel("API KEYS") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SURFACE), shape = RoundedCornerShape(10.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    ApiKeyField("Anthropic API Key", anthropicKey) { anthropicKey = it; com.openclaw.android.ai.AgentConfig.anthropicKey = it }
                    ApiKeyField("OpenAI API Key", openaiKey) { openaiKey = it; com.openclaw.android.ai.AgentConfig.openaiKey = it }
                    ApiKeyField("MiniMax API Key", minimaxKey) { minimaxKey = it; com.openclaw.android.ai.AgentConfig.minimaxKey = it }
                    ApiKeyField("OpenRouter API Key", openrouterKey) { openrouterKey = it; com.openclaw.android.ai.AgentConfig.openrouterKey = it }
                }
            }
        }

        // About
        item { SectionLabel("ABOUT") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SURFACE), shape = RoundedCornerShape(10.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    AboutRow("App", "OpenClaw Android Native")
                    AboutRow("Version", "0.2.0-alpha")
                    AboutRow("Engine", "OpenClaw Gateway")
                    AboutRow("Bridge", "localhost:18790")
                    AboutRow("Database", "SQLite + Vector Search")
                    AboutRow("Device", android.os.Build.MODEL)
                    AboutRow("Android", "API ${android.os.Build.VERSION.SDK_INT}")
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = TEXT2, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun ApiKeyField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label, fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
        visualTransformation = if (value.isNotEmpty()) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = TEXT),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CYAN, unfocusedBorderColor = BORDER, cursorColor = CYAN, focusedLabelColor = CYAN, unfocusedLabelColor = TEXT2),
        singleLine = true
    )
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TEXT2, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Text(value, color = TEXT, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}
