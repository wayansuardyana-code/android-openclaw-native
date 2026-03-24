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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
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

// Kanban column enum
enum class KanbanColumn(val label: String, val color: Color) {
    INBOX("Inbox", Color(0xFF8B949E)),
    ACTIVE("Active", Color(0xFF58A6FF)),
    REVIEW("Review", Color(0xFFD29922)),
    DONE("Done", Color(0xFF3FB950))
}

data class KanbanTask(
    val id: Long,
    val title: String,
    val column: KanbanColumn
)

@Composable
fun DashboardScreen(
    onQuickAction: (String) -> Unit = {}
) {
    val isRunning by ServiceState.isRunning.collectAsState()
    val logs by ServiceState.logs.collectAsState()

    // Kanban state
    val tasks = remember {
        mutableStateListOf(
            KanbanTask(1, "Setup bridge server", KanbanColumn.DONE),
            KanbanTask(2, "Implement agent loop", KanbanColumn.ACTIVE),
            KanbanTask(3, "Add push notifications", KanbanColumn.INBOX),
            KanbanTask(4, "Wire connector toggles", KanbanColumn.REVIEW)
        )
    }
    var nextId by remember { mutableStateOf(5L) }
    var showAddDialog by remember { mutableStateOf(false) }

    // Add Task Dialog
    if (showAddDialog) {
        AddTaskDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { title ->
                tasks.add(KanbanTask(nextId++, title, KanbanColumn.INBOX))
                showAddDialog = false
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        // Header
        item {
            Text(
                "Mission Control",
                color = TEXT,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                "OpenClaw Android Native",
                color = TEXT2,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        // Status Grid
        item {
            SectionHeader("Status")
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusChip(
                    Modifier.weight(1f),
                    "Gateway",
                    if (isRunning) "ONLINE" else "OFFLINE",
                    if (isRunning) GREEN else RED
                )
                StatusChip(
                    Modifier.weight(1f),
                    "Bridge",
                    if (isRunning) ":18790" else "OFF",
                    if (isRunning) GREEN else RED
                )
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusChip(Modifier.weight(1f), "A11y", "Check Settings", ORANGE)
                StatusChip(Modifier.weight(1f), "Notif", "Check Settings", ORANGE)
            }
        }

        // Quick Actions
        item {
            SectionHeader("Quick Actions")
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuickActionBtn(Modifier.weight(1f), "Doctor Fix", Icons.Default.Build, CYAN) {
                    onQuickAction("doctor_fix")
                }
                QuickActionBtn(Modifier.weight(1f), "Restart", Icons.Default.Refresh, GREEN) {
                    onQuickAction("restart")
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuickActionBtn(Modifier.weight(1f), "Clear Logs", Icons.Default.Delete, ORANGE) {
                    onQuickAction("clear_logs")
                }
                QuickActionBtn(Modifier.weight(1f), "Sync", Icons.Default.Sync, PURPLE) {
                    onQuickAction("sync_skills")
                }
            }
        }

        // Kanban Board
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionHeader("Kanban Board")
                IconButton(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Add Task",
                        tint = CYAN,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
        item {
            KanbanBoard(
                tasks = tasks,
                onMoveTask = { task ->
                    val idx = tasks.indexOfFirst { it.id == task.id }
                    if (idx >= 0) {
                        val current = tasks[idx]
                        val nextColumn = when (current.column) {
                            KanbanColumn.INBOX -> KanbanColumn.ACTIVE
                            KanbanColumn.ACTIVE -> KanbanColumn.REVIEW
                            KanbanColumn.REVIEW -> KanbanColumn.DONE
                            KanbanColumn.DONE -> KanbanColumn.DONE
                        }
                        if (nextColumn != current.column) {
                            tasks[idx] = current.copy(column = nextColumn)
                        }
                    }
                }
            )
        }

        // Stats Row
        item {
            SectionHeader("Stats")
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatCard(Modifier.weight(1f), "Tools", "22", CYAN)
                StatCard(Modifier.weight(1f), "Tokens", "0", PURPLE)
                StatCard(Modifier.weight(1f), "Memory", "0", GREEN)
            }
        }

        // Recent Activity (last 10 logs)
        item {
            SectionHeader("Recent Activity")
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF010409)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
            ) {
                if (logs.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "No activity yet",
                            color = Color(0xFF484F58),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.padding(8.dp)) {
                        items(logs.takeLast(10)) { log ->
                            Text(
                                log,
                                color = Color(0xFFC9D1D9),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                lineHeight = 14.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Kanban Board ────────────────────────────────────────────────────────────────

@Composable
private fun KanbanBoard(
    tasks: List<KanbanTask>,
    onMoveTask: (KanbanTask) -> Unit
) {
    val scrollState = rememberScrollState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        KanbanColumn.entries.forEach { column ->
            val columnTasks = tasks.filter { it.column == column }
            KanbanColumnCard(
                column = column,
                tasks = columnTasks,
                onMoveTask = onMoveTask
            )
        }
    }
}

@Composable
private fun KanbanColumnCard(
    column: KanbanColumn,
    tasks: List<KanbanTask>,
    onMoveTask: (KanbanTask) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SURFACE),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.width(160.dp)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Column header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(column.color, RoundedCornerShape(4.dp))
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        column.label,
                        color = TEXT,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Text(
                    "${tasks.size}",
                    color = TEXT2,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Divider(color = BORDER, thickness = 1.dp)

            // Task cards
            if (tasks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Empty",
                        color = Color(0xFF484F58),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            } else {
                tasks.forEach { task ->
                    KanbanTaskCard(
                        task = task,
                        onClick = { onMoveTask(task) }
                    )
                }
            }
        }
    }
}

@Composable
private fun KanbanTaskCard(
    task: KanbanTask,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = BG),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .border(1.dp, BORDER, RoundedCornerShape(6.dp))
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                task.title,
                color = TEXT,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (task.column != KanbanColumn.DONE) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "tap to advance ->",
                    color = Color(0xFF484F58),
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

// ── Add Task Dialog ─────────────────────────────────────────────────────────────

@Composable
private fun AddTaskDialog(
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit
) {
    var title by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = SURFACE),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "New Task",
                    color = TEXT,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = {
                        Text(
                            "Task title",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TEXT,
                        unfocusedTextColor = TEXT,
                        cursorColor = CYAN,
                        focusedBorderColor = CYAN,
                        unfocusedBorderColor = BORDER,
                        focusedLabelColor = CYAN,
                        unfocusedLabelColor = TEXT2
                    ),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp
                    ),
                    singleLine = true
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            "Cancel",
                            color = TEXT2,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (title.isNotBlank()) {
                                onAdd(title.trim())
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CYAN),
                        shape = RoundedCornerShape(8.dp),
                        enabled = title.isNotBlank()
                    ) {
                        Text(
                            "Add",
                            color = BG,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

// ── Shared Components ───────────────────────────────────────────────────────────

@Composable
fun SectionHeader(title: String) {
    Text(
        title,
        color = TEXT2,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
fun StatusChip(modifier: Modifier, label: String, value: String, color: Color) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SURFACE),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, color = TEXT2, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(color, RoundedCornerShape(4.dp))
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    value,
                    color = color,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
fun QuickActionBtn(
    modifier: Modifier,
    label: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit
) {
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
        Text(
            count,
            color = color,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Text(label, color = TEXT2, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun StatCard(modifier: Modifier, label: String, value: String, color: Color) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SURFACE),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                value,
                color = color,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(label, color = TEXT2, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
    }
}
