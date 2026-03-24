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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openclaw.android.util.ServiceState

// Colors
private val BG = Color(0xFF0D1117)
private val SURFACE = Color(0xFF161B22)
private val BORDER = Color(0xFF30363D)
private val CYAN = Color(0xFF58A6FF)
private val GREEN = Color(0xFF3FB950)
private val RED = Color(0xFFF85149)
private val PURPLE = Color(0xFFD2A8FF)
private val ORANGE = Color(0xFFD29922)
private val TEXT = Color(0xFFF0F6FC)
private val TEXT2 = Color(0xFF8B949E)

@Composable
fun DashboardScreen(
    onQuickAction: (String) -> Unit = {}
) {
    val isRunning by ServiceState.isRunning.collectAsState()
    val logs by ServiceState.logs.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(BG).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        // Header
        item {
            Text("Mission Control", color = TEXT, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text("OpenClaw Android Native", color = TEXT2, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }

        // Status Grid
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(Modifier.weight(1f), "Gateway", if (isRunning) "ONLINE" else "OFFLINE", if (isRunning) GREEN else RED)
                StatusChip(Modifier.weight(1f), "Bridge", if (isRunning) ":18790" else "OFF", if (isRunning) GREEN else RED)
            }
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(Modifier.weight(1f), "A11y", "Check Settings", ORANGE)
                StatusChip(Modifier.weight(1f), "Notif", "Check Settings", ORANGE)
            }
        }

        // Quick Actions
        item {
            SectionHeader("Quick Actions")
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickActionBtn(Modifier.weight(1f), "Doctor Fix", Icons.Default.Build, CYAN) { onQuickAction("doctor_fix") }
                QuickActionBtn(Modifier.weight(1f), "Restart", Icons.Default.Refresh, GREEN) { onQuickAction("restart") }
            }
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickActionBtn(Modifier.weight(1f), "Clear Logs", Icons.Default.Delete, ORANGE) { onQuickAction("clear_logs") }
                QuickActionBtn(Modifier.weight(1f), "Sync Skills", Icons.Default.Sync, PURPLE) { onQuickAction("sync_skills") }
            }
        }

        // Task Summary
        item {
            SectionHeader("Tasks")
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SURFACE), shape = RoundedCornerShape(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    TaskBadge("Inbox", "0", TEXT2)
                    TaskBadge("Active", "0", CYAN)
                    TaskBadge("Review", "0", ORANGE)
                    TaskBadge("Done", "0", GREEN)
                }
            }
        }

        // Recent Logs
        item {
            SectionHeader("Recent Activity")
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF010409)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().height(200.dp)
            ) {
                if (logs.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No activity yet", color = Color(0xFF484F58), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                } else {
                    LazyColumn(modifier = Modifier.padding(8.dp)) {
                        items(logs.takeLast(20)) { log ->
                            Text(log, color = Color(0xFFC9D1D9), fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 14.sp)
                        }
                    }
                }
            }
        }

        // Stats
        item {
            SectionHeader("Stats")
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard(Modifier.weight(1f), "Memories", "0", PURPLE)
                StatCard(Modifier.weight(1f), "Tokens", "0", CYAN)
                StatCard(Modifier.weight(1f), "Uptime", if (isRunning) "Active" else "—", GREEN)
            }
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(title, color = TEXT2, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 4.dp))
}

@Composable
fun StatusChip(modifier: Modifier, label: String, value: String, color: Color) {
    Card(colors = CardDefaults.cardColors(containerColor = SURFACE), shape = RoundedCornerShape(10.dp), modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, color = TEXT2, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(color, RoundedCornerShape(4.dp)))
                Spacer(Modifier.width(6.dp))
                Text(value, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
fun QuickActionBtn(modifier: Modifier, label: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, BORDER),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color)
    ) {
        Icon(icon, null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun TaskBadge(label: String, count: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(count, color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text(label, color = TEXT2, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun StatCard(modifier: Modifier, label: String, value: String, color: Color) {
    Card(colors = CardDefaults.cardColors(containerColor = SURFACE), shape = RoundedCornerShape(10.dp), modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text(label, color = TEXT2, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
    }
}
