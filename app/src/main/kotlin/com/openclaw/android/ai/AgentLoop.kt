package com.openclaw.android.ai

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.stream.JsonReader
import com.openclaw.android.OpenClawApplication
import com.openclaw.android.util.NotificationHelper
import com.openclaw.android.util.ServiceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.openclaw.android.data.AppDatabase
import com.openclaw.android.data.entity.MemoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The AI Agent loop.
 * Takes a user message → sends to LLM with tools → executes tool calls → loops until done.
 *
 * Flow:
 * 1. User sends message (from Telegram, SMS, or in-app chat)
 * 2. Agent sends message + tool definitions to LLM
 * 3. LLM either responds with text OR requests a tool call
 * 4. If tool call: execute the tool, send result back to LLM, goto 3
 * 5. If text: return to user
 */
class AgentLoop(private val llmClient: LlmClient) {

    private val gson = Gson()
    private val maxSteps = 25
    private val MAX_EMBEDDED_TOOLS = 5  // Cap embedded tool calls per run

    /** Parse JSON leniently — LLM output is often not strictly valid */
    private fun <T> parseLenient(json: String, clazz: Class<T>): T {
        val cleaned = cleanLlmJson(json)
        val reader = JsonReader(StringReader(cleaned))
        reader.isLenient = true
        return gson.fromJson(reader, clazz)
    }

    /**
     * Clean up LLM JSON output — some providers (MiniMax, etc.) return JSON
     * wrapped in XML-like tags or with trailing garbage.
     * Extracts the JSON object/array and discards everything after.
     */
    private fun cleanLlmJson(raw: String): String {
        val trimmed = raw.trim()
        val startChar = trimmed.firstOrNull() ?: return raw
        if (startChar != '{' && startChar != '[') return raw
        val endChar = if (startChar == '{') '}' else ']'

        // Track nesting depth for {} and [] separately
        var braceDepth = 0
        var bracketDepth = 0
        var inString = false
        var escape = false
        var lastValidPos = 0  // Track last position that ends a complete value

        for (i in trimmed.indices) {
            val c = trimmed[i]
            if (escape) { escape = false; continue }
            if (c == '\\' && inString) { escape = true; continue }
            if (c == '"' && !escape) { inString = !inString; continue }
            if (inString) continue

            when (c) {
                '{' -> braceDepth++
                '}' -> {
                    braceDepth--
                    if (braceDepth == 0 && bracketDepth == 0 && startChar == '{') {
                        return trimmed.substring(0, i + 1)
                    }
                    lastValidPos = i
                }
                '[' -> bracketDepth++
                ']' -> {
                    bracketDepth--
                    if (bracketDepth == 0 && braceDepth == 0 && startChar == '[') {
                        return trimmed.substring(0, i + 1)
                    }
                    lastValidPos = i
                }
                ',' -> lastValidPos = i - 1  // Before comma = end of previous value
            }
        }

        // JSON truncated — close open structures
        // Strip any incomplete value after last comma/colon
        var result = if (inString) {
            // We're in the middle of a string — close it and trim
            val lastQuote = trimmed.lastIndexOf('"', trimmed.length - 1)
            if (lastQuote > 0) trimmed.substring(0, lastQuote + 1) else trimmed
        } else {
            // Strip trailing incomplete tokens (partial key, colon without value, etc.)
            trimmed.trimEnd().replace(Regex("[,:\"\\s]+$"), "")
        }

        // Remove XML tags if present
        result = result.substringBefore("</")

        // Close all open structures
        repeat(braceDepth.coerceAtLeast(0)) { result += "}" }
        repeat(bracketDepth.coerceAtLeast(0)) { result += "]" }

        return result
    }

    private val _isThinking = MutableStateFlow(false)
    val isThinking = _isThinking.asStateFlow()

    private val _totalTokens = MutableStateFlow(0L)
    val totalTokens = _totalTokens.asStateFlow()

    /** Live narration: broadcasts what the agent is doing at each step */
    private val _liveNarration = MutableStateFlow("")
    val liveNarration = _liveNarration.asStateFlow()

    /** Mid-task feedback: user can inject messages while agent is working */
    @Volatile
    private var pendingFeedback: String? = null

    fun injectFeedback(feedback: String) {
        pendingFeedback = feedback
        ServiceState.addLog("Agent: received mid-task feedback: ${feedback.take(60)}")
    }

    /**
     * Run the agent with a user message. Returns the final text response.
     */
    suspend fun run(config: LlmClient.Config, userMessage: String, systemPrompt: String? = null): String {
        _isThinking.value = true
        ServiceState.addLog("Agent: processing message")

        // Add to conversation history
        ConversationManager.addUserMessage(userMessage)
        ConversationManager.maybeCompact(config.provider)

        // Build messages: conversation history + current tool-calling loop
        val conversationHistory = ConversationManager.getHistory()
        val messages = mutableListOf<LlmClient.Message>()
        messages.addAll(conversationHistory)

        val tools = AndroidTools.getToolDefinitions()
        var step = 0
        var embeddedToolCount = 0
        var nudgeCount = 0
        val toolsUsed = mutableListOf<String>() // Track for auto-learn

        try {
            while (step < maxSteps) {
                step++
                if (step > maxSteps) break  // Safety: prevent overshoot from continue paths
                ServiceState.addLog("Agent: step $step/$maxSteps")

                val response = llmClient.chat(config, messages, systemPrompt, tools)
                _totalTokens.value += response.tokensUsed
                ConversationManager.recordTokensUsed(response.tokensUsed)

                if (response.error != null) {
                    ServiceState.addLog("Agent: LLM error — ${response.error}")
                    NotificationHelper.notifyError(response.error)
                    logFailure(userMessage, "LLM error: ${response.error}", toolsUsed, step)
                    return "Error: ${response.error}"
                }

                var content = response.content

                // Strip MiniMax XML wrapper and raw tool_use markup that leaks into responses
                if (content.contains("</minimax:tool_call>") || content.contains("</invoke>")) {
                    content = content.replace(Regex("</?(minimax:tool_call|invoke)[^>]*>"), "").trim()
                }
                // Strip raw tool_use JSON that MiniMax sometimes sends with = instead of :
                if (content.contains("\"type=\"tool_use\"") || content.contains("\"type=\\\"tool_use\\\"")) {
                    content = content.replace(Regex("\\{\"type=\"tool_use\"[^}]*\\}"), "").trim()
                }

                // Check if it's a tool call (Anthropic format)
                if (content.startsWith("{") && content.contains("\"type\":\"tool_use\"")) {
                    try {
                        val toolCall = parseLenient(content, JsonObject::class.java)
                        val toolName = toolCall.get("name")?.asString ?: continue
                        val toolInput = toolCall.getAsJsonObject("input") ?: JsonObject()

                        toolsUsed.add(toolName)
                        _liveNarration.value = "Step $step: $toolName"
                        ServiceState.addLog("Agent: calling tool $toolName")
                        val toolResult = AndroidTools.executeTool(toolName, toolInput)
                        ServiceState.addLog("Agent: tool $toolName returned ${toolResult.take(100)}...")

                        // Auto-memory: save significant tool results as facts
                        autoMemoryFromTool(toolName, toolInput, toolResult)

                        // Narrate the result briefly
                        val narration = narrate(toolName, toolResult)
                        _liveNarration.value = narration

                        messages.add(LlmClient.Message("assistant", content))

                        // Check for mid-task user feedback
                        val feedback = pendingFeedback
                        if (feedback != null) {
                            pendingFeedback = null
                            messages.add(LlmClient.Message("user", "[Tool result for $toolName]: $toolResult\n\n[USER FEEDBACK (respond to this!)]: $feedback"))
                        } else {
                            messages.add(LlmClient.Message("user", "[Tool result for $toolName]: $toolResult"))
                        }
                        continue
                    } catch (e: Exception) {
                        ServiceState.addLog("Agent: tool parse error (Anthropic) — ${e.message?.take(80)}")
                    }
                }

                // Check if it's a tool call (OpenAI format)
                if (content.startsWith("[") && content.contains("\"function\"")) {
                    try {
                        val toolCalls = parseLenient(content, JsonArray::class.java)
                        val results = StringBuilder()

                        for (tc in toolCalls) {
                            val call = tc.asJsonObject
                            val fn = call.getAsJsonObject("function")
                            val toolName = fn.get("name")?.asString ?: continue
                            val argsStr = fn.get("arguments")?.asString ?: "{}"
                            val toolInput = parseLenient(argsStr, JsonObject::class.java)

                            toolsUsed.add(toolName)
                            _liveNarration.value = "Step $step: $toolName"
                            ServiceState.addLog("Agent: calling tool $toolName")
                            val toolResult = AndroidTools.executeTool(toolName, toolInput)
                            autoMemoryFromTool(toolName, toolInput, toolResult)
                            results.append("[$toolName]: $toolResult\n")
                        }

                        _liveNarration.value = narrate(toolsUsed.lastOrNull() ?: "tool", results.toString())
                        messages.add(LlmClient.Message("assistant", content))

                        // Check for mid-task user feedback
                        val feedback = pendingFeedback
                        if (feedback != null) {
                            pendingFeedback = null
                            messages.add(LlmClient.Message("user", "$results\n\n[USER FEEDBACK (respond to this!)]: $feedback"))
                        } else {
                            messages.add(LlmClient.Message("user", results.toString()))
                        }
                        continue
                    } catch (e: Exception) {
                        ServiceState.addLog("Agent: tool parse error (OpenAI) — ${e.message?.take(80)}")
                        // Treat as text response if parse fails
                    }
                }

                // Check if text response contains an embedded tool call (MiniMax/Pollinations pattern)
                // SECURITY: Require "type":"tool_use" or "type":"function" marker to prevent
                // prompt injection from scraped web content or file data triggering arbitrary tool calls.
                // Also cap embedded tool executions to prevent infinite loops.
                val hasToolUseMarker = content.contains("\"type\"") && (content.contains("tool_use") || content.contains("function"))
                val embeddedToolMatch = if (hasToolUseMarker && embeddedToolCount < MAX_EMBEDDED_TOOLS) {
                    Regex("\"name\"\\s*:\\s*\"(android_tap|android_swipe|android_type_text|android_press_back|android_press_home|android_press_enter|android_open_app|look_and_find|look_and_describe|scroll_down|scroll_up|android_read_screen|find_element|take_screenshot|analyze_screenshot|analyze_screen_with_som|tap_som_element)\"").find(content)
                } else null
                if (embeddedToolMatch != null) {
                    val toolName = embeddedToolMatch.groupValues[1]
                    embeddedToolCount++
                    ServiceState.addLog("Agent: found embedded tool call '$toolName' in text response — executing ($embeddedToolCount/$MAX_EMBEDDED_TOOLS)")
                    try {
                        // Extract input/parameters from the embedded JSON
                        val inputJson = JsonObject()
                        // Extract x,y for tap
                        val xVal = Regex("\"x\"\\s*[>:]\\s*(\\d+)").find(content)?.groupValues?.get(1)?.toFloatOrNull()
                        val yVal = Regex("\"y\"\\s*[>:]\\s*(\\d+)").find(content)?.groupValues?.get(1)?.toFloatOrNull()
                        if (xVal != null) inputJson.addProperty("x", xVal)
                        if (yVal != null) inputJson.addProperty("y", yVal)
                        // Extract x1,y1,x2,y2 for swipe
                        val x1Val = Regex("\"x1\"\\s*[>:]\\s*(\\d+)").find(content)?.groupValues?.get(1)?.toFloatOrNull()
                        val y1Val = Regex("\"y1\"\\s*[>:]\\s*(\\d+)").find(content)?.groupValues?.get(1)?.toFloatOrNull()
                        val x2Val = Regex("\"x2\"\\s*[>:]\\s*(\\d+)").find(content)?.groupValues?.get(1)?.toFloatOrNull()
                        val y2Val = Regex("\"y2\"\\s*[>:]\\s*(\\d+)").find(content)?.groupValues?.get(1)?.toFloatOrNull()
                        if (x1Val != null) inputJson.addProperty("x1", x1Val)
                        if (y1Val != null) inputJson.addProperty("y1", y1Val)
                        if (x2Val != null) inputJson.addProperty("x2", x2Val)
                        if (y2Val != null) inputJson.addProperty("y2", y2Val)
                        // Extract query for find_element
                        val queryVal = Regex("\"query\"\\s*[>:]\\s*\"([^\"]+)\"").find(content)?.groupValues?.get(1)
                        if (queryVal != null) inputJson.addProperty("query", queryVal)
                        // Extract command for run_in_linux/run_shell
                        val cmdVal = Regex("\"command\"\\s*[>:]\\s*\"([^\"]+)\"").find(content)?.groupValues?.get(1)
                        if (cmdVal != null) inputJson.addProperty("command", cmdVal)
                        // Extract code for run_python/run_node
                        val codeVal = Regex("\"code\"\\s*[>:]\\s*\"([^\"]+)\"").find(content)?.groupValues?.get(1)
                        if (codeVal != null) inputJson.addProperty("code", codeVal)
                        // Extract text for type_text
                        val textVal = Regex("\"text\"\\s*[>:]\\s*\"([^\"]+)\"").find(content)?.groupValues?.get(1)
                        if (textVal != null) inputJson.addProperty("text", textVal)
                        // Extract target for look_and_find
                        val targetVal = Regex("\"target\"\\s*[>:]\\s*\"([^\"]+)\"").find(content)?.groupValues?.get(1)
                            ?: Regex("<parameter name=\"target\">([^<]+)</parameter>").find(content)?.groupValues?.get(1)
                        if (targetVal != null) inputJson.addProperty("target", targetVal)
                        // Extract packageName for open_app
                        val pkgVal = Regex("\"packageName\"\\s*[>:]\\s*\"([^\"]+)\"").find(content)?.groupValues?.get(1)
                        if (pkgVal != null) inputJson.addProperty("packageName", pkgVal)
                        // Extract id for tap_som_element
                        val idVal = Regex("\"id\"\\s*[>:]\\s*(\\d+)").find(content)?.groupValues?.get(1)?.toIntOrNull()
                        if (idVal != null) inputJson.addProperty("id", idVal)

                        toolsUsed.add(toolName)
                        _liveNarration.value = "Found embedded: $toolName"
                        val toolResult = AndroidTools.executeTool(toolName, inputJson)
                        ServiceState.addLog("Agent: embedded tool $toolName returned ${toolResult.take(100)}...")
                        autoMemoryFromTool(toolName, inputJson, toolResult)

                        messages.add(LlmClient.Message("assistant", content))
                        val feedback = pendingFeedback
                        if (feedback != null) {
                            pendingFeedback = null
                            messages.add(LlmClient.Message("user", "[Tool result for $toolName]: $toolResult\n\n[USER FEEDBACK]: $feedback"))
                        } else {
                            messages.add(LlmClient.Message("user", "[Tool result for $toolName]: $toolResult"))
                        }
                        continue
                    } catch (e: Exception) {
                        ServiceState.addLog("Agent: embedded tool exec failed: ${e.message?.take(80)}")
                    }
                }

                // Plain text response — check if agent SHOULD have called a tool
                // Nudge up to 3 times if task looks incomplete
                val isActionRequest = looksLikeActionRequest(userMessage)
                val taskLooksIncomplete = isActionRequest && step < maxSteps - 2 && nudgeCount < 3
                if (taskLooksIncomplete) {
                    nudgeCount++
                    ServiceState.addLog("Agent: text-only response on action request — nudging ($nudgeCount/3)")
                    messages.add(LlmClient.Message("assistant", content))
                    messages.add(LlmClient.Message("user",
                        "[SYSTEM: You replied with text but the task is NOT complete yet. " +
                        "The user asked you to: ${userMessage.take(100)}. " +
                        "You MUST call a tool NOW to continue. Use look_and_find or android_read_screen to see the current state, " +
                        "then take the next action. DO NOT reply with text — call a tool.]"))
                    continue // Retry with nudge
                }

                ServiceState.addLog("Agent: final response (${content.length} chars, ${response.tokensUsed} tokens)")
                ConversationManager.addAssistantMessage(content)
                NotificationHelper.notifyAgentResponse("OpenClaw", content.take(200))

                // Auto-learn: save successful multi-step tasks as skills
                if (step >= 5 && toolsUsed.size >= 3) {
                    autoLearn(userMessage, toolsUsed, step)
                }

                // Auto-memory: save conversation turn + facts to SQLite
                autoMemoryConversation(userMessage, content, toolsUsed, step)

                return content
            }

            ServiceState.addLog("Agent: max steps reached — continuing as sub-agent")
            // Auto-continue: spawn sub-agent to finish the task
            val lastMsgs = messages.takeLast(4).joinToString("\n") { "${it.role}: ${it.content.take(200)}" }
            val continuation = SubAgentManager.spawn(
                "Continue: ${userMessage.take(50)}",
                "Continue this task. Context of what was done so far:\n$lastMsgs\n\nOriginal request: $userMessage\n\nPick up where the previous agent left off and complete the task.",
                config
            )
            val fallback = "Task needed more than $maxSteps steps. I've spawned a background agent to finish it. $continuation"
            ConversationManager.addAssistantMessage(fallback)
            return fallback
        } finally {
            _isThinking.value = false
        }
    }

    /**
     * Detect if user message is asking for an action (not just a question).
     * Used to nudge the agent when it replies with text instead of tool calls.
     */
    private fun looksLikeActionRequest(msg: String): Boolean {
        val lower = msg.lowercase().trim()
        // Explicit stop/cancel signals — NOT action requests
        val stopWords = listOf("stop", "berhenti", "cancel", "udah", "cukup", "gajelas", "gagal", "salah", "wrong")
        if (stopWords.any { lower == it || lower.startsWith("$it ") }) return false
        // Short encouragement messages — NOT action requests (user is saying "go on")
        val encourageWords = listOf("ayo", "ayok", "ayokk", "gas", "cmon", "go", "go on", "lanjut", "mana", "terus", "oke", "ok", "yes", "sip")
        if (encourageWords.any { lower == it }) return false

        val actionWords = listOf(
            "buka", "open", "cari", "search", "scroll", "swipe", "tap", "klik", "click",
            "play", "pause", "next", "back", "home", "type", "ketik", "tulis",
            "kirim", "send", "download", "install", "screenshot", "foto", "volume",
            "nyalakan", "matikan", "tutup", "close", "refresh", "update", "hapus", "delete",
            "copy", "paste", "share", "forward", "reply", "check", "cek", "liat", "lihat",
            "tolong", "please", "bantu", "help me", "carikan", "bukain", "nyalain", "matiin",
            "scroll atas", "scroll bawah", "geser", "tekan", "pencet", "enter",
            "navigate", "go to", "pergi ke", "masuk ke", "buat", "create", "generate", "bikin",
            "masukkin", "masukin", "taruh", "add to cart", "checkout", "cariin"
        )
        return actionWords.any { lower.contains(it) }
    }

    /**
     * Generate a brief human-readable narration of what the tool did.
     * Sent to user via liveNarration StateFlow and displayed in chat/Telegram.
     */
    private fun narrate(toolName: String, result: String): String {
        val r = result.take(150)
        return when {
            toolName.contains("open_app") -> "Opening app..."
            toolName.contains("read_screen") -> "Reading screen..."
            toolName.contains("find_element") -> {
                if (r.contains("matches\":0")) "Element not found — trying another approach"
                else "Found element on screen"
            }
            toolName.contains("tap") -> "Tapped on screen"
            toolName.contains("type_text") -> "Typing text..."
            toolName.contains("press_enter") -> "Pressing Enter/Search"
            toolName.contains("press_back") -> "Going back"
            toolName.contains("swipe") -> "Scrolling..."
            toolName.contains("screenshot") -> "Taking screenshot..."
            toolName.contains("media_control") -> "Media: ${result.substringAfter("action\":\"").substringBefore("\"")}"
            toolName.contains("web_search") -> "Searching the web..."
            toolName.contains("web_scrape") -> "Reading web page..."
            toolName.contains("memory_store") -> "Saving to memory..."
            toolName.contains("memory_search") -> "Searching memories..."
            toolName.contains("telegram") -> "Sending to Telegram..."
            toolName.contains("write_file") || toolName.contains("generate") -> "Creating file..."
            else -> "Working: $toolName"
        }
    }

    /**
     * Auto-learn: append successful multi-step task to skills.md.
     * Only saves if task used 5+ steps and 3+ unique tools.
     * Lightweight — just appends a few lines, no LLM call needed.
     */
    private fun autoLearn(userMessage: String, toolsUsed: List<String>, steps: Int) {
        try {
            val uniqueTools = toolsUsed.distinct()
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
            val taskSummary = userMessage.take(80).replace("\n", " ")
            val toolChain = uniqueTools.joinToString(" → ")

            // Read current skills.md
            val content = Bootstrap.readFile("skills.md")

            // Don't save duplicates — check if similar task already saved
            if (content.contains(taskSummary.take(40))) return

            // Append new learned pattern
            val newSkill = "\n### auto_${System.currentTimeMillis() / 1000}\n" +
                "- Task: $taskSummary\n" +
                "- Tools: $toolChain\n" +
                "- Steps: $steps | Learned: $timestamp\n"

            val updated = content.trimEnd() + "\n" + newSkill

            // Write back — find the right file location
            val wsFile = File(OpenClawApplication.instance.filesDir, "workspace/skills.md")
            val cfgFile = File(OpenClawApplication.instance.filesDir, "agent_config/skills.md")
            val target = when {
                wsFile.exists() -> wsFile
                cfgFile.exists() -> cfgFile
                else -> { wsFile.parentFile?.mkdirs(); wsFile }
            }
            target.writeText(updated)
            ServiceState.addLog("Auto-learn: saved skill from ${uniqueTools.size} tools, $steps steps")
        } catch (e: Exception) {
            ServiceState.addLog("Auto-learn error: ${e.message?.take(60)}")
        }
    }

    /**
     * Log failed tasks to memory.md so the heartbeat can review and improve.
     * Failures are the MOST valuable data for self-improvement.
     */
    private suspend fun logFailure(userMessage: String, error: String, toolsUsed: List<String>, step: Int) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
            val task = userMessage.take(60).replace("\n", " ")
            val tools = toolsUsed.distinct().joinToString(", ")

            val content = Bootstrap.readFile("memory.md")
            val failEntry = "\n## Failed Task [$timestamp]\n" +
                "- Task: $task\n" +
                "- Error: ${error.take(100)}\n" +
                "- Tools tried: $tools\n" +
                "- Failed at step: $step\n" +
                "- Status: NEEDS REVIEW (heartbeat will analyze)\n"

            val updated = content.trimEnd() + "\n" + failEntry

            val wsFile = File(OpenClawApplication.instance.filesDir, "workspace/memory.md")
            val cfgFile = File(OpenClawApplication.instance.filesDir, "agent_config/memory.md")
            val target = when {
                wsFile.exists() -> wsFile
                cfgFile.exists() -> cfgFile
                else -> { wsFile.parentFile?.mkdirs(); wsFile }
            }
            target.writeText(updated)
            ServiceState.addLog("Failure logged to memory.md for heartbeat review")

            // Also save failure to SQLite for searchable recall
            saveToSqlite("FAILED TASK: $task | Error: ${error.take(100)} | Tools: $tools | Step: $step",
                type = "conversation", importance = 0.7f)
        } catch (_: Exception) {}
    }

    // ─── AUTO-MEMORY SYSTEM ─────────────────────────────────────────────
    // Agent automatically saves to SQLite WITHOUT being told.
    // Three triggers: (1) every conversation turn, (2) significant tool results, (3) periodic prune.

    private val conversationCounter = java.util.concurrent.atomic.AtomicInteger(0)  // For periodic prune (every 20 turns)

    /** Save a memory to SQLite — suspend-safe, no runBlocking */
    private suspend fun saveToSqlite(content: String, type: String = "general", importance: Float = 0.5f, metadata: String = "{}") {
        try {
            val db = AppDatabase.getInstance(OpenClawApplication.instance)
            val entity = MemoryEntity(
                content = content,
                type = type,
                source = "agent",
                importance = importance,
                metadata = metadata
            )
            withContext(Dispatchers.IO) {
                db.memoryDao().insert(entity)
            }
        } catch (e: Exception) {
            ServiceState.addLog("Auto-memory save error: ${e.message?.take(60)}")
        }
    }

    /**
     * Auto-save conversation turn to SQLite.
     * Called after every completed agent run.
     * Saves: user request summary + agent response summary + tools used.
     */
    private suspend fun autoMemoryConversation(userMessage: String, agentResponse: String, toolsUsed: List<String>, steps: Int) {
        try {
            val userSummary = userMessage.take(150).replace("\n", " ").trim()
            val agentSummary = agentResponse.take(200).replace("\n", " ").trim()
            val tools = toolsUsed.distinct().joinToString(", ")
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

            // Determine importance based on complexity
            val importance = when {
                steps >= 10 -> 0.9f       // Complex multi-step task
                steps >= 5 -> 0.7f        // Moderate task
                toolsUsed.isNotEmpty() -> 0.5f  // Simple tool use
                else -> 0.3f              // Simple Q&A
            }

            val memoryContent = buildString {
                append("[$timestamp] User: $userSummary")
                if (tools.isNotEmpty()) append(" | Tools: $tools")
                append(" | Agent: $agentSummary")
            }

            saveToSqlite(memoryContent, type = "conversation", importance = importance,
                metadata = """{"steps":$steps,"tools_count":${toolsUsed.size}}""")

            // Periodic prune: every 20 conversations, check and clean up
            if (conversationCounter.incrementAndGet() % 20 == 0) {
                withContext(Dispatchers.IO) {
                    val db = AppDatabase.getInstance(OpenClawApplication.instance)
                    // Lightweight count: fetch 501 to check if over 500, don't load all
                    val count = db.memoryDao().getTopMemories(501).size
                    if (count > 500) {
                        val cutoff = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
                        db.memoryDao().pruneOldUnused(cutoff)
                        ServiceState.addLog("Auto-memory: pruned old unused memories (was $count+)")
                    }
                }
            }
        } catch (e: Exception) {
            ServiceState.addLog("Auto-memory conversation error: ${e.message?.take(60)}")
        }
    }

    /**
     * Auto-save significant tool results as facts in SQLite.
     * Only saves tools that produce useful, reusable information.
     * Skips transient results (tap confirmation, scroll, etc.)
     */
    private suspend fun autoMemoryFromTool(toolName: String, toolInput: JsonObject, toolResult: String) {
        try {
            // Only save tools that produce factual/reusable results
            // memory_store/memory_search excluded — don't save memory ops as new memories
            val factTools = setOf(
                "web_search", "web_scrape", "http_request",
                "read_file", "read_workspace_file",
                "github_api", "vercel_api", "supabase_query", "postgres_query",
                "run_python", "calculator",
                "android_read_notifications"
            )

            // Skip if not a fact-producing tool
            if (toolName !in factTools) return

            // Skip empty or error results
            if (toolResult.length < 20) return
            if (toolResult.startsWith("{\"error\"")) return
            if (toolResult.startsWith("{\"success\":false")) return

            // Determine type and importance based on tool
            val (type, importance) = when (toolName) {
                "web_search" -> "fact" to 0.6f
                "web_scrape" -> "fact" to 0.5f
                "http_request" -> "fact" to 0.6f
                "github_api", "vercel_api" -> "fact" to 0.7f
                "supabase_query", "postgres_query" -> "fact" to 0.7f
                "calculator" -> "fact" to 0.4f
                "android_read_notifications" -> "general" to 0.3f
                else -> "general" to 0.4f
            }

            // Build compact memory content
            val inputHint = toolInput.entrySet().take(2).joinToString(", ") { "${it.key}=${it.value}" }
            val resultSnippet = toolResult.take(300).replace("\n", " ").trim()
            val content = "$toolName($inputHint): $resultSnippet"

            saveToSqlite(content, type = type, importance = importance,
                metadata = """{"tool":"$toolName"}""")
        } catch (_: Exception) {
            // Silent — don't disrupt agent flow for memory errors
        }
    }
}
