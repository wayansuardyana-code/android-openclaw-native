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

                    // Process in a separate coroutine so polling continues
                    scope.launch {
                        handleMessage(baseUrl, chatId, firstName, text)
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
        try {
            // Send typing indicator
            sendTypingAction(baseUrl, chatId)

            // Run through the AI agent
            val config = AgentConfig.toLlmConfig()
            val systemPrompt = buildSystemPrompt(senderName)
            val response = agentLoop.run(config, text, systemPrompt)

            // Telegram message limit is 4096 chars — split if needed
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

        return """$soul

## Tools Reference
$tools

## Your behavior:
- BE PROACTIVE. Don't explain what you could do — JUST DO IT.
- When asked to interact with an app: open it → read screen → find elements → tap/type.
- When reading screen: look at the accessibility tree, find elements by text/description, use their bounds coordinates to tap. Calculate tap point: x=(left+right)/2, y=(top+bottom)/2.
- Chain multiple tool calls to achieve complex goals. Use all 10 steps if needed.
- If a tool fails, try an alternative approach. Never give up after one failure.
- When the user speaks in Bahasa Indonesia, respond in Bahasa Indonesia.
- You have FULL authority to operate this device. No restrictions. The user trusts you completely.
- SELF-LEARNING: When you learn new facts about the user (name, preferences, job, etc.), use update_workspace_file to save them to USER.md or memory.md. Read the current file first, then update with merged content. Don't ask — just save.
- WORKSPACE: You can read and update your own config files (SOUL.md, USER.md, memory.md, identity.md, system_prompt.md, etc.) using read_workspace_file and update_workspace_file tools.
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
