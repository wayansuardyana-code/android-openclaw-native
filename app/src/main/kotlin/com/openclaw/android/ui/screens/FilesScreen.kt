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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.io.File

private val BG = Color(0xFF0D1117)
private val SURFACE = Color(0xFF161B22)
private val BORDER = Color(0xFF30363D)
private val CYAN = Color(0xFF58A6FF)
private val GREEN = Color(0xFF3FB950)
private val TEXT = Color(0xFFF0F6FC)
private val TEXT2 = Color(0xFF8B949E)

data class MdFile(val name: String, val description: String, val defaultContent: String)

// Files are now loaded dynamically from Bootstrap workspace
private val fileDescriptions = mapOf(
    "SOUL.md" to "Core personality & identity (who the agent IS)",
    "USER.md" to "Owner profile & preferences",
    "AGENTS.md" to "Workspace conventions & layout",
    "TOOLS.md" to "Tool notes, credentials, app packages",
    "HEARTBEAT.md" to "Periodic self-check patterns",
    "identity.md" to "Agent identity (injected into prompt)",
    "system_prompt.md" to "Custom instructions (appended to prompt)",
    "memory.md" to "Persistent learned facts",
    "skills.md" to "Installed skills manifest",
    "bootstrap.md" to "First-run bootstrap behavior",
)

@Composable
fun FilesScreen() {
    val context = LocalContext.current
    var selectedFileName by remember { mutableStateOf<String?>(null) }
    var editContent by remember { mutableStateOf("") }
    var isDirty by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<String?>(null) }

    // Bootstrap workspace if needed
    LaunchedEffect(Unit) {
        if (!com.openclaw.android.ai.Bootstrap.isBootstrapped()) {
            com.openclaw.android.ai.Bootstrap.run()
        }
    }

    // Get all files from workspace
    val allFiles = remember { com.openclaw.android.ai.Bootstrap.getAllFiles() }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().background(BG).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            Text("Files", color = TEXT, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text("Agent configuration files", color = TEXT2, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(8.dp))
        }

        items(allFiles) { (name, _) ->
            val isSelected = selectedFileName == name
            val desc = fileDescriptions[name] ?: "Workspace file"
            Card(
                colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFF1C2333) else SURFACE),
                shape = RoundedCornerShape(10.dp),
                border = if (isSelected) BorderStroke(1.dp, CYAN) else null,
                modifier = Modifier.fillMaxWidth().clickable {
                    selectedFileName = name
                    editContent = com.openclaw.android.ai.Bootstrap.readFile(name)
                    isDirty = false
                    saveMessage = null
                    // Scroll to editor (items: 1 header + N files + 1 editor header + 1 editor)
                    scope.launch { listState.animateScrollToItem(allFiles.size + 2) }
                }
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Description, null, tint = if (isSelected) CYAN else TEXT2, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(name, color = if (isSelected) CYAN else TEXT, fontSize = 14.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)
                        Text(desc, color = TEXT2, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    if (isSelected && isDirty) {
                        Box(Modifier.size(8.dp).background(Color(0xFFD29922), RoundedCornerShape(4.dp)))
                    }
                }
            }
        }

        if (selectedFileName != null) {
            item {
                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Editing: $selectedFileName", color = CYAN, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    Row {
                        if (saveMessage != null) {
                            Text(saveMessage!!, color = GREEN, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(end = 8.dp))
                        }
                        Button(
                            onClick = {
                                selectedFileName?.let { name ->
                                    // Save to workspace or agent_config
                                    val wsFile = File(com.openclaw.android.ai.Bootstrap.agentConfigDir(), name)
                                    val wsFile2 = File(com.openclaw.android.OpenClawApplication.instance.filesDir, "workspace/$name")
                                    if (wsFile2.exists()) wsFile2.writeText(editContent)
                                    else wsFile.writeText(editContent)
                                    isDirty = false
                                    saveMessage = "Saved!"
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = if (isDirty) GREEN else BORDER),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text("Save", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = editContent,
                    onValueChange = { editContent = it; isDirty = true; saveMessage = null },
                    modifier = Modifier.fillMaxWidth().height(400.dp),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TEXT, lineHeight = 18.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CYAN, unfocusedBorderColor = BORDER, cursorColor = CYAN,
                        focusedContainerColor = Color(0xFF010409), unfocusedContainerColor = Color(0xFF010409)
                    )
                )
            }
        }
    }
}
