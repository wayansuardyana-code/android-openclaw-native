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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openclaw.android.util.ServiceState

private val BG = Color(0xFF0D1117)
private val CYAN = Color(0xFF58A6FF)
private val TEXT = Color(0xFFF0F6FC)
private val TEXT2 = Color(0xFF8B949E)
private val BORDER = Color(0xFF30363D)

@Composable
fun LogScreen() {
    val logs by ServiceState.logs.collectAsState()
    var filter by remember { mutableStateOf("all") }
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize().background(BG).padding(horizontal = 16.dp, vertical = 16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Logs", color = TEXT, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            IconButton(onClick = { ServiceState.clearLogs() }) {
                Icon(Icons.Default.Delete, "Clear", tint = TEXT2)
            }
        }

        // Filter buttons
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("all", "error", "bridge", "agent").forEach { f ->
                val isSelected = filter == f
                OutlinedButton(
                    onClick = { filter = f },
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, if (isSelected) CYAN else BORDER),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (isSelected) CYAN.copy(alpha = 0.15f) else Color.Transparent,
                        contentColor = if (isSelected) CYAN else TEXT2
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(f.replaceFirstChar { c -> c.uppercase() }, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        }

        // Log output
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF010409)),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            val filteredLogs = when (filter) {
                "error" -> logs.filter { it.lowercase().contains("error") || it.lowercase().contains("fail") }
                "bridge" -> logs.filter { it.lowercase().contains("bridge") }
                "agent" -> logs.filter { it.lowercase().contains("agent") || it.lowercase().contains("session") }
                else -> logs
            }

            if (filteredLogs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No logs", color = Color(0xFF484F58), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.padding(8.dp)) {
                    items(filteredLogs) { log ->
                        val color = when {
                            log.lowercase().contains("error") || log.lowercase().contains("fail") -> Color(0xFFF85149)
                            log.lowercase().contains("started") || log.lowercase().contains("success") -> Color(0xFF3FB950)
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
