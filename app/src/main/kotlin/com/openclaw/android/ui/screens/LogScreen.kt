package com.openclaw.android.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openclaw.android.util.ServiceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

private val BG = Color(0xFF0D1117)
private val SURFACE = Color(0xFF161B22)
private val BORDER = Color(0xFF30363D)
private val CYAN = Color(0xFF58A6FF)
private val GREEN = Color(0xFF3FB950)
private val RED = Color(0xFFF85149)
private val ORANGE = Color(0xFFD29922)
private val TEXT = Color(0xFFF0F6FC)
private val TEXT2 = Color(0xFF8B949E)

@Composable
fun LogScreen() {
    // Auto-switch to Crashes tab if there's a recent crash
    val hasCrash = remember { com.openclaw.android.OpenClawApplication.instance.getLatestCrashLog() != null }
    var activeTab by remember { mutableStateOf(if (hasCrash) "crashes" else "logs") }

    Column(modifier = Modifier.fillMaxSize().background(BG)) {
        Row(
            modifier = Modifier.fillMaxWidth().background(SURFACE).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("logs" to "Logs", "terminal" to "Terminal", "crashes" to "Crashes").forEach { (id, label) ->
                val selected = activeTab == id
                OutlinedButton(
                    onClick = { activeTab = id },
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, if (selected) CYAN else BORDER),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (selected) CYAN.copy(alpha = 0.15f) else Color.Transparent,
                        contentColor = if (selected) CYAN else TEXT2
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Icon(if (id == "logs") Icons.Default.List else Icons.Default.Terminal, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(label, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        }
        when (activeTab) {
            "logs" -> LogsTab()
            "terminal" -> TerminalTab()
            "crashes" -> CrashLogsTab()
        }
    }
}

@Composable
fun LogsTab() {
    val logs by ServiceState.logs.collectAsState()
    var filter by remember { mutableStateOf("all") }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(logs.size) { if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Logs", color = TEXT, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Row {
                IconButton(onClick = { clipboard.setText(AnnotatedString(logs.joinToString("\n"))) }) {
                    Icon(Icons.Default.ContentCopy, "Copy all", tint = TEXT2, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = { ServiceState.clearLogs() }) {
                    Icon(Icons.Default.Delete, "Clear", tint = TEXT2, modifier = Modifier.size(20.dp))
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("all", "error", "bridge", "agent", "tool").forEach { f ->
                val isSelected = filter == f
                OutlinedButton(
                    onClick = { filter = f }, shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, if (isSelected) CYAN else BORDER),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = if (isSelected) CYAN.copy(alpha = 0.15f) else Color.Transparent, contentColor = if (isSelected) CYAN else TEXT2),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                ) { Text(f.replaceFirstChar { c -> c.uppercase() }, fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF010409)), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxSize()) {
            val filteredLogs = when (filter) {
                "error" -> logs.filter { it.lowercase().let { l -> l.contains("error") || l.contains("fail") } }
                "bridge" -> logs.filter { it.lowercase().contains("bridge") }
                "agent" -> logs.filter { it.lowercase().let { l -> l.contains("agent") || l.contains("session") } }
                "tool" -> logs.filter { it.lowercase().contains("tool") }
                else -> logs
            }
            if (filteredLogs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No logs", color = Color(0xFF484F58), fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            } else {
                SelectionContainer {
                    LazyColumn(state = listState, modifier = Modifier.padding(8.dp)) {
                        items(filteredLogs) { log ->
                            val color = when {
                                log.lowercase().let { it.contains("error") || it.contains("fail") } -> RED
                                log.lowercase().let { it.contains("started") || it.contains("success") } -> GREEN
                                log.lowercase().contains("tool") -> CYAN
                                log.lowercase().contains("warning") -> Color(0xFFD29922)
                                else -> Color(0xFFC9D1D9)
                            }
                            Text(log, color = color, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 14.sp, modifier = Modifier.padding(vertical = 1.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TerminalTab() {
    var input by remember { mutableStateOf("") }
    val output = remember { mutableStateListOf<Pair<String, Color>>() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(output.size) { if (output.isNotEmpty()) listState.animateScrollToItem(output.size - 1) }
    LaunchedEffect(Unit) {
        if (output.isEmpty()) {
            output.add("OpenClaw Terminal v0.6.0" to GREEN)
            output.add("Type shell commands here." to TEXT2)
            output.add("Examples: ls, pwd, whoami, id, uname -a" to TEXT2)
            output.add("─".repeat(40) to BORDER)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Terminal", color = TEXT, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(8.dp))

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF010409)), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
            SelectionContainer {
                LazyColumn(state = listState, modifier = Modifier.padding(8.dp)) {
                    items(output) { (text, color) ->
                        Text(text, color = color, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$", color = GREEN, fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                placeholder = { Text("command...", color = Color(0xFF484F58), fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
                modifier = Modifier.weight(1f), singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = TEXT),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CYAN, unfocusedBorderColor = BORDER, cursorColor = CYAN, focusedContainerColor = Color(0xFF010409), unfocusedContainerColor = Color(0xFF010409)),
                shape = RoundedCornerShape(8.dp)
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = {
                if (input.isBlank()) return@IconButton
                val cmd = input.trim(); input = ""
                output.add("$ $cmd" to CYAN)
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        try {
                            val process = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
                            val out = BufferedReader(InputStreamReader(process.inputStream)).readText()
                            val exitCode = process.waitFor()
                            Pair(out.ifBlank { "(no output)" }, exitCode)
                        } catch (e: Exception) { Pair("Error: ${e.message}", 1) }
                    }
                    val color = if (result.second == 0) Color(0xFFC9D1D9) else RED
                    result.first.lines().forEach { line -> output.add(line to color) }
                }
            }) { Icon(Icons.Default.Send, "Run", tint = CYAN, modifier = Modifier.size(20.dp)) }
        }
    }
}

@Composable
fun CrashLogsTab() {
    val app = com.openclaw.android.OpenClawApplication.instance
    val latestCrash = remember { app.getLatestCrashLog() }
    val allCrashes = remember { app.getCrashLogs() }
    val clipboard = LocalClipboardManager.current

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Crash Logs", color = TEXT, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Row {
                if (latestCrash != null) {
                    IconButton(onClick = { clipboard.setText(AnnotatedString(latestCrash)) }) {
                        Icon(Icons.Default.ContentCopy, "Copy", tint = TEXT2, modifier = Modifier.size(20.dp))
                    }
                }
                IconButton(onClick = { app.clearCrashLogs() }) {
                    Icon(Icons.Default.Delete, "Clear", tint = TEXT2, modifier = Modifier.size(20.dp))
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        if (latestCrash == null && allCrashes.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.CheckCircle, null, tint = GREEN, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("No crashes recorded", color = GREEN, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                    Text("App is running stable", color = TEXT2, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        } else {
            // Show latest crash prominently
            if (latestCrash != null) {
                Text("LATEST CRASH", color = RED, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                Spacer(Modifier.height(4.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF010409)),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, RED.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth().weight(1f)
                ) {
                    SelectionContainer {
                        LazyColumn(modifier = Modifier.padding(8.dp)) {
                            items(latestCrash.lines()) { line ->
                                val color = when {
                                    line.contains("===") -> CYAN
                                    line.contains("at ") -> Color(0xFF8B949E)
                                    line.contains("Caused by") -> RED
                                    line.contains("Exception") || line.contains("Error") -> RED
                                    line.startsWith("Time:") || line.startsWith("Device:") || line.startsWith("Android:") -> ORANGE
                                    else -> Color(0xFFC9D1D9)
                                }
                                Text(line, color = color, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 14.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
