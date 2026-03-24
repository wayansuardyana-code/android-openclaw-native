package com.openclaw.android.ai

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.openclaw.android.util.ServiceState
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Unified LLM client that talks to any OpenAI-compatible API.
 * Supports: Anthropic, OpenAI, Google, MiniMax, OpenRouter, Ollama, custom.
 *
 * All providers are called via their OpenAI-compatible endpoint where possible.
 * Anthropic uses its native /v1/messages API.
 */
class LlmClient {
    private val client = HttpClient(OkHttp)
    private val gson = Gson()

    data class Config(
        val provider: String,       // "anthropic", "openai", "google", "minimax", "openrouter", "ollama", "custom"
        val apiKey: String,
        val model: String,
        val baseUrl: String
    )

    data class Message(val role: String, val content: String)
    data class LlmResponse(val content: String, val tokensUsed: Int = 0, val error: String? = null)

    suspend fun chat(config: Config, messages: List<Message>, systemPrompt: String? = null, tools: List<ToolDef>? = null): LlmResponse {
        return withContext(Dispatchers.IO) {
            try {
                when (config.provider) {
                    "anthropic", "minimax" -> chatAnthropic(config, messages, systemPrompt, tools)
                    else -> chatOpenAI(config, messages, systemPrompt, tools)
                }
            } catch (e: Exception) {
                ServiceState.addLog("LLM error: ${e.message}")
                LlmResponse(content = "", error = e.message)
            }
        }
    }

    private suspend fun chatAnthropic(config: Config, messages: List<Message>, systemPrompt: String?, tools: List<ToolDef>?): LlmResponse {
        val body = JsonObject().apply {
            addProperty("model", config.model)
            addProperty("max_tokens", 4096)
            if (systemPrompt != null) addProperty("system", systemPrompt)

            val msgsArray = JsonArray()
            messages.forEach { msg ->
                msgsArray.add(JsonObject().apply {
                    addProperty("role", msg.role)
                    addProperty("content", msg.content)
                })
            }
            add("messages", msgsArray)

            if (tools != null && tools.isNotEmpty()) {
                val toolsArray = JsonArray()
                tools.forEach { tool ->
                    toolsArray.add(JsonObject().apply {
                        addProperty("name", tool.name)
                        addProperty("description", tool.description)
                        add("input_schema", gson.toJsonTree(tool.inputSchema))
                    })
                }
                add("tools", toolsArray)
            }
        }

        val response = client.post("${config.baseUrl}/v1/messages") {
            contentType(ContentType.Application.Json)
            header("x-api-key", config.apiKey)
            header("anthropic-version", "2023-06-01")
            setBody(body.toString())
        }

        val respText = response.bodyAsText()
        val respJson = gson.fromJson(respText, JsonObject::class.java)

        if (respJson.has("error")) {
            val err = respJson.getAsJsonObject("error").get("message").asString
            return LlmResponse(content = "", error = err)
        }

        val content = respJson.getAsJsonArray("content")
        val textParts = StringBuilder()
        var toolUse: JsonObject? = null

        content?.forEach { block ->
            val obj = block.asJsonObject
            when (obj.get("type").asString) {
                "text" -> textParts.append(obj.get("text").asString)
                "tool_use" -> toolUse = obj
            }
        }

        val tokens = respJson.getAsJsonObject("usage")?.let {
            (it.get("input_tokens")?.asInt ?: 0) + (it.get("output_tokens")?.asInt ?: 0)
        } ?: 0

        val finalContent = if (toolUse != null) {
            gson.toJson(toolUse)
        } else {
            textParts.toString()
        }

        return LlmResponse(content = finalContent, tokensUsed = tokens)
    }

    private suspend fun chatOpenAI(config: Config, messages: List<Message>, systemPrompt: String?, tools: List<ToolDef>?): LlmResponse {
        val msgsArray = JsonArray()
        if (systemPrompt != null) {
            msgsArray.add(JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", systemPrompt)
            })
        }
        messages.forEach { msg ->
            msgsArray.add(JsonObject().apply {
                addProperty("role", msg.role)
                addProperty("content", msg.content)
            })
        }

        val body = JsonObject().apply {
            addProperty("model", config.model)
            add("messages", msgsArray)
            addProperty("max_tokens", 4096)

            if (tools != null && tools.isNotEmpty()) {
                val toolsArray = JsonArray()
                tools.forEach { tool ->
                    toolsArray.add(JsonObject().apply {
                        addProperty("type", "function")
                        add("function", JsonObject().apply {
                            addProperty("name", tool.name)
                            addProperty("description", tool.description)
                            add("parameters", gson.toJsonTree(tool.inputSchema))
                        })
                    })
                }
                add("tools", toolsArray)
            }
        }

        val url = when {
            config.baseUrl.contains("googleapis.com") ->
                "${config.baseUrl}/v1beta/openai/chat/completions"
            else -> "${config.baseUrl}/v1/chat/completions"
        }

        val response = client.post(url) {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer ${config.apiKey}")
            setBody(body.toString())
        }

        val respText = response.bodyAsText()
        val respJson = gson.fromJson(respText, JsonObject::class.java)

        if (respJson.has("error")) {
            val err = respJson.getAsJsonObject("error").get("message").asString
            return LlmResponse(content = "", error = err)
        }

        val choice = respJson.getAsJsonArray("choices")?.get(0)?.asJsonObject
        val msg = choice?.getAsJsonObject("message")
        val content = msg?.get("content")?.asString ?: ""
        val toolCalls = msg?.getAsJsonArray("tool_calls")

        val tokens = respJson.getAsJsonObject("usage")?.let {
            (it.get("total_tokens")?.asInt ?: 0)
        } ?: 0

        val finalContent = if (toolCalls != null && toolCalls.size() > 0) {
            gson.toJson(toolCalls)
        } else {
            content
        }

        return LlmResponse(content = finalContent, tokensUsed = tokens)
    }

    fun close() {
        client.close()
    }
}
