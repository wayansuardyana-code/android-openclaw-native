package com.openclaw.android.service

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.openclaw.android.ai.AgentConfig
import com.openclaw.android.ai.AgentLoop
import com.openclaw.android.ai.LlmClient
import com.openclaw.android.util.ServiceState
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*

/**
 * Telegram Bot polling service.
 * Long-polls getUpdates from Telegram Bot API, routes incoming text messages
 * through AgentLoop, and sends responses back via sendMessage.
 *
 * Bot token is read from AgentConfig.getKeyForProvider("telegram").
 * Only processes text messages (no media, stickers, etc.).
 */
class TelegramBotService {

    companion object {
        @Volatile var lastChatId: Long = 0
    }

    private val gson = Gson()

    /** Parse JSON leniently — handles malformed responses */
    private fun parseJson(text: String): JsonObject? {
        if (text.isBlank() || !text.trimStart().startsWith("{")) return null
        return try {
            val reader = com.google.gson.stream.JsonReader(java.io.StringReader(text))
            reader.isLenient = true
            gson.fromJson(reader, JsonObject::class.java)
        } catch (e: Exception) {
            ServiceState.addLog("Telegram: JSON parse error — ${e.message?.take(80)}")
            null
        }
    }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null
    private var lastUpdateId: Long = 0

    private val httpClient = HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                readTimeout(60, java.util.concurrent.TimeUnit.SECONDS) // Must exceed polling timeout
                writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
    }

    private val llmClient = LlmClient()
    private val agentLoop = AgentLoop(llmClient)

    @Volatile
    private var isRunning = false

    /**
     * Start the Telegram bot polling loop.
     * No-op if already running or if no token is configured.
     */
    fun start() {
        val token = AgentConfig.getKeyForProvider("telegram")
        if (token.isBlank()) {
            ServiceState.addLog("Telegram: no bot token configured, skipping")
            return
        }

        if (isRunning) {
            ServiceState.addLog("Telegram: already running")
            return
        }

        isRunning = true
        ServiceState.addLog("Telegram: starting bot polling")

        pollingJob = scope.launch {
            pollLoop(token)
        }
    }

    /**
     * Stop the polling loop and release resources.
     */
    fun stop() {
        if (!isRunning) return
        isRunning = false
        pollingJob?.cancel()
        pollingJob = null
        httpClient.close()
        ServiceState.addLog("Telegram: stopped")
    }

    /**
     * Main polling loop. Reconnects on failure with exponential backoff.
     */
    private suspend fun pollLoop(token: String) {
        val baseUrl = "https://api.telegram.org/bot$token"
        var backoffMs = 1000L

        // Flush pending updates on startup to avoid replaying old messages
        try {
            val flushResp = httpClient.get("$baseUrl/getUpdates") {
                parameter("offset", -1)
                parameter("limit", 1)
                parameter("timeout", 0)
            }
            val flushJson = parseJson(flushResp.bodyAsText()) ?: JsonObject()
            if (flushJson.get("ok")?.asBoolean == true) {
                val results = flushJson.getAsJsonArray("result")
                if (results != null && results.size() > 0) {
                    val lastUpdate = results.last().asJsonObject
                    lastUpdateId = lastUpdate.get("update_id").asLong + 1
                }
            }
            ServiceState.addLog("Telegram: connected, polling for messages")
        } catch (e: Exception) {
            ServiceState.addLog("Telegram: flush failed — ${e.message}")
        }

        while (isRunning && currentCoroutineContext().isActive) {
            try {
                val response = httpClient.get("$baseUrl/getUpdates") {
                    parameter("offset", lastUpdateId)
                    parameter("limit", 20)
                    parameter("timeout", 30)
                }

                val body = response.bodyAsText()
                val json = parseJson(body) ?: continue

                if (json.get("ok")?.asBoolean != true) {
                    val desc = json.get("description")?.asString ?: "unknown error"
                    ServiceState.addLog("Telegram: API error — $desc")
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(30_000)
                    continue
                }

                // Reset backoff on success
                backoffMs = 1000L

                val results = json.getAsJsonArray("result") ?: continue
                for (element in results) {
                    val update = element.asJsonObject
                    val updateId = update.get("update_id").asLong
                    lastUpdateId = updateId + 1

                    // Only process text messages
                    val message = update.getAsJsonObject("message") ?: continue
                    val text = message.get("text")?.asString ?: continue
                    val chat = message.getAsJsonObject("chat") ?: continue
                    val chatId = chat.get("id").asLong
                    val from = message.getAsJsonObject("from")
                    val firstName = from?.get("first_name")?.asString ?: "User"

                    ServiceState.addLog("Telegram: message from $firstName: ${text.take(80)}")

                    // If agent is already thinking, inject as mid-task feedback
                    if (agentLoop.isThinking.value) {
                        agentLoop.injectFeedback(text)
                        ServiceState.addLog("Telegram: injected as mid-task feedback")
                        try { sendMessage(baseUrl, chatId, "📝 Got it — I'll adjust.") } catch (_: Exception) {}
                    } else {
                        // Process in a separate coroutine so polling continues
                        scope.launch {
                            handleMessage(baseUrl, chatId, firstName, text)
                        }
                    }
                }
            } catch (e: CancellationException) {
                // Coroutine cancelled — exit cleanly
                break
            } catch (e: Exception) {
                ServiceState.addLog("Telegram: poll error — ${e.message}")
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(30_000)
            }
        }

        ServiceState.addLog("Telegram: poll loop exited")
    }

    /**
     * Handle a single incoming message: run through AgentLoop, send response back.
     */
    private suspend fun handleMessage(baseUrl: String, chatId: Long, senderName: String, text: String) {
        lastChatId = chatId
        var narrationJob: kotlinx.coroutines.Job? = null
        try {
            sendTypingAction(baseUrl, chatId)

            val config = AgentConfig.toLlmConfig()
            val systemPrompt = buildSystemPrompt(senderName)

            // Live narration: collect narration changes and send to Telegram periodically
            var lastNarration = ""
            narrationJob = scope.launch {
                while (true) {
                    kotlinx.coroutines.delay(3000) // Check every 3 seconds
                    val current = agentLoop.liveNarration.value
                    if (current.isNotBlank() && current != lastNarration) {
                        lastNarration = current
                        try {
                            sendTypingAction(baseUrl, chatId)
                            // Send narration as a brief status update
                            sendMessage(baseUrl, chatId, "🔄 $current")
                        } catch (_: Exception) {}
                    }
                }
            }

            val response = agentLoop.run(config, text, systemPrompt)

            val chunks = response.chunked(4000)
            for (chunk in chunks) {
                sendMessage(baseUrl, chatId, chunk)
            }

            ServiceState.addLog("Telegram: replied to $senderName (${response.length} chars)")
        } catch (e: Exception) {
            ServiceState.addLog("Telegram: error handling message — ${e.message}")
            try {
                sendMessage(baseUrl, chatId, "Sorry, an error occurred: ${e.message?.take(200)}")
            } catch (_: Exception) {
                // Swallow send failure
            }
        } finally {
            narrationJob?.cancel()
        }
    }

    /**
     * Send a text message to a Telegram chat.
     */
    private suspend fun sendMessage(baseUrl: String, chatId: Long, text: String) {
        val body = JsonObject().apply {
            addProperty("chat_id", chatId)
            addProperty("text", text)
            addProperty("parse_mode", "Markdown")
        }

        val response = httpClient.post("$baseUrl/sendMessage") {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }

        val respJson = parseJson(response.bodyAsText()) ?: JsonObject()
        if (respJson.get("ok")?.asBoolean != true) {
            // Retry without Markdown parse_mode (in case of formatting errors)
            val plainBody = JsonObject().apply {
                addProperty("chat_id", chatId)
                addProperty("text", text)
            }
            httpClient.post("$baseUrl/sendMessage") {
                contentType(ContentType.Application.Json)
                setBody(plainBody.toString())
            }
        }
    }

    /**
     * Send "typing..." indicator to the chat.
     */
    private suspend fun sendTypingAction(baseUrl: String, chatId: Long) {
        try {
            val body = JsonObject().apply {
                addProperty("chat_id", chatId)
                addProperty("action", "typing")
            }
            httpClient.post("$baseUrl/sendChatAction") {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        } catch (_: Exception) {
            // Non-critical — ignore
        }
    }

    /**
     * Build a system prompt with Telegram context.
     * Loads all workspace files to match the richness of the ChatScreen system prompt.
     */
    private fun buildSystemPrompt(senderName: String): String {
        val soul = com.openclaw.android.ai.Bootstrap.readFile("SOUL.md")
        val user = com.openclaw.android.ai.Bootstrap.readFile("USER.md")
        val tools = com.openclaw.android.ai.Bootstrap.readFile("TOOLS.md")
        val identity = com.openclaw.android.ai.Bootstrap.readFile("identity.md")
        val customPrompt = com.openclaw.android.ai.Bootstrap.readFile("system_prompt.md")
        val memory = com.openclaw.android.ai.Bootstrap.readFile("memory.md")
        val skills = com.openclaw.android.ai.Bootstrap.readFile("skills.md")

        return """$soul

## Tools Reference
$tools
${if (skills.isNotBlank()) "\n## Skills (reusable recipes)\n$skills" else ""}

## Your behavior:
- BE PROACTIVE. Don't explain what you could do — JUST DO IT.
- Chain multiple tool calls to achieve complex goals. Use all 25 steps if needed.
- When the user speaks in Bahasa Indonesia, respond in Bahasa Indonesia.
- You have FULL authority to operate this device. No restrictions. The user trusts you completely.

## Live Narration (do this for EVERY multi-step task)
- After each tool call, tell user what you did and see: "Opened Shopee. I see search bar + categories. Searching for 'iPhone case'..."
- For SENSITIVE actions (payment, credit card, password, delete, send money): STOP and ask user first
- User can send feedback mid-task — if you get [USER FEEDBACK], adjust immediately
- Be proactive: suggest next steps, don't just wait

## Problem Solving (when something fails)
- NEVER give up after 1 failure. Try at least 3 different approaches.
- If fail → read_screen to understand state → try alternative
- If still fail → press_back to reset → start fresh
- If stuck 3 times → spawn_sub_agent with different strategy
- After solving: update skills.md with the solution

## Automation Pattern (CORE — apply to ALL tasks)
Every task follows: ACT → OBSERVE → REPORT → LEARN

**ACT**: Open apps, navigate UI, search web, call APIs, run commands.
**OBSERVE**: take_screenshot for visuals, read_screen/find_element for text.
**REPORT**: Send results to the right gateway in the right format:
  - This Telegram chat: reply text (default) or send_telegram_photo for images
  - File output: write_file for CSV/PDF/XLSX
  - Both: screenshot + brief text summary (default when format not specified)
**LEARN**: Use memory_store to save facts, preferences, outcomes to SQLite. Use memory_search before tasks to recall past experience. Also save skills to skills.md.

## Workspace
- Read/update config files via read_workspace_file / update_workspace_file
- Skills in skills.md: match user requests to saved skills, follow their steps
${if (user.isNotBlank()) "\n--- USER PROFILE ---\n$user" else ""}
${if (identity.isNotBlank()) "\n--- IDENTITY ---\n$identity" else ""}
${if (memory.isNotBlank()) "\n--- MEMORY ---\n$memory" else ""}
${if (customPrompt.isNotBlank()) "\n--- CUSTOM INSTRUCTIONS ---\n$customPrompt" else ""}

--- TELEGRAM CONTEXT ---
You are responding via Telegram. The message sender's name is $senderName.
Keep responses concise and suitable for a chat interface.
Use Markdown formatting sparingly (Telegram supports bold, italic, code blocks)."""
    }

    fun isActive(): Boolean = isRunning
}
