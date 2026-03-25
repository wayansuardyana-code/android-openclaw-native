package com.openclaw.android.ui.screens

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openclaw.android.ai.AgentConfig
import com.openclaw.android.util.ServiceState
import kotlinx.coroutines.delay

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

// Task statuses: Pending → Active → Done
data class KanbanTask(
    val id: Long = System.currentTimeMillis(),
    val title: String,
    val status: String = "pending", // "pending", "active", "done"
    val agentId: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

// Shared task state so agent can modify it
object TaskBoard {
    val tasks = mutableStateListOf<KanbanTask>()
    private val nextId = java.util.concurrent.atomic.AtomicLong(1L)

    fun addPending(title: String): Long {
        val id = nextId.getAndIncrement()
        tasks.add(KanbanTask(id = id, title = title, status = "pending"))
        return id
    }
    fun moveToActive(taskId: Long, agentId: String? = null) {
        val idx = tasks.indexOfFirst { it.id == taskId }
        if (idx >= 0) tasks[idx] = tasks[idx].copy(status = "active", agentId = agentId)
    }
    fun moveToDone(taskId: Long) {
        val idx = tasks.indexOfFirst { it.id == taskId }
        if (idx >= 0) tasks[idx] = tasks[idx].copy(status = "done")
    }
}

@Composable
fun DashboardScreen() {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Overview", "Task Board")

    Column(Modifier.fillMaxSize().background(BG)) {
        // Tab row
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = SURFACE,
            contentColor = CYAN,
            divider = { Divider(color = BORDER, thickness = 1.dp) }
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title, fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal) },
                    selectedContentColor = CYAN,
                    unselectedContentColor = TEXT2
                )
            }
        }

        when (selectedTab) {
            0 -> OverviewTab()
            1 -> TaskBoardTab()
        }
    }
}

@Composable
private fun OverviewTab() {
    val isRunning by ServiceState.isRunning.collectAsState()
    val logs by ServiceState.logs.collectAsState()
    val context = LocalContext.current

    var ramUsed by remember { mutableStateOf("--") }
    var ramTotal by remember { mutableStateOf("--") }
    var storageUsed by remember { mutableStateOf("--") }
    var storageFree by remember { mutableStateOf("--") }
    var batteryLevel by remember { mutableStateOf(0) }
    var batteryCharging by remember { mutableStateOf(false) }
    val tokensUsed by com.openclaw.android.ai.ConversationManager.totalTokensUsed.collectAsState()

    LaunchedEffect(Unit) {
        while (true) {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val totalMb = mi.totalMem / (1024 * 1024)
            val availMb = mi.availMem / (1024 * 1024)
            ramUsed = "${totalMb - availMb}MB"; ramTotal = "${totalMb}MB"

            val stat = StatFs(Environment.getDataDirectory().path)
            val totalGb = (stat.blockSizeLong * stat.blockCountLong) / (1024 * 1024 * 1024)
            val freeGb = (stat.blockSizeLong * stat.availableBlocksLong) / (1024 * 1024 * 1024)
            storageUsed = "${totalGb - freeGb}GB"; storageFree = "${freeGb}GB"

            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            batteryLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            batteryCharging = bm.isCharging
            delay(5000)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        item {
            Text("Mission Control", color = TEXT, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text("OpenClaw Android v2.4.0", color = TEXT2, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(Modifier.weight(1f), "Gateway", if (isRunning) "ONLINE" else "OFF", if (isRunning) GREEN else RED)
                StatusChip(Modifier.weight(1f), "Bridge", if (isRunning) ":18790" else "OFF", if (isRunning) GREEN else RED)
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(Modifier.weight(1f), "Provider", AgentConfig.activeProvider, CYAN)
                StatusChip(Modifier.weight(1f), "Battery", "${batteryLevel}%${if (batteryCharging) " ⚡" else ""}", if (batteryLevel > 20) GREEN else RED)
            }
        }
        item { SectionHeader("HARDWARE") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SURFACE), shape = RoundedCornerShape(10.dp)) {
                Column(Modifier.padding(12.dp)) {
                    HardwareRow("RAM", "$ramUsed / $ramTotal", CYAN)
                    HardwareRow("Storage", "$storageUsed used, $storageFree free", PURPLE)
                    HardwareRow("Battery", "$batteryLevel% ${if (batteryCharging) "(Charging)" else ""}", if (batteryLevel > 20) GREEN else RED)
                    HardwareRow("LLM Tokens", "$tokensUsed total", ORANGE)
                }
            }
        }
        item { SectionHeader("STATS") }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard(Modifier.weight(1f), "Tools", "56", CYAN)
                StatCard(Modifier.weight(1f), "Providers", "15", PURPLE)
                StatCard(Modifier.weight(1f), "Uptime", if (isRunning) "Active" else "—", GREEN)
            }
        }
        item { SectionHeader("RECENT ACTIVITY") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF010409)), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().height(180.dp)) {
                if (logs.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No activity", color = Color(0xFF484F58), fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                } else {
                    LazyColumn(Modifier.padding(8.dp)) {
                        items(logs.takeLast(20)) { log ->
                            val color = when {
                                log.lowercase().let { it.contains("error") || it.contains("fail") } -> RED
                                log.lowercase().let { it.contains("started") || it.contains("success") } -> GREEN
                                log.lowercase().contains("tool") -> CYAN
                                else -> Color(0xFFC9D1D9)
                            }
                            Text(log, color = color, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 14.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskBoardTab() {
    val pending = TaskBoard.tasks.filter { it.status == "pending" }
    val active = TaskBoard.tasks.filter { it.status == "active" }
    val done = TaskBoard.tasks.filter { it.status == "done" }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Task Board", color = TEXT, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(4.dp))
        Text("Auto-managed by Nate. Plan via chat → Pending → Active → Done.", color = TEXT2, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(12.dp))

        // Full-width kanban columns
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KanbanColumnFull("Pending", pending, ORANGE, Modifier.weight(1f))
            KanbanColumnFull("Active", active, CYAN, Modifier.weight(1f))
            KanbanColumnFull("Done", done, GREEN, Modifier.weight(1f))
        }
    }
}

@Composable
fun KanbanColumnFull(title: String, tasks: List<KanbanTask>, color: Color, modifier: Modifier = Modifier) {
    Card(colors = CardDefaults.cardColors(containerColor = SURFACE), shape = RoundedCornerShape(10.dp), modifier = modifier.fillMaxHeight()) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(color, RoundedCornerShape(4.dp)))
                Spacer(Modifier.width(6.dp))
                Text(title, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.weight(1f))
                Text("${tasks.size}", color = TEXT2, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(6.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (tasks.isEmpty()) {
                    item { Text("—", color = Color(0xFF484F58), fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(4.dp)) }
                }
                items(tasks) { task ->
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF010409)), shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(task.title, color = TEXT, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun KanbanColumn(title: String, tasks: List<KanbanTask>, color: Color, width: androidx.compose.ui.unit.Dp) {
    Card(colors = CardDefaults.cardColors(containerColor = SURFACE), shape = RoundedCornerShape(10.dp), modifier = Modifier.width(width)) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(color, RoundedCornerShape(4.dp)))
                Spacer(Modifier.width(6.dp))
                Text(title, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.weight(1f))
                Text("${tasks.size}", color = TEXT2, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(6.dp))
            if (tasks.isEmpty()) {
                Text("—", color = Color(0xFF484F58), fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(4.dp))
            } else {
                tasks.take(5).forEach { task ->
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF010409)), shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(task.title, color = TEXT, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(6.dp))
                    }
                }
                if (tasks.size > 5) Text("+${tasks.size - 5} more", color = TEXT2, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable fun SectionHeader(title: String) { Text(title, color = TEXT2, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp, modifier = Modifier.padding(top = 4.dp)) }

@Composable fun StatusChip(modifier: Modifier, label: String, value: String, color: Color) {
    Card(colors = CardDefaults.cardColors(containerColor = SURFACE), shape = RoundedCornerShape(10.dp), modifier = modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, color = TEXT2, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(color, RoundedCornerShape(4.dp)))
                Spacer(Modifier.width(6.dp))
                Text(value, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 1)
            }
        }
    }
}

@Composable fun StatCard(modifier: Modifier, label: String, value: String, color: Color) {
    Card(colors = CardDefaults.cardColors(containerColor = SURFACE), shape = RoundedCornerShape(10.dp), modifier = modifier) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text(label, color = TEXT2, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable fun HardwareRow(label: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TEXT2, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Text(value, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}
