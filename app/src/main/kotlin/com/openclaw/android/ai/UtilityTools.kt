package com.openclaw.android.ai

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.openclaw.android.OpenClawApplication
import com.openclaw.android.util.ServiceState
import net.objecthunter.exp4j.ExpressionBuilder
import org.jsoup.Jsoup
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import org.dhatim.fastexcel.Workbook
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*

/**
 * Non-Android utility tools: shell, web scraper, calculator, file gen.
 * These use the deps already in build.gradle (Jsoup, exp4j, FastExcel, kotlin-csv, SSHJ).
 */
object UtilityTools {

    private val sharedHttpClient = HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
    }

    fun getToolDefinitions(): List<ToolDef> = listOf(
        ToolDef(
            name = "run_shell_command",
            description = "Execute a shell command on the Android device. Returns stdout and stderr. Use for: listing files, checking processes, network info, etc.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "command" to mapOf("type" to "string", "description" to "Shell command to execute")
                ),
                "required" to listOf("command")
            )
        ),
        ToolDef(
            name = "web_scrape",
            description = "Fetch a web page and extract its text content, title, and links. Good for reading articles, checking websites, getting information.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "url" to mapOf("type" to "string", "description" to "URL to fetch"),
                    "selector" to mapOf("type" to "string", "description" to "Optional CSS selector to extract specific content")
                ),
                "required" to listOf("url")
            )
        ),
        ToolDef(
            name = "web_search",
            description = "Search the web using DuckDuckGo. Returns top results with titles, URLs, and snippets.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "query" to mapOf("type" to "string", "description" to "Search query")
                ),
                "required" to listOf("query")
            )
        ),
        ToolDef(
            name = "brave_search",
            description = "Search the web using Brave Search API. Better than web_search for factual queries. Needs brave API key configured in Settings → Services.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "query" to mapOf("type" to "string", "description" to "Search query"),
                    "count" to mapOf("type" to "number", "description" to "Number of results (default 5)")
                ),
                "required" to listOf("query")
            )
        ),
        ToolDef(
            name = "exa_search",
            description = "Neural search using Exa API. Best for research-grade semantic search. Needs exa API key configured in Settings → Services.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "query" to mapOf("type" to "string", "description" to "Search query"),
                    "num_results" to mapOf("type" to "number", "description" to "Number of results (default 5)"),
                    "type" to mapOf("type" to "string", "description" to "Search type: keyword or neural (default neural)")
                ),
                "required" to listOf("query")
            )
        ),
        ToolDef(
            name = "firecrawl_scrape",
            description = "Scrape a webpage and get clean markdown content using Firecrawl. Better than web_scrape for complex pages. Needs firecrawl API key configured in Settings → Services.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "url" to mapOf("type" to "string", "description" to "URL to scrape")
                ),
                "required" to listOf("url")
            )
        ),
        ToolDef(
            name = "calculator",
            description = "Evaluate a math expression. Supports: +, -, *, /, ^, sqrt, sin, cos, tan, log, abs, ceil, floor, pi, e. Example: '2 * sin(pi/4) + sqrt(16)'",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "expression" to mapOf("type" to "string", "description" to "Math expression to evaluate")
                ),
                "required" to listOf("expression")
            )
        ),
        ToolDef(
            name = "read_file",
            description = "Read the contents of a file on the device.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "path" to mapOf("type" to "string", "description" to "Absolute file path")
                ),
                "required" to listOf("path")
            )
        ),
        ToolDef(
            name = "write_file",
            description = "Write content to a file on the device. Creates parent directories if needed. WARNING: Do NOT use this for workspace/config files (SOUL.md, USER.md, memory.md, etc.) — use update_workspace_file instead!",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "path" to mapOf("type" to "string", "description" to "Absolute file path"),
                    "content" to mapOf("type" to "string", "description" to "File content to write")
                ),
                "required" to listOf("path", "content")
            )
        ),
        ToolDef(
            name = "list_files",
            description = "List files and directories at a given path.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "path" to mapOf("type" to "string", "description" to "Directory path to list")
                ),
                "required" to listOf("path")
            )
        ),
        ToolDef(
            name = "generate_csv",
            description = "Generate a CSV file. Provide headers and rows as arrays.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "filename" to mapOf("type" to "string", "description" to "Output filename (saved to Documents/OpenClaw/)"),
                    "headers" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                    "rows" to mapOf("type" to "array", "items" to mapOf("type" to "array", "items" to mapOf("type" to "string")))
                ),
                "required" to listOf("filename", "headers", "rows")
            )
        ),
        ToolDef(
            name = "http_request",
            description = "Make an HTTP GET or POST request to any URL. Good for calling APIs.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "url" to mapOf("type" to "string", "description" to "Request URL"),
                    "method" to mapOf("type" to "string", "description" to "GET or POST (default GET)"),
                    "body" to mapOf("type" to "string", "description" to "Request body for POST"),
                    "headers" to mapOf("type" to "object", "description" to "Optional headers as key-value pairs")
                ),
                "required" to listOf("url")
            )
        ),
        ToolDef(
            name = "spawn_sub_agent",
            description = "Spawn a sub-agent to handle a task in the background. Use this for long-running tasks so the chat stays responsive. The sub-agent will notify the user when done.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "task_title" to mapOf("type" to "string", "description" to "Short title for the task (shown in kanban)"),
                    "prompt" to mapOf("type" to "string", "description" to "Detailed instructions for the sub-agent")
                ),
                "required" to listOf("task_title", "prompt")
            )
        ),
        ToolDef(
            name = "list_sub_agents",
            description = "List all running and completed sub-agents with their status and results.",
            inputSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        ),
        ToolDef(
            name = "read_workspace_file",
            description = "Read a workspace or agent config file by name. Available files: SOUL.md, USER.md, AGENTS.md, TOOLS.md, HEARTBEAT.md (workspace), identity.md, system_prompt.md, memory.md, skills.md, bootstrap.md (agent_config). Use this to check current content before updating.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "name" to mapOf("type" to "string", "description" to "File name, e.g. 'USER.md', 'memory.md'")
                ),
                "required" to listOf("name")
            )
        ),
        ToolDef(
            name = "update_workspace_file",
            description = "Update a workspace or agent config file by name. Use to save learned facts to memory.md, update USER.md with owner info, customize identity.md, add custom instructions to system_prompt.md, etc. The file is overwritten with the new content. Available files: SOUL.md, USER.md, AGENTS.md, TOOLS.md, HEARTBEAT.md, identity.md, system_prompt.md, memory.md, skills.md, bootstrap.md.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "name" to mapOf("type" to "string", "description" to "File name, e.g. 'USER.md', 'memory.md'"),
                    "content" to mapOf("type" to "string", "description" to "New file content (replaces entire file)")
                ),
                "required" to listOf("name", "content")
            )
        ),
        ToolDef(
            name = "memory_store",
            description = "Store a fact/memory in the SQLite database for persistent recall. Better than text files — supports search, importance ranking, and type categorization. Use for: learned user preferences, important dates, task outcomes, discovered patterns.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "content" to mapOf("type" to "string", "description" to "The memory/fact to store"),
                    "type" to mapOf("type" to "string", "description" to "Category: general, skill, fact, conversation, preference",
                        "enum" to listOf("general", "skill", "fact", "conversation", "preference")),
                    "importance" to mapOf("type" to "number", "description" to "0.0-1.0, how important this memory is (default 0.5)")
                ),
                "required" to listOf("content")
            )
        ),
        ToolDef(
            name = "memory_search",
            description = "Search stored memories by keyword. Returns matching memories sorted by importance. Use to recall: user preferences, past task outcomes, learned facts, skills.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "query" to mapOf("type" to "string", "description" to "Search keyword or phrase"),
                    "type" to mapOf("type" to "string", "description" to "Optional: filter by type (general, skill, fact, conversation, preference)"),
                    "limit" to mapOf("type" to "number", "description" to "Max results (default 10)")
                ),
                "required" to listOf("query")
            )
        ),
        ToolDef(
            name = "generate_xlsx",
            description = "Generate an Excel (.xlsx) file. Supports MULTIPLE SHEETS. For single sheet: use headers+rows. For multi-sheet: use 'sheets' array. Saved to Documents/OpenClaw/.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "filename" to mapOf("type" to "string", "description" to "Output filename ending in .xlsx"),
                    "headers" to mapOf("type" to "array", "items" to mapOf("type" to "string"), "description" to "Column headers (single sheet mode)"),
                    "rows" to mapOf("type" to "array", "items" to mapOf("type" to "array", "items" to mapOf("type" to "string")), "description" to "Data rows (single sheet mode)"),
                    "sheets" to mapOf("type" to "array", "description" to "Multi-sheet mode: array of {name, headers, rows} objects. Overrides headers/rows if present.",
                        "items" to mapOf("type" to "object", "properties" to mapOf(
                            "name" to mapOf("type" to "string"),
                            "headers" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                            "rows" to mapOf("type" to "array", "items" to mapOf("type" to "array", "items" to mapOf("type" to "string")))
                        )))
                ),
                "required" to listOf("filename")
            )
        ),
        ToolDef(
            name = "generate_pdf",
            description = "Generate a PDF file with text content and optional title. Saved to app documents folder.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "filename" to mapOf("type" to "string", "description" to "Output filename ending in .pdf (saved to Documents/OpenClaw/)"),
                    "content" to mapOf("type" to "string", "description" to "Text content for the PDF body"),
                    "title" to mapOf("type" to "string", "description" to "Optional title displayed at the top of the PDF")
                ),
                "required" to listOf("filename", "content")
            )
        ),
        ToolDef(
            name = "send_telegram_message",
            description = "Send a text message to Telegram. Defaults to last active chat. Use target='group' to send to the group chat instead of DM.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "text" to mapOf("type" to "string", "description" to "Message text to send (supports Markdown: *bold*, _italic_, `code`)"),
                    "target" to mapOf("type" to "string", "description" to "Where to send: 'dm' (default, last DM chat) or 'group' (last group chat)", "enum" to listOf("dm", "group")),
                    "chat_id" to mapOf("type" to "number", "description" to "Optional: explicit chat ID to send to (overrides target)")
                ),
                "required" to listOf("text")
            )
        ),
        ToolDef(
            name = "send_telegram_photo",
            description = "Send a photo/image file (JPG, PNG, etc.) to the current Telegram chat. Use after take_screenshot to send screenshots to the user. For non-image files (XLSX, PDF, CSV, etc.) use send_telegram_document instead.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "file_path" to mapOf("type" to "string", "description" to "Absolute path to the image file to send"),
                    "caption" to mapOf("type" to "string", "description" to "Optional caption for the photo")
                ),
                "required" to listOf("file_path")
            )
        ),
        ToolDef(
            name = "send_telegram_document",
            description = "Send a file (XLSX, PDF, CSV, TXT, or any file type) to the current Telegram chat as a document. Use this for spreadsheets, reports, and any non-image files. For images/screenshots use send_telegram_photo instead.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "file_path" to mapOf("type" to "string", "description" to "Absolute path to the file to send"),
                    "caption" to mapOf("type" to "string", "description" to "Optional caption for the document")
                ),
                "required" to listOf("file_path")
            )
        ),
        ToolDef(
            name = "send_discord_message",
            description = "Send a message to Discord via webhook URL or bot token + channel ID. Use webhook for simple posting, bot token for richer features.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "text" to mapOf("type" to "string", "description" to "Message text to send"),
                    "webhook_url" to mapOf("type" to "string", "description" to "Discord webhook URL (optional if bot token configured)"),
                    "channel_id" to mapOf("type" to "string", "description" to "Discord channel ID (required if using bot token)")
                ),
                "required" to listOf("text")
            )
        ),
        ToolDef(
            name = "send_slack_message",
            description = "Send a message to Slack via webhook URL or bot token + channel. Use webhook for simple posting, bot token (xoxb-) for richer features.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "text" to mapOf("type" to "string", "description" to "Message text to send"),
                    "webhook_url" to mapOf("type" to "string", "description" to "Slack webhook URL (optional if bot token configured)"),
                    "channel" to mapOf("type" to "string", "description" to "Slack channel name or ID (required if using bot token)")
                ),
                "required" to listOf("text")
            )
        ),
        ToolDef(
            name = "schedule_task",
            description = "Schedule a recurring task. Agent will automatically execute it at the specified time/interval.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "prompt" to mapOf("type" to "string", "description" to "What to do (e.g., 'check weather in Bali and send to Telegram')"),
                    "interval_minutes" to mapOf("type" to "number", "description" to "Run every N minutes (e.g., 60 for hourly, 1440 for daily)"),
                    "hour" to mapOf("type" to "number", "description" to "Run at specific hour (0-23, e.g., 8 for 8am). Combines with interval for daily tasks."),
                    "gateway" to mapOf("type" to "string", "description" to "Where to send results: chat, telegram, group (default: telegram)")
                ),
                "required" to listOf("prompt")
            )
        ),
        ToolDef(
            name = "list_scheduled_tasks",
            description = "List all scheduled tasks with their next run time and status.",
            inputSchema = mapOf("type" to "object", "properties" to mapOf<String, Any>())
        ),
        ToolDef(
            name = "cancel_scheduled_task",
            description = "Cancel a scheduled task by its ID.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "id" to mapOf("type" to "number", "description" to "Task ID to cancel")
                ),
                "required" to listOf("id")
            )
        ),
    )

    suspend fun executeTool(name: String, args: JsonObject): String {
        ServiceState.addLog("Utility tool: $name")
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            when (name) {
                "memory_store" -> {
                    val content = args.get("content").asString
                    val type = args.get("type")?.asString ?: "general"
                    val importance = args.get("importance")?.asFloat ?: 0.5f
                    val db = com.openclaw.android.data.AppDatabase.getInstance(OpenClawApplication.instance)
                    val entity = com.openclaw.android.data.entity.MemoryEntity(
                        content = content, type = type, importance = importance, source = "agent"
                    )
                    val id = db.memoryDao().insert(entity)
                    ServiceState.addLog("Memory stored: id=$id type=$type")
                    """{"success":true,"id":$id,"type":"$type","importance":$importance}"""
                }
                "memory_search" -> {
                    val query = args.get("query").asString.lowercase()
                    val type = args.get("type")?.asString
                    val limit = args.get("limit")?.asInt ?: 10
                    val db = com.openclaw.android.data.AppDatabase.getInstance(OpenClawApplication.instance)
                    val all = db.memoryDao().getTopMemories(500)

                    // Fuzzy search: score by word overlap (not exact keyword match)
                    val queryWords = query.split(Regex("[\\s,.:;!?]+")).filter { it.length > 2 }.toSet()
                    val scored = all.mapNotNull { mem ->
                        if (type != null && mem.type != type) return@mapNotNull null
                        val memWords = mem.content.lowercase().split(Regex("[\\s,.:;!?]+")).filter { it.length > 2 }.toSet()
                        // Score = word overlap + exact substring bonus + importance weight
                        val overlap = queryWords.count { qw -> memWords.any { it.contains(qw) || qw.contains(it) } }
                        val exactBonus = if (mem.content.lowercase().contains(query)) 3f else 0f
                        val score = (overlap.toFloat() / queryWords.size.coerceAtLeast(1)) + exactBonus + (mem.importance * 0.5f)
                        if (score > 0.1f) mem to score else null
                    }.sortedByDescending { it.second }.take(limit)

                    scored.forEach { (mem, _) -> db.memoryDao().recordAccess(mem.id) }
                    val results = scored.map { (mem, score) ->
                        """{"id":${mem.id},"content":${Gson().toJson(mem.content.take(200))},"type":"${mem.type}","score":${"%.2f".format(score)},"created":"${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(mem.createdAt))}"}"""
                    }
                    """{"matches":${scored.size},"memories":[${results.joinToString(",")}]}"""
                }
                "run_shell_command" -> runShell(args.get("command").asString)
                "web_scrape" -> webScrape(args.get("url").asString, args.get("selector")?.asString)
                "web_search" -> webSearch(args.get("query").asString)
                "brave_search" -> braveSearch(args)
                "exa_search" -> exaSearch(args)
                "firecrawl_scrape" -> firecrawlScrape(args)
                "calculator" -> calculate(args.get("expression").asString)
                "read_file" -> readFile(args.get("path").asString)
                "write_file" -> writeFile(args.get("path").asString, args.get("content").asString)
                "list_files" -> listFiles(args.get("path").asString)
                "generate_csv" -> generateCsv(args)
                "generate_xlsx" -> generateXlsx(args)
                "generate_pdf" -> generatePdf(args)
                "http_request" -> httpRequest(args)
                "spawn_sub_agent" -> {
                    val title = args.get("task_title").asString
                    val prompt = args.get("prompt").asString
                    val config = AgentConfig.toLlmConfig()
                    SubAgentManager.spawn(title, prompt, config)
                }
                "list_sub_agents" -> {
                    val running = SubAgentManager.getRunning()
                    val completed = SubAgentManager.getCompletedResults()
                    val gson = Gson()
                    """{"running":${running.size},"completed":${completed.size},"agents":${gson.toJson(running.map { mapOf("id" to it.id, "title" to it.title, "status" to it.status) } + completed.map { mapOf("id" to it.id, "title" to it.title, "status" to it.status, "result" to (it.result?.take(500) ?: "")) })}}"""
                }
                "read_workspace_file" -> {
                    val fileName = args.get("name").asString
                    val content = Bootstrap.readFile(fileName)
                    if (content.isBlank()) """{"error":"File not found or empty: $fileName"}"""
                    else """{"name":"$fileName","content":${Gson().toJson(content)}}"""
                }
                "update_workspace_file" -> {
                    val fileName = args.get("name").asString
                    val content = args.get("content").asString
                    val validFiles = setOf("SOUL.md", "USER.md", "AGENTS.md", "TOOLS.md", "HEARTBEAT.md",
                        "identity.md", "system_prompt.md", "memory.md", "skills.md", "bootstrap.md")
                    if (fileName !in validFiles) {
                        """{"error":"Invalid file name: $fileName. Valid: ${validFiles.joinToString(", ")}"}"""
                    } else {
                        val wsDir = File(OpenClawApplication.instance.filesDir, "workspace")
                        val cfgDir = File(OpenClawApplication.instance.filesDir, "agent_config")
                        val wsFile = File(wsDir, fileName)
                        val cfgFile = File(cfgDir, fileName)
                        val target = when {
                            wsFile.exists() -> wsFile
                            cfgFile.exists() -> cfgFile
                            fileName.uppercase() == fileName -> { wsDir.mkdirs(); File(wsDir, fileName) }
                            else -> { cfgDir.mkdirs(); File(cfgDir, fileName) }
                        }
                        target.writeText(content)
                        ServiceState.addLog("Workspace updated: $fileName (${content.length} chars)")
                        """{"success":true,"name":"$fileName","size":${target.length()}}"""
                    }
                }
                "send_telegram_message" -> {
                    val text = args.get("text").asString
                    val token = AgentConfig.getKeyForProvider("telegram")
                    if (token.isBlank()) return@withContext """{"error":"Telegram bot token not configured"}"""
                    val target = args.get("target")?.asString ?: "dm"
                    val explicitId = args.get("chat_id")?.asLong
                    val chatId = explicitId
                        ?: if (target == "group") com.openclaw.android.service.TelegramBotService.lastGroupChatId
                        else com.openclaw.android.service.TelegramBotService.lastChatId
                    if (chatId == 0L) return@withContext """{"error":"No active Telegram ${if (target == "group") "group" else "chat"}. Send a message to the bot first."}"""

                    try {
                        val body = com.google.gson.JsonObject().apply {
                            addProperty("chat_id", chatId)
                            addProperty("text", text)
                            addProperty("parse_mode", "Markdown")
                        }
                        val resp = sharedHttpClient.post("https://api.telegram.org/bot$token/sendMessage") {
                            contentType(ContentType.Application.Json)
                            setBody(body.toString())
                        }
                        val respText = resp.bodyAsText()
                        val ok = com.google.gson.JsonParser.parseString(respText).asJsonObject.get("ok")?.asBoolean ?: false
                        if (!ok) {
                            // Retry without markdown
                            val plain = com.google.gson.JsonObject().apply {
                                addProperty("chat_id", chatId)
                                addProperty("text", text)
                            }
                            sharedHttpClient.post("https://api.telegram.org/bot$token/sendMessage") {
                                contentType(ContentType.Application.Json)
                                setBody(plain.toString())
                            }
                        }
                        """{"success":true,"chat_id":$chatId,"length":${text.length}}"""
                    } catch (e: Exception) {
                        """{"error":"Failed to send message: ${e.message?.replace("\"", "'")}"}"""
                    }
                }
                "send_telegram_photo" -> {
                    val filePath = args.get("file_path").asString
                    val caption = args.get("caption")?.asString ?: ""
                    val file = File(filePath)
                    if (!file.exists()) return@withContext """{"error":"File not found: $filePath"}"""

                    val token = AgentConfig.getKeyForProvider("telegram")
                    if (token.isBlank()) return@withContext """{"error":"Telegram bot token not configured"}"""

                    val chatId = com.openclaw.android.service.TelegramBotService.lastChatId
                    if (chatId == 0L) return@withContext """{"error":"No active Telegram chat"}"""

                    try {
                        sharedHttpClient.post("https://api.telegram.org/bot$token/sendPhoto") {
                            setBody(io.ktor.client.request.forms.MultiPartFormDataContent(
                                io.ktor.client.request.forms.formData {
                                    append("chat_id", chatId.toString())
                                    if (caption.isNotBlank()) append("caption", caption)
                                    append("photo", file.readBytes(), io.ktor.http.Headers.build {
                                        append(io.ktor.http.HttpHeaders.ContentType, "image/png")
                                        append(io.ktor.http.HttpHeaders.ContentDisposition, "filename=${file.name}")
                                    })
                                }
                            ))
                        }
                        """{"success":true,"chat_id":$chatId}"""
                    } catch (e: Exception) {
                        """{"error":"Failed to send photo: ${e.message?.replace("\"", "'")}"}"""
                    }
                }
                "send_telegram_document" -> {
                    val filePath = args.get("file_path").asString
                    val caption = args.get("caption")?.asString ?: ""
                    val file = File(filePath)
                    if (!file.exists()) return@withContext """{"error":"File not found: $filePath"}"""

                    val token = AgentConfig.getKeyForProvider("telegram")
                    if (token.isBlank()) return@withContext """{"error":"Telegram bot token not configured"}"""

                    val chatId = com.openclaw.android.service.TelegramBotService.lastChatId
                    if (chatId == 0L) return@withContext """{"error":"No active Telegram chat"}"""

                    try {
                        sharedHttpClient.post("https://api.telegram.org/bot$token/sendDocument") {
                            setBody(io.ktor.client.request.forms.MultiPartFormDataContent(
                                io.ktor.client.request.forms.formData {
                                    append("chat_id", chatId.toString())
                                    if (caption.isNotBlank()) append("caption", caption)
                                    append("document", file.readBytes(), io.ktor.http.Headers.build {
                                        append(io.ktor.http.HttpHeaders.ContentType, "application/octet-stream")
                                        append(io.ktor.http.HttpHeaders.ContentDisposition, "filename=${file.name}")
                                    })
                                }
                            ))
                        }
                        """{"success":true,"chat_id":$chatId,"filename":"${file.name}","size":${file.length()}}"""
                    } catch (e: Exception) {
                        """{"error":"Failed to send document: ${e.message?.replace("\"", "'")}"}"""
                    }
                }
                "send_discord_message" -> {
                    val text = args.get("text").asString
                    val webhookUrl = args.get("webhook_url")?.asString
                    val channelId = args.get("channel_id")?.asString

                    try {
                        if (!webhookUrl.isNullOrBlank()) {
                            // Validate webhook URL to prevent SSRF
                            if (!webhookUrl.startsWith("https://discord.com/") && !webhookUrl.startsWith("https://discordapp.com/")) {
                                return@withContext """{"error":"Invalid Discord webhook URL. Must start with https://discord.com/ or https://discordapp.com/"}"""
                            }
                            // Webhook mode — simple POST
                            val body = JsonObject().apply { addProperty("content", text) }
                            val resp = sharedHttpClient.post(webhookUrl) {
                                contentType(ContentType.Application.Json)
                                setBody(body.toString())
                            }
                            """{"success":true,"method":"webhook","status":${resp.status.value}}"""
                        } else {
                            // Bot token mode
                            val token = AgentConfig.getKeyForProvider("discord")
                            if (token.isBlank()) return@withContext """{"error":"Discord bot token not configured and no webhook_url provided"}"""
                            if (channelId.isNullOrBlank()) return@withContext """{"error":"channel_id required when using bot token"}"""

                            val body = JsonObject().apply { addProperty("content", text) }
                            val resp = sharedHttpClient.post("https://discord.com/api/v10/channels/$channelId/messages") {
                                header("Authorization", "Bot $token")
                                contentType(ContentType.Application.Json)
                                setBody(body.toString())
                            }
                            val respText = resp.bodyAsText()
                            """{"success":true,"method":"bot","status":${resp.status.value},"response":${respText.take(200)}}"""
                        }
                    } catch (e: Exception) {
                        """{"error":"Discord send failed: ${e.message?.take(100)}"}"""
                    }
                }
                "send_slack_message" -> {
                    val text = args.get("text").asString
                    val webhookUrl = args.get("webhook_url")?.asString
                    val channel = args.get("channel")?.asString

                    try {
                        if (!webhookUrl.isNullOrBlank()) {
                            // Validate webhook URL to prevent SSRF
                            if (!webhookUrl.startsWith("https://hooks.slack.com/")) {
                                return@withContext """{"error":"Invalid Slack webhook URL. Must start with https://hooks.slack.com/"}"""
                            }
                            // Webhook mode
                            val body = JsonObject().apply { addProperty("text", text) }
                            val resp = sharedHttpClient.post(webhookUrl) {
                                contentType(ContentType.Application.Json)
                                setBody(body.toString())
                            }
                            """{"success":true,"method":"webhook","status":${resp.status.value}}"""
                        } else {
                            // Bot token mode
                            val token = AgentConfig.getKeyForProvider("slack")
                            if (token.isBlank()) return@withContext """{"error":"Slack bot token not configured and no webhook_url provided"}"""
                            if (channel.isNullOrBlank()) return@withContext """{"error":"channel required when using bot token"}"""

                            val body = JsonObject().apply {
                                addProperty("channel", channel)
                                addProperty("text", text)
                            }
                            val resp = sharedHttpClient.post("https://slack.com/api/chat.postMessage") {
                                header("Authorization", "Bearer $token")
                                contentType(ContentType.Application.Json)
                                setBody(body.toString())
                            }
                            val respText = resp.bodyAsText()
                            """{"success":true,"method":"bot","status":${resp.status.value},"response":${respText.take(200)}}"""
                        }
                    } catch (e: Exception) {
                        """{"error":"Slack send failed: ${e.message?.take(100)}"}"""
                    }
                }
                "schedule_task" -> {
                    val prompt = args.get("prompt").asString
                    val intervalMinutes = args.get("interval_minutes")?.asInt ?: 1440 // default daily
                    val hour = args.get("hour")?.asInt
                    val gateway = args.get("gateway")?.asString ?: "telegram"

                    val db = com.openclaw.android.data.AppDatabase.getInstance(OpenClawApplication.instance)

                    // Enforce max 20 scheduled tasks
                    if (db.scheduledTaskDao().getAll().size >= 20) {
                        return@withContext """{"error":"Maximum 20 scheduled tasks reached. Cancel existing tasks before adding new ones."}"""
                    }

                    // Calculate next run time
                    val now = System.currentTimeMillis()
                    val nextRun = if (hour != null) {
                        // Schedule for specific hour today or tomorrow
                        val cal = java.util.Calendar.getInstance()
                        cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
                        cal.set(java.util.Calendar.MINUTE, 0)
                        cal.set(java.util.Calendar.SECOND, 0)
                        if (cal.timeInMillis <= now) cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
                        cal.timeInMillis
                    } else {
                        now + (intervalMinutes * 60 * 1000L)
                    }

                    val task = com.openclaw.android.data.entity.ScheduledTaskEntity(
                        prompt = prompt,
                        intervalMinutes = intervalMinutes,
                        nextRunAt = nextRun,
                        gateway = gateway
                    )
                    val id = db.scheduledTaskDao().insert(task)

                    val nextRunStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(nextRun))
                    """{"success":true,"id":$id,"prompt":${Gson().toJson(prompt.take(80))},"next_run":"$nextRunStr","interval_minutes":$intervalMinutes,"gateway":"$gateway"}"""
                }

                "list_scheduled_tasks" -> {
                    val db = com.openclaw.android.data.AppDatabase.getInstance(OpenClawApplication.instance)
                    val tasks = db.scheduledTaskDao().getAll()
                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                    val list = tasks.map { t ->
                        """{"id":${t.id},"prompt":${Gson().toJson(t.prompt.take(60))},"enabled":${t.isEnabled},"interval_min":${t.intervalMinutes},"next_run":"${sdf.format(java.util.Date(t.nextRunAt))}","runs":${t.runCount}}"""
                    }
                    """{"count":${tasks.size},"tasks":[${list.joinToString(",")}]}"""
                }

                "cancel_scheduled_task" -> {
                    val id = args.get("id").asLong
                    val db = com.openclaw.android.data.AppDatabase.getInstance(OpenClawApplication.instance)
                    db.scheduledTaskDao().deleteById(id)
                    """{"success":true,"deleted_id":$id}"""
                }

                else -> """{"error":"Unknown tool: $name"}"""
            }
        } catch (e: Exception) {
            ServiceState.addLog("Utility tool error: $name — ${e.message}")
            """{"error":"${e.message?.replace("\"", "'")}"}"""
        }
        } // end withContext(IO)
    }

    private fun runShell(command: String): String {
        val blocklist = listOf(
            "rm -rf /", "rm -r /", "mkfs", "dd if=", ":(){ :|:&", "format",
            // Block data exfiltration
            "shared_prefs", "databases/", ".db",
            // Block network exfiltration
            "curl ", "wget ", "nc ", "ncat ", "netcat ",
            // Block dangerous operations
            "chmod 777", "su ", "mount ", "umount ",
            // Block package manipulation
            "pm uninstall", "pm disable", "pm clear"
        )
        val lowerCmd = command.lowercase()
        if (blocklist.any { lowerCmd.contains(it) }) {
            return """{"error":"Command blocked for security"}"""
        }

        val process = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .start()

        val output = BufferedReader(InputStreamReader(process.inputStream))
            .readText()
            .take(4000)

        val finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return """{"error":"Command timed out after 30 seconds"}"""
        }
        val exitCode = process.exitValue()
        val escaped = com.google.gson.Gson().toJson(output) // proper JSON escaping
        return """{"exitCode":$exitCode,"output":$escaped}"""
    }

    private fun webScrape(url: String, selector: String?): String {
        val fullUrl = if (url.startsWith("http")) url else "https://$url"
        if (!isUrlSafe(fullUrl)) return """{"error":"URL blocked: internal/private network addresses not allowed"}"""
        val conn = Jsoup.connect(fullUrl)
            .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml")
            .timeout(15000)
            .followRedirects(true)
            .ignoreHttpErrors(true)
        val doc = conn.get()

        val title = doc.title()
        val content = if (selector != null) {
            doc.select(selector).text()
        } else {
            doc.body().text().take(3000)
        }

        val gson = Gson()
        return """{"title":${gson.toJson(title)},"content":${gson.toJson(content.take(3000))}}"""
    }

    private fun webSearch(query: String): String {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val gson = Gson()

        // Try multiple search engines (DuckDuckGo blocks some Android requests)
        val searchUrls = listOf(
            "https://lite.duckduckgo.com/lite/?q=$encoded" to ".result-link" to ".result-snippet",
            "https://html.duckduckgo.com/html/?q=$encoded" to ".result__title" to ".result__snippet",
        )

        for ((urlSelectors, _) in searchUrls) {
            val (url, titleSel) = urlSelectors
            try {
                val conn = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Accept-Language", "en-US,en;q=0.9,id;q=0.8")
                    .timeout(10000)
                    .followRedirects(true)
                    .ignoreHttpErrors(true)
                val doc = conn.get()

                // Try DuckDuckGo lite format
                val links = doc.select("a.result-link")
                if (links.isNotEmpty()) {
                    val results = links.take(5).mapIndexed { i, el ->
                        val title = el.text()
                        val href = el.attr("href")
                        // Get snippet from next sibling
                        val snippet = el.parent()?.nextElementSibling()?.text() ?: ""
                        """{"rank":${i+1},"title":${gson.toJson(title)},"url":${gson.toJson(href)},"snippet":${gson.toJson(snippet.take(200))}}"""
                    }
                    return """{"query":${gson.toJson(query)},"results":[${results.joinToString(",")}]}"""
                }

                // Try DuckDuckGo HTML format
                val bodies = doc.select(".result__body")
                if (bodies.isNotEmpty()) {
                    val results = bodies.take(5).mapIndexed { i, el ->
                        val title = el.select(".result__title").text()
                        val snippet = el.select(".result__snippet").text()
                        val link = el.select(".result__url").text()
                        """{"rank":${i+1},"title":${gson.toJson(title)},"snippet":${gson.toJson(snippet)},"url":${gson.toJson(link)}}"""
                    }
                    return """{"query":${gson.toJson(query)},"results":[${results.joinToString(",")}]}"""
                }
            } catch (_: Exception) { continue }
        }

        // Final fallback: use web_scrape on a search-friendly site
        return try {
            val wttrResult = webScrape("https://www.google.com/search?q=$encoded&hl=en", "h3")
            """{"query":${gson.toJson(query)},"results":[],"fallback_scrape":${gson.toJson(wttrResult)},"note":"DuckDuckGo unavailable. Scraped Google instead."}"""
        } catch (_: Exception) {
            """{"query":${gson.toJson(query)},"results":[],"error":"All search engines failed. Try web_scrape on a specific URL."}"""
        }
    }

    /** Validate URL is not targeting internal/private networks (SSRF protection) */
    private fun isUrlSafe(url: String): Boolean {
        try {
            val parsed = java.net.URL(url)
            val host = parsed.host.lowercase()
            // Block private/internal networks
            if (host == "localhost" || host == "127.0.0.1" || host == "0.0.0.0") return false
            if (host.startsWith("10.")) return false
            if (host.startsWith("192.168.")) return false
            if (host.startsWith("172.") && host.split(".").getOrNull(1)?.toIntOrNull()?.let { it in 16..31 } == true) return false
            if (host == "169.254.169.254") return false // Cloud metadata
            if (parsed.protocol == "file") return false
            return true
        } catch (_: Exception) { return false }
    }

    /** Validate file path is not accessing app internal data directories */
    private fun isPathAllowed(path: String): Boolean {
        val normalized = java.io.File(path).canonicalPath
        val blocked = listOf(
            "/data/data/com.openclaw.android/shared_prefs",
            "/data/data/com.openclaw.android.debug/shared_prefs",
            "/data/data/com.openclaw.android/databases",
            "/data/data/com.openclaw.android.debug/databases"
        )
        return blocked.none { normalized.startsWith(it) }
    }

    private fun calculate(expression: String): String {
        val result = ExpressionBuilder(expression)
            .build()
            .evaluate()
        return """{"expression":"${expression.replace("\"", "'")}","result":$result}"""
    }

    private fun readFile(path: String): String {
        if (!isPathAllowed(path)) return """{"error":"Path blocked: cannot access app internal data directories"}"""
        val file = File(path)
        if (!file.exists()) return """{"error":"File not found: $path"}"""
        if (!file.canRead()) return """{"error":"Cannot read file: $path"}"""
        val content = file.readText().take(4000)
        return """{"path":"$path","size":${file.length()},"content":"${content.replace("\"", "\\\"").replace("\n", "\\n")}"}"""
    }

    private fun writeFile(path: String, content: String): String {
        if (!isPathAllowed(path)) return """{"error":"Path blocked: cannot access app internal data directories"}"""
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return """{"success":true,"path":"$path","size":${file.length()}}"""
    }

    private fun listFiles(path: String): String {
        val dir = File(path)
        if (!dir.exists()) return """{"error":"Directory not found: $path"}"""
        val files = dir.listFiles()?.map { f ->
            """{"name":"${f.name}","isDir":${f.isDirectory},"size":${f.length()}}"""
        } ?: emptyList()
        return """{"path":"$path","files":[${files.joinToString(",")}]}"""
    }

    private fun generateCsv(args: JsonObject): String {
        val filename = args.get("filename").asString
        val headers = args.getAsJsonArray("headers").map { it.asString }
        val rows = args.getAsJsonArray("rows").map { row ->
            row.asJsonArray.map { it.asString }
        }

        val dir = File(OpenClawApplication.instance.getExternalFilesDir(null), "documents")
        dir.mkdirs()
        val file = File(dir, filename)

        val sb = StringBuilder()
        sb.appendLine(headers.joinToString(",") { "\"$it\"" })
        rows.forEach { row ->
            sb.appendLine(row.joinToString(",") { "\"$it\"" })
        }
        file.writeText(sb.toString())

        return """{"success":true,"path":"${file.absolutePath}","rows":${rows.size}}"""
    }

    private fun generateXlsx(args: JsonObject): String {
        val filename = args.get("filename").asString
        val dir = File(OpenClawApplication.instance.getExternalFilesDir(null), "documents")
        dir.mkdirs()
        val file = File(dir, if (filename.endsWith(".xlsx")) filename else "$filename.xlsx")

        // Build sheet data — either from "sheets" array (multi) or "headers"+"rows" (single)
        data class SheetData(val name: String, val headers: List<String>, val rows: List<List<String>>)
        val sheetList = mutableListOf<SheetData>()

        val sheetsArray = args.getAsJsonArray("sheets")
        if (sheetsArray != null && sheetsArray.size() > 0) {
            // Multi-sheet mode
            for (s in sheetsArray) {
                val obj = s.asJsonObject
                val name = obj.get("name")?.asString ?: "Sheet${sheetList.size + 1}"
                val h = obj.getAsJsonArray("headers")?.map { it.asString } ?: emptyList()
                val r = obj.getAsJsonArray("rows")?.map { row -> row.asJsonArray.map { it.asString } } ?: emptyList()
                sheetList.add(SheetData(name, h, r))
            }
        } else {
            // Single sheet mode (backward compatible)
            val headers = args.getAsJsonArray("headers")?.map { it.asString } ?: emptyList()
            val rows = args.getAsJsonArray("rows")?.map { row -> row.asJsonArray.map { it.asString } } ?: emptyList()
            sheetList.add(SheetData("Sheet1", headers, rows))
        }

        FileOutputStream(file).use { fos ->
            val wb = Workbook(fos, "OpenClaw", "1.0")

            for (sheet in sheetList) {
                val ws = wb.newWorksheet(sheet.name)
                sheet.headers.forEachIndexed { col, header -> ws.value(0, col, header) }
                sheet.rows.forEachIndexed { rowIdx, row ->
                    row.forEachIndexed { col, cell ->
                        val num = cell.toDoubleOrNull()
                        if (num != null) ws.value(rowIdx + 1, col, num)
                        else ws.value(rowIdx + 1, col, cell)
                    }
                }
            }

            wb.finish()
        }

        val totalRows = sheetList.sumOf { it.rows.size }
        return """{"success":true,"path":"${file.absolutePath}","sheets":${sheetList.size},"total_rows":$totalRows}"""
    }

    private fun generatePdf(args: JsonObject): String {
        val filename = args.get("filename").asString
        val content = args.get("content").asString
        val title = args.get("title")?.asString

        val dir = File(OpenClawApplication.instance.getExternalFilesDir(null), "documents")
        dir.mkdirs()
        val file = File(dir, if (filename.endsWith(".pdf")) filename else "$filename.pdf")

        val pageWidth = 595  // A4 width in points (72 dpi)
        val pageHeight = 842 // A4 height in points
        val marginLeft = 50f
        val marginTop = 60f
        val marginRight = 50f
        val marginBottom = 60f
        val usableWidth = pageWidth - marginLeft - marginRight

        val document = PdfDocument()

        val bodyPaint = Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = 12f
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            isAntiAlias = true
        }

        val titlePaint = Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            isAntiAlias = true
        }

        val lineHeight = bodyPaint.textSize * 1.5f
        var pageNumber = 1
        var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
        var page = document.startPage(pageInfo)
        var canvas: Canvas = page.canvas
        var yPosition = marginTop

        // Draw title if present
        if (title != null) {
            canvas.drawText(title, marginLeft, yPosition + titlePaint.textSize, titlePaint)
            yPosition += titlePaint.textSize * 2f
        }

        // Word-wrap and draw body content
        val lines = content.split("\n")
        for (line in lines) {
            // Wrap long lines
            val words = line.split(" ")
            val wrappedLine = StringBuilder()
            for (word in words) {
                val test = if (wrappedLine.isEmpty()) word else "$wrappedLine $word"
                if (bodyPaint.measureText(test) > usableWidth && wrappedLine.isNotEmpty()) {
                    // Flush current line
                    yPosition += lineHeight
                    if (yPosition > pageHeight - marginBottom) {
                        document.finishPage(page)
                        pageNumber++
                        pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                        page = document.startPage(pageInfo)
                        canvas = page.canvas
                        yPosition = marginTop + lineHeight
                    }
                    canvas.drawText(wrappedLine.toString(), marginLeft, yPosition, bodyPaint)
                    wrappedLine.clear()
                    wrappedLine.append(word)
                } else {
                    if (wrappedLine.isNotEmpty()) wrappedLine.append(" ")
                    wrappedLine.append(word)
                }
            }
            // Flush remaining text in this line
            yPosition += lineHeight
            if (yPosition > pageHeight - marginBottom) {
                document.finishPage(page)
                pageNumber++
                pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                page = document.startPage(pageInfo)
                canvas = page.canvas
                yPosition = marginTop + lineHeight
            }
            canvas.drawText(wrappedLine.toString(), marginLeft, yPosition, bodyPaint)
        }

        document.finishPage(page)

        FileOutputStream(file).use { fos ->
            document.writeTo(fos)
        }
        document.close()

        return """{"success":true,"path":"${file.absolutePath}","pages":$pageNumber}"""
    }

    private fun httpRequest(args: JsonObject): String {
        val url = args.get("url").asString
        if (!isUrlSafe(url)) return """{"error":"URL blocked: internal/private network addresses not allowed"}"""
        val method = args.get("method")?.asString ?: "GET"

        val conn = Jsoup.connect(url)
            .ignoreContentType(true)
            .userAgent("OpenClaw/1.0")
            .timeout(15000)
            .method(if (method.uppercase() == "POST") org.jsoup.Connection.Method.POST else org.jsoup.Connection.Method.GET)

        if (args.has("body")) {
            conn.requestBody(args.get("body").asString)
        }

        val response = conn.execute()
        val body = response.body().take(4000)

        return """{"status":${response.statusCode()},"body":"${body.replace("\"", "\\\"").replace("\n", "\\n")}"}"""
    }

    private suspend fun braveSearch(args: JsonObject): String {
        val key = AgentConfig.getKeyForProvider("brave")
        if (key.isBlank()) return """{"error":"brave API key not configured. Add it in Settings → Services → + → brave"}"""

        val query = args.get("query").asString
        val count = args.get("count")?.asInt ?: 5
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val gson = Gson()

        return try {
            val resp = sharedHttpClient.get("https://api.search.brave.com/res/v1/web/search?q=$encoded&count=$count") {
                header("Accept", "application/json")
                header("X-Subscription-Token", key)
            }
            val body = resp.bodyAsText()
            val json = com.google.gson.JsonParser.parseString(body).asJsonObject
            val webResults = json.getAsJsonObject("web")?.getAsJsonArray("results")
            if (webResults == null || webResults.size() == 0) {
                return """{"query":${gson.toJson(query)},"results":[],"note":"No results found"}"""
            }
            val results = webResults.take(count).mapIndexed { i, el ->
                val obj = el.asJsonObject
                val title = obj.get("title")?.asString ?: ""
                val url = obj.get("url")?.asString ?: ""
                val desc = (obj.get("description")?.asString ?: "").take(300)
                """{"rank":${i+1},"title":${gson.toJson(title)},"url":${gson.toJson(url)},"description":${gson.toJson(desc)}}"""
            }
            """{"query":${gson.toJson(query)},"results":[${results.joinToString(",")}]}"""
        } catch (e: Exception) {
            """{"error":"Brave search failed: ${e.message?.replace("\"", "'")}"}"""
        }
    }

    private suspend fun exaSearch(args: JsonObject): String {
        val key = AgentConfig.getKeyForProvider("exa")
        if (key.isBlank()) return """{"error":"exa API key not configured. Add it in Settings → Services → + → exa"}"""

        val query = args.get("query").asString
        val numResults = args.get("num_results")?.asInt ?: 5
        val searchType = args.get("type")?.asString ?: "neural"
        val gson = Gson()

        return try {
            val requestBody = com.google.gson.JsonObject().apply {
                addProperty("query", query)
                addProperty("numResults", numResults)
                addProperty("type", searchType)
            }
            val resp = sharedHttpClient.post("https://api.exa.ai/search") {
                contentType(ContentType.Application.Json)
                header("x-api-key", key)
                setBody(requestBody.toString())
            }
            val body = resp.bodyAsText()
            val json = com.google.gson.JsonParser.parseString(body).asJsonObject
            val exaResults = json.getAsJsonArray("results")
            if (exaResults == null || exaResults.size() == 0) {
                return """{"query":${gson.toJson(query)},"results":[],"note":"No results found"}"""
            }
            val results = exaResults.take(numResults).mapIndexed { i, el ->
                val obj = el.asJsonObject
                val title = obj.get("title")?.asString ?: ""
                val url = obj.get("url")?.asString ?: ""
                val score = obj.get("score")?.asFloat ?: 0f
                """{"rank":${i+1},"title":${gson.toJson(title)},"url":${gson.toJson(url)},"score":${"%.3f".format(score)}}"""
            }
            """{"query":${gson.toJson(query)},"type":"$searchType","results":[${results.joinToString(",")}]}"""
        } catch (e: Exception) {
            """{"error":"Exa search failed: ${e.message?.replace("\"", "'")}"}"""
        }
    }

    private suspend fun firecrawlScrape(args: JsonObject): String {
        val key = AgentConfig.getKeyForProvider("firecrawl")
        if (key.isBlank()) return """{"error":"firecrawl API key not configured. Add it in Settings → Services → + → firecrawl"}"""

        val url = args.get("url").asString
        if (!isUrlSafe(url)) return """{"error":"URL blocked: internal/private network addresses not allowed"}"""
        val gson = Gson()

        return try {
            val requestBody = com.google.gson.JsonObject().apply {
                addProperty("url", url)
            }
            val resp = sharedHttpClient.post("https://api.firecrawl.dev/v1/scrape") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $key")
                setBody(requestBody.toString())
            }
            val body = resp.bodyAsText()
            val json = com.google.gson.JsonParser.parseString(body).asJsonObject
            val success = json.get("success")?.asBoolean ?: false
            if (!success) {
                val error = json.get("error")?.asString ?: "Unknown error"
                return """{"error":"Firecrawl failed: ${error.replace("\"", "'")}"}"""
            }
            val data = json.getAsJsonObject("data")
            val markdown = (data?.get("markdown")?.asString ?: "").take(4000)
            val title = data?.get("metadata")?.asJsonObject?.get("title")?.asString ?: ""
            """{"url":${gson.toJson(url)},"title":${gson.toJson(title)},"content":${gson.toJson(markdown)}}"""
        } catch (e: Exception) {
            """{"error":"Firecrawl scrape failed: ${e.message?.replace("\"", "'")}"}"""
        }
    }
}
