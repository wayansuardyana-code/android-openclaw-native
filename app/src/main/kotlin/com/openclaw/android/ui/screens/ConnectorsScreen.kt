package com.openclaw.android.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.openclaw.android.data.ConnectorRegistry
import com.openclaw.android.data.entity.ConnectorEntity

private val BG = Color(0xFF0D1117)
private val SURFACE = Color(0xFF161B22)
private val BORDER = Color(0xFF30363D)
private val CYAN = Color(0xFF58A6FF)
private val GREEN = Color(0xFF3FB950)
private val RED = Color(0xFFF85149)
private val TEXT = Color(0xFFF0F6FC)
private val TEXT2 = Color(0xFF8B949E)

private val gson = Gson()
private val mapType = object : TypeToken<MutableMap<String, String>>() {}.type

@Composable
fun ConnectorsScreen() {
    val connectors = remember { mutableStateListOf(*ConnectorRegistry.getDefaults().toTypedArray()) }

    val categories = listOf(
        "llm" to "LLM Providers",
        "channel" to "Channels",
        "database" to "Databases",
        "tool" to "Developer Tools",
        "file_gen" to "File Generation",
        "skill" to "Skills & Extensions"
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(BG).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            Text("Connectors", color = TEXT, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text("Configure tools, APIs & integrations", color = TEXT2, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(8.dp))
        }

        categories.forEach { (catId, catName) ->
            val catConnectors = connectors.filter { it.category == catId }
            if (catConnectors.isNotEmpty()) {
                item {
                    Text(catName.uppercase(), color = TEXT2, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                }
                items(catConnectors, key = { it.id }) { connector ->
                    ConnectorCard(connector) { updated ->
                        val idx = connectors.indexOfFirst { it.id == updated.id }
                        if (idx >= 0) connectors[idx] = updated
                    }
                }
            }
        }
    }
}

@Composable
fun ConnectorCard(connector: ConnectorEntity, onUpdate: (ConnectorEntity) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val statusColor = when (connector.status) {
        "connected" -> GREEN
        "error" -> RED
        else -> TEXT2
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = SURFACE),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                // Status dot
                Box(Modifier.size(10.dp).background(statusColor, RoundedCornerShape(5.dp)))
                Spacer(Modifier.width(10.dp))
                // Name + description
                Column(modifier = Modifier.weight(1f)) {
                    Text(connector.name, color = TEXT, fontSize = 14.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)
                    Text(connector.description, color = TEXT2, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                // Toggle
                Switch(
                    checked = connector.enabled,
                    onCheckedChange = { onUpdate(connector.copy(enabled = it)) },
                    colors = SwitchDefaults.colors(checkedTrackColor = GREEN, checkedThumbColor = TEXT)
                )
            }

            // Expanded config
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Divider(color = BORDER, thickness = 1.dp)
                Spacer(Modifier.height(8.dp))

                val config: MutableMap<String, String> = remember(connector.configJson) {
                    try {
                        gson.fromJson(connector.configJson, mapType)
                    } catch (_: Exception) {
                        mutableMapOf()
                    }
                }

                config.forEach { (key, value) ->
                    val isSecret = key.lowercase().let { it.contains("key") || it.contains("token") || it.contains("password") || it.contains("secret") }
                    var fieldValue by remember(key, value) { mutableStateOf(value) }

                    OutlinedTextField(
                        value = fieldValue,
                        onValueChange = { newVal ->
                            fieldValue = newVal
                            config[key] = newVal
                            onUpdate(connector.copy(configJson = gson.toJson(config)))
                        },
                        label = { Text(key, fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                        visualTransformation = if (isSecret && fieldValue.isNotEmpty()) PasswordVisualTransformation() else VisualTransformation.None,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = TEXT),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CYAN,
                            unfocusedBorderColor = BORDER,
                            cursorColor = CYAN,
                            focusedLabelColor = CYAN,
                            unfocusedLabelColor = TEXT2
                        ),
                        singleLine = true
                    )
                }

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { /* test connection */ }) {
                        Text("Test", color = CYAN, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
