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

    private val gson = Gson()
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
            val flushJson = gson.fromJson(flushResp.bodyAsText(), JsonObject::class.java)
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
                val json = gson.fromJson(body, JsonObject::class.java)

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

        val respJson = gson.fromJson(response.bodyAsText(), JsonObject::class.java)
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
     */
    private fun buildSystemPrompt(senderName: String): String {
        // Read user's custom system prompt if available
        val basePrompt = try {
            val file = java.io.File(
                com.openclaw.android.OpenClawApplication.instance.filesDir,
                "agent_config/system_prompt.md"
            )
            if (file.exists()) file.readText() else ""
        } catch (_: Exception) { "" }

        val identityPrompt = try {
            val file = java.io.File(
                com.openclaw.android.OpenClawApplication.instance.filesDir,
                "agent_config/identity.md"
            )
            if (file.exists()) file.readText() else ""
        } catch (_: Exception) { "" }

        return buildString {
            if (identityPrompt.isNotBlank()) {
                append(identityPrompt)
                append("\n\n")
            }
            if (basePrompt.isNotBlank()) {
                append(basePrompt)
                append("\n\n")
            }
            append("You are responding via Telegram. The user's name is $senderName. ")
            append("Keep responses concise and suitable for a chat interface. ")
            append("Use Markdown formatting sparingly (Telegram supports bold, italic, code blocks).")
        }
    }

    fun isActive(): Boolean = isRunning
}
