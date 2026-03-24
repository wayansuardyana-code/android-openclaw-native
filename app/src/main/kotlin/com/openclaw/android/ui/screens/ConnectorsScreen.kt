package com.openclaw.android.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openclaw.android.ai.AgentConfig

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

// Available services for "+" button
private data class ServiceOption(val id: String, val name: String, val desc: String, val category: String)
private val AVAILABLE_SERVICES = listOf(
    ServiceOption("github", "GitHub", "Repos, issues, PRs", "dev"),
    ServiceOption("vercel", "Vercel", "Deployments, projects", "dev"),
    ServiceOption("supabase", "Supabase", "Database via PostgREST", "dev"),
    ServiceOption("google_workspace", "Google Workspace", "Drive, Sheets, Gmail", "dev"),
    ServiceOption("ssh", "SSH Remote", "Remote server shell", "dev"),
    ServiceOption("postgres", "PostgreSQL", "SQL via SSH tunnel", "dev"),
    ServiceOption("telegram", "Telegram Bot", "Bot messaging", "dev"),
    ServiceOption("cloudflare", "Cloudflare", "DNS, Workers", "dev"),
    ServiceOption("notion", "Notion", "Pages, databases", "dev"),
    ServiceOption("linear", "Linear", "Issues, projects", "dev"),
    ServiceOption("slack", "Slack", "Messages, channels", "dev"),
    ServiceOption("discord", "Discord", "Bot, channels", "dev"),
    ServiceOption("stripe", "Stripe", "Payments", "dev"),
    ServiceOption("resend", "Resend", "Email sending", "dev"),
    ServiceOption("upstash", "Upstash Redis", "Key-value cache", "dev"),
)

// Built-in active skills (always available, no config needed)
private data class SkillInfo(val name: String, val desc: String, val category: String)
private val BUILTIN_SKILLS = listOf(
    SkillInfo("android_read_screen", "Read screen of any app", "Device Control"),
    SkillInfo("android_tap", "Tap screen coordinates", "Device Control"),
    SkillInfo("android_swipe", "Swipe / scroll", "Device Control"),
    SkillInfo("android_type_text", "Type in text fields", "Device Control"),
    SkillInfo("android_open_app", "Launch any app", "Device Control"),
    SkillInfo("android_read_notifications", "Read all notifications", "Device Control"),
    SkillInfo("android_press_back / home", "Navigation buttons", "Device Control"),
    SkillInfo("run_shell_command", "Execute shell commands", "System"),
    SkillInfo("web_search", "DuckDuckGo search", "Web"),
    SkillInfo("web_scrape", "Fetch & parse web pages", "Web"),
    SkillInfo("http_request", "Call any REST API", "Web"),
    SkillInfo("calculator", "Math expressions (exp4j)", "Utility"),
    SkillInfo("generate_csv", "Create CSV files", "File Generation"),
    SkillInfo("generate_xlsx", "Create Excel files (FastExcel)", "File Generation"),
    SkillInfo("generate_pdf", "Create PDF documents", "File Generation"),
    SkillInfo("read_file / write_file / list_files", "File system access", "Utility"),
    SkillInfo("spawn_sub_agent", "Delegate to background agents", "Orchestration"),
    SkillInfo("install_python", "Install Python 3.13 runtime", "Python"),
    SkillInfo("run_python", "Execute Python scripts", "Python"),
    SkillInfo("pip_install", "Install Python packages", "Python"),
    SkillInfo("Infographic Generator", "PNG infographics from data (Pillow)", "Python Skill"),
    SkillInfo("MarkItDown", "Convert PDF/Word/Excel to markdown", "Python Skill"),
    SkillInfo("Data Analysis", "pandas + matplotlib + scipy", "Python Skill"),
)

@Composable
fun ConnectorsScreen() {
    val context = LocalContext.current

    // Dynamic: only show services with saved tokens
    val connectedServices = remember { mutableStateListOf<String>() }
    LaunchedEffect(Unit) {
        connectedServices.clear()
        AVAILABLE_SERVICES.forEach { svc ->
            if (AgentConfig.getKeyForProvider(svc.id).isNotBlank()) connectedServices.add(svc.id)
        }
    }

    // Add service dialog
    var showAddService by remember { mutableStateOf(false) }
    var addServiceId by remember { mutableStateOf("") }
    var addServiceToken by remember { mutableStateOf("") }
    var addServiceDropdown by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(BG).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            Text("Connect", color = TEXT, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text("Tools, services & skills", color = TEXT2, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(8.dp))
        }

        // ── DEVELOPER TOOLS (dynamic — only connected ones) ──
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("DEVELOPER TOOLS")
                IconButton(onClick = { showAddService = true; addServiceId = ""; addServiceToken = "" }) {
                    Icon(Icons.Default.Add, "Add", tint = CYAN, modifier = Modifier.size(20.dp))
                }
            }
        }

        if (connectedServices.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = SURFACE), shape = RoundedCornerShape(10.dp)) {
                    Text("No services connected yet. Tap + to add.", color = TEXT2, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(16.dp))
                }
            }
        }

        items(connectedServices.toList()) { id ->
            val svc = AVAILABLE_SERVICES.find { it.id == id }
            val name = svc?.name ?: id
            val desc = svc?.desc ?: ""
            val token = AgentConfig.getKeyForProvider(id)

            ConnectedServiceCard(name, desc, token, context) {
                AgentConfig.setKeyForProvider(id, "")
                connectedServices.remove(id)
            }
        }

        // ── DATABASE ──
        item { SectionLabel("DATABASE") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SURFACE), shape = RoundedCornerShape(10.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(GREEN, RoundedCornerShape(4.dp)))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("SQLite (Built-in)", color = TEXT, fontSize = 14.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)
                        Text("Local database + vector memory", color = TEXT2, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    Text("Active", color = GREEN, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        // ── ACTIVE SKILLS (all built-in, no config needed) ──
        item { SectionLabel("ACTIVE SKILLS (${BUILTIN_SKILLS.size})") }

        // Group by category
        val categories = BUILTIN_SKILLS.map { it.category }.distinct()
        categories.forEach { cat ->
            val skills = BUILTIN_SKILLS.filter { it.category == cat }
            item {
                Text(cat.uppercase(), color = CYAN.copy(alpha = 0.6f), fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp, modifier = Modifier.padding(top = 4.dp))
            }
            items(skills) { skill ->
                Card(colors = CardDefaults.cardColors(containerColor = SURFACE), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).background(GREEN, RoundedCornerShape(3.dp)))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(skill.name, color = TEXT, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Text(skill.desc, color = TEXT2, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }

    // ── ADD SERVICE DIALOG ──
    if (showAddService) {
        AlertDialog(
            onDismissRequest = { showAddService = false },
            containerColor = SURFACE,
            title = { Text("Connect Service", color = TEXT, fontFamily = FontFamily.Monospace) },
            text = {
                Column {
                    Box {
                        OutlinedButton(onClick = { addServiceDropdown = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, BORDER), colors = ButtonDefaults.outlinedButtonColors(contentColor = TEXT)) {
                            val svcName = AVAILABLE_SERVICES.find { it.id == addServiceId }?.name ?: "Select service..."
                            Text(svcName, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Icon(Icons.Default.ArrowDropDown, null)
                        }
                        DropdownMenu(expanded = addServiceDropdown, onDismissRequest = { addServiceDropdown = false }, modifier = Modifier.background(Color(0xFF1C2333)).heightIn(max = 300.dp)) {
                            AVAILABLE_SERVICES.filter { svc -> svc.id !in connectedServices }.forEach { svc ->
                                DropdownMenuItem(
                                    text = { Column { Text(svc.name, color = TEXT, fontFamily = FontFamily.Monospace, fontSize = 13.sp); Text(svc.desc, color = TEXT2, fontFamily = FontFamily.Monospace, fontSize = 10.sp) } },
                                    onClick = { addServiceId = svc.id; addServiceDropdown = false }
                                )
                            }
                        }
                    }
                    if (addServiceId.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = addServiceToken, onValueChange = { addServiceToken = it },
                            placeholder = { Text("API token / key...", color = Color(0xFF484F58), fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation(),
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = TEXT),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CYAN, unfocusedBorderColor = BORDER, cursorColor = CYAN))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (addServiceId.isNotBlank() && addServiceToken.isNotBlank()) {
                        AgentConfig.setKeyForProvider(addServiceId, addServiceToken)
                        connectedServices.add(addServiceId)
                        showAddService = false
                        Toast.makeText(context, "Connected!", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Connect", color = GREEN, fontFamily = FontFamily.Monospace) }
            },
            dismissButton = { TextButton(onClick = { showAddService = false }) { Text("Cancel", color = TEXT2, fontFamily = FontFamily.Monospace) } }
        )
    }
}

@Composable
private fun ConnectedServiceCard(name: String, desc: String, token: String, context: Context, onRemove: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = SURFACE), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(GREEN, RoundedCornerShape(4.dp)))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = TEXT, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text(
                    if (visible) token else token.take(6) + "•".repeat(minOf(12, token.length - 6).coerceAtLeast(0)),
                    color = TEXT2, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1
                )
            }
            IconButton(onClick = { visible = !visible }, modifier = Modifier.size(28.dp)) {
                Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, tint = TEXT2, modifier = Modifier.size(14.dp))
            }
            IconButton(onClick = {
                val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clip.setPrimaryClip(ClipData.newPlainText("token", token))
                Toast.makeText(context, "Copied!", Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.ContentCopy, null, tint = TEXT2, modifier = Modifier.size(14.dp))
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Delete, null, tint = RED, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = TEXT2, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
}
