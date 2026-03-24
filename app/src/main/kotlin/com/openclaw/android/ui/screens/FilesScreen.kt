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
import java.io.File

private val BG = Color(0xFF0D1117)
private val SURFACE = Color(0xFF161B22)
private val BORDER = Color(0xFF30363D)
private val CYAN = Color(0xFF58A6FF)
private val GREEN = Color(0xFF3FB950)
private val TEXT = Color(0xFFF0F6FC)
private val TEXT2 = Color(0xFF8B949E)

data class MdFile(val name: String, val description: String, val defaultContent: String)

private val defaultFiles = listOf(
    MdFile("identity.md", "Agent identity & personality", """# Identity

## Name
OpenClaw Android

## Role
Personal AI assistant with full Android device control.

## Personality
- Direct and efficient
- Technical but approachable
- Proactive — suggest actions, don't just answer

## Owner
(your name here)

## Context
Running on Android device with accessibility, notification, and system control.
"""),
    MdFile("memory.md", "Persistent memory & learned facts", """# Memory

## Learned Facts
- (facts the agent learns over time)

## User Preferences
- (preferences discovered through interaction)

## Important Dates
- (birthdays, deadlines, events)
"""),
    MdFile("system_prompt.md", "Custom system prompt additions", """# System Prompt Additions

Add custom instructions that get appended to the agent's system prompt.

## Custom Instructions
-
"""),
    MdFile("skills.md", "Installed skills manifest", """# Installed Skills

## Active Skills
- file_generation: Generate XLSX, CSV, PDF files
- web_scrape: Fetch and parse web pages
- calculator: Math expressions and statistics
- android_control: Full device control via accessibility
- shell: Execute shell commands
- web_search: DuckDuckGo search
- http_request: Call any API

## Pending
- (skills waiting to be installed)
"""),
)

@Composable
fun FilesScreen() {
    val context = LocalContext.current
    val filesDir = remember { File(context.filesDir, "agent_config") }
    var selectedFile by remember { mutableStateOf<MdFile?>(null) }
    var editContent by remember { mutableStateOf("") }
    var isDirty by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<String?>(null) }

    // Ensure directory exists
    LaunchedEffect(Unit) { filesDir.mkdirs() }

    // Load file content when selected
    fun loadFileContent(file: MdFile): String {
        val diskFile = File(filesDir, file.name)
        return if (diskFile.exists()) diskFile.readText() else file.defaultContent
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(BG).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            Text("Files", color = TEXT, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text("Agent configuration files", color = TEXT2, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(8.dp))
        }

        items(defaultFiles) { file ->
            val isSelected = selectedFile?.name == file.name
            Card(
                colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFF1C2333) else SURFACE),
                shape = RoundedCornerShape(10.dp),
                border = if (isSelected) BorderStroke(1.dp, CYAN) else null,
                modifier = Modifier.fillMaxWidth().clickable {
                    selectedFile = file
                    editContent = loadFileContent(file)
                    isDirty = false
                    saveMessage = null
                }
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Description, null, tint = if (isSelected) CYAN else TEXT2, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(file.name, color = if (isSelected) CYAN else TEXT, fontSize = 14.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)
                        Text(file.description, color = TEXT2, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    if (isSelected && isDirty) {
                        Box(Modifier.size(8.dp).background(Color(0xFFD29922), RoundedCornerShape(4.dp)))
                    }
                }
            }
        }

        if (selectedFile != null) {
            item {
                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Editing: ${selectedFile?.name}", color = CYAN, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    Row {
                        if (saveMessage != null) {
                            Text(saveMessage!!, color = GREEN, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(end = 8.dp))
                        }
                        Button(
                            onClick = {
                                selectedFile?.let { file ->
                                    File(filesDir, file.name).writeText(editContent)
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
