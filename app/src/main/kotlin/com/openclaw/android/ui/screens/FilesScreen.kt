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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

private fun notesDir(): File {
    val dir = File(
        android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOCUMENTS
        ), "OpenClaw/notes"
    )
    dir.mkdirs()
    return dir
}

data class NoteInfo(
    val file: File,
    val title: String,
    val preview: String,
    val lastModified: Long
)

private fun loadNotes(): List<NoteInfo> {
    val dir = notesDir()
    if (!dir.exists()) return emptyList()
    return dir.walkTopDown()
        .filter { it.isFile && it.extension == "md" }
        .map { file ->
            val lines = file.readText().lines()
            val preview = lines.take(2).joinToString(" ").take(120)
            NoteInfo(
                file = file,
                title = file.nameWithoutExtension,
                preview = preview,
                lastModified = file.lastModified()
            )
        }
        .sortedByDescending { it.lastModified }
        .toList()
}

@Composable
fun FilesScreen() {
    var selectedTab by remember { mutableIntStateOf(0) }
    var editingNotePath by remember { mutableStateOf<String?>(null) }

    if (editingNotePath != null) {
        NoteEditor(filePath = editingNotePath!!, onBack = { editingNotePath = null })
    } else {
        Column(Modifier.fillMaxSize().background(BG)) {
            // Tab row
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = SURFACE,
                contentColor = CYAN,
                divider = { Divider(color = BORDER, thickness = 1.dp) }
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Text(
                            "Notes",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = if (selectedTab == 0) CYAN else TEXT2
                        )
                    },
                    icon = {
                        Icon(
                            Icons.Default.NoteAdd,
                            contentDescription = null,
                            tint = if (selectedTab == 0) CYAN else TEXT2,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Text(
                            "Config",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = if (selectedTab == 1) CYAN else TEXT2
                        )
                    },
                    icon = {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = null,
                            tint = if (selectedTab == 1) CYAN else TEXT2,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
            }

            when (selectedTab) {
                0 -> NotesTab(onOpenNote = { path -> editingNotePath = path })
                1 -> ConfigTab()
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NotesTab(onOpenNote: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var notes by remember { mutableStateOf<List<NoteInfo>>(emptyList()) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var newNoteTitle by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf<NoteInfo?>(null) }

    // Load notes
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            notes = loadNotes()
        }
    }

    // Refresh notes when returning from editor
    val refreshNotes: () -> Unit = {
        scope.launch(Dispatchers.IO) {
            notes = loadNotes()
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (notes.isEmpty()) {
            // Empty state
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.NoteAdd,
                    contentDescription = null,
                    tint = TEXT2,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "No notes yet",
                    color = TEXT2,
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    "Tap + to create a note",
                    color = TEXT2,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    "Notes are stored as .md files in",
                    color = TEXT2,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    "/sdcard/Documents/OpenClaw/notes/",
                    color = CYAN,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items(notes, key = { it.file.absolutePath }) { note ->
                    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()) }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SURFACE),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { onOpenNote(note.file.absolutePath) },
                                onLongClick = { showDeleteDialog = note }
                            )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Description,
                                    contentDescription = null,
                                    tint = CYAN,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    note.title,
                                    color = TEXT,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (note.preview.isNotBlank()) {
                                Text(
                                    note.preview,
                                    color = TEXT2,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 2,
                                    modifier = Modifier.padding(top = 4.dp, start = 26.dp)
                                )
                            }
                            Text(
                                dateFormat.format(Date(note.lastModified)),
                                color = TEXT2.copy(alpha = 0.6f),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(top = 4.dp, start = 26.dp)
                            )
                        }
                    }
                }
            }
        }

        // FAB
        FloatingActionButton(
            onClick = { showCreateDialog = true },
            containerColor = CYAN,
            contentColor = BG,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = "Create note")
        }
    }

    // Create note dialog
    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false; newNoteTitle = "" },
            containerColor = SURFACE,
            title = {
                Text("New Note", color = TEXT, fontFamily = FontFamily.Monospace)
            },
            text = {
                OutlinedTextField(
                    value = newNoteTitle,
                    onValueChange = { newNoteTitle = it },
                    label = { Text("Title", color = TEXT2, fontFamily = FontFamily.Monospace) },
                    textStyle = TextStyle(color = TEXT, fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CYAN,
                        unfocusedBorderColor = BORDER,
                        cursorColor = CYAN,
                        focusedContainerColor = BG,
                        unfocusedContainerColor = BG
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newNoteTitle.isNotBlank()) {
                            val sanitized = newNoteTitle.replace(Regex("[/\\\\:*?\"<>|]"), "_")
                            val file = File(notesDir(), "$sanitized.md")
                            file.writeText("# $sanitized\n\n")
                            showCreateDialog = false
                            newNoteTitle = ""
                            onOpenNote(file.absolutePath)
                            refreshNotes()
                        }
                    }
                ) {
                    Text("Create", color = GREEN, fontFamily = FontFamily.Monospace)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false; newNoteTitle = "" }) {
                    Text("Cancel", color = TEXT2, fontFamily = FontFamily.Monospace)
                }
            }
        )
    }

    // Delete confirmation dialog
    if (showDeleteDialog != null) {
        val note = showDeleteDialog!!
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            containerColor = SURFACE,
            title = {
                Text("Delete Note", color = TEXT, fontFamily = FontFamily.Monospace)
            },
            text = {
                Text(
                    "Delete \"${note.title}\"? This cannot be undone.",
                    color = TEXT2,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            note.file.delete()
                            notes = loadNotes()
                        }
                        showDeleteDialog = null
                    }
                ) {
                    Text("Delete", color = Color(0xFFF85149), fontFamily = FontFamily.Monospace)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("Cancel", color = TEXT2, fontFamily = FontFamily.Monospace)
                }
            }
        )
    }
}

@Composable
private fun NoteEditor(filePath: String, onBack: () -> Unit) {
    var content by remember { mutableStateOf("") }
    var isEditing by remember { mutableStateOf(false) }
    var isDirty by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val file = remember { File(filePath) }

    LaunchedEffect(filePath) {
        withContext(Dispatchers.IO) {
            content = if (file.exists()) file.readText() else ""
        }
    }

    Column(Modifier.fillMaxSize().background(BG)) {
        // Top bar
        Row(
            Modifier
                .fillMaxWidth()
                .background(SURFACE)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                if (isDirty) {
                    scope.launch(Dispatchers.IO) { file.writeText(content) }
                }
                onBack()
            }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TEXT)
            }
            Text(
                file.nameWithoutExtension,
                color = TEXT,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            if (isDirty) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(Color(0xFFD29922), RoundedCornerShape(4.dp))
                )
                Spacer(Modifier.width(8.dp))
            }
            if (isEditing && isDirty) {
                IconButton(onClick = {
                    scope.launch(Dispatchers.IO) { file.writeText(content) }
                    isDirty = false
                }) {
                    Icon(Icons.Default.Save, contentDescription = "Save", tint = GREEN)
                }
            }
            IconButton(onClick = { isEditing = !isEditing }) {
                Icon(
                    if (isEditing) Icons.Default.Visibility else Icons.Default.Edit,
                    contentDescription = if (isEditing) "Preview" else "Edit",
                    tint = CYAN
                )
            }
        }

        Divider(color = BORDER, thickness = 1.dp)

        if (isEditing) {
            // Edit mode
            OutlinedTextField(
                value = content,
                onValueChange = { content = it; isDirty = true },
                modifier = Modifier.fillMaxSize().padding(0.dp),
                textStyle = TextStyle(
                    color = TEXT,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    cursorColor = CYAN,
                    focusedContainerColor = BG,
                    unfocusedContainerColor = BG
                )
            )
        } else {
            // Read mode
            SelectionContainer {
                Text(
                    content,
                    color = TEXT,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        }
    }
}

@Composable
private fun ConfigTab() {
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
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        item {
            Text(
                "Agent configuration files",
                color = TEXT2,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(4.dp))
        }

        items(allFiles) { (name, _) ->
            val isSelected = selectedFileName == name
            val desc = fileDescriptions[name] ?: "Workspace file"
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) Color(0xFF1C2333) else SURFACE
                ),
                shape = RoundedCornerShape(10.dp),
                border = if (isSelected) BorderStroke(1.dp, CYAN) else null,
                modifier = Modifier.fillMaxWidth().clickable {
                    selectedFileName = name
                    editContent = com.openclaw.android.ai.Bootstrap.readFile(name)
                    isDirty = false
                    saveMessage = null
                    scope.launch { listState.animateScrollToItem(allFiles.size + 2) }
                }
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Description,
                        null,
                        tint = if (isSelected) CYAN else TEXT2,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            name,
                            color = if (isSelected) CYAN else TEXT,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            desc,
                            color = TEXT2,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    if (isSelected && isDirty) {
                        Box(
                            Modifier.size(8.dp)
                                .background(Color(0xFFD29922), RoundedCornerShape(4.dp))
                        )
                    }
                }
            }
        }

        if (selectedFileName != null) {
            item {
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Editing: $selectedFileName",
                        color = CYAN,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Row {
                        if (saveMessage != null) {
                            Text(
                                saveMessage!!,
                                color = GREEN,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                        Button(
                            onClick = {
                                selectedFileName?.let { name ->
                                    val wsFile = File(
                                        com.openclaw.android.ai.Bootstrap.agentConfigDir(),
                                        name
                                    )
                                    val wsFile2 = File(
                                        com.openclaw.android.OpenClawApplication.instance.filesDir,
                                        "workspace/$name"
                                    )
                                    if (wsFile2.exists()) wsFile2.writeText(editContent)
                                    else wsFile.writeText(editContent)
                                    isDirty = false
                                    saveMessage = "Saved!"
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isDirty) GREEN else BORDER
                            ),
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
                    textStyle = LocalTextStyle.current.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = TEXT,
                        lineHeight = 18.sp
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CYAN,
                        unfocusedBorderColor = BORDER,
                        cursorColor = CYAN,
                        focusedContainerColor = Color(0xFF010409),
                        unfocusedContainerColor = Color(0xFF010409)
                    )
                )
            }
        }
    }
}
