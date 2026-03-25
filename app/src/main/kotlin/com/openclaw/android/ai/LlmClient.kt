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
    private val client = HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)  // LLM can be slow with big context
                writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
    }
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
            // Try primary provider
            val primary = try {
                callProvider(config, messages, systemPrompt, tools)
            } catch (e: Exception) {
                ServiceState.addLog("LLM primary error: ${e.message}")
                LlmResponse(content = "", error = e.message)
            }

            // If primary succeeded, return it
            if (primary.error == null && primary.content.isNotBlank()) return@withContext primary

            // Log why primary failed
            if (primary.error != null) {
                ServiceState.addLog("LLM primary failed (${AgentConfig.activeProvider}/${config.model}): ${primary.error?.take(100)}")
            } else if (primary.content.isBlank()) {
                ServiceState.addLog("LLM primary returned empty (${AgentConfig.activeProvider}/${config.model})")
            }

            // Fallback: try other providers with saved keys
            // Use fast/efficient models for fallback (not the expensive defaults)
            val actualProvider = AgentConfig.activeProvider
            val fallbackModels = mapOf(
                "gemini" to "gemini-2.5-flash",
                "google" to "gemini-2.5-flash",
                "anthropic" to "claude-haiku-4-5-20251001",
                "openai" to "gpt-4.1-mini",
                "minimax" to "MiniMax-M2.5-Highspeed",
                "openrouter" to "google/gemini-2.5-flash",
                "deepseek" to "deepseek-chat",
                "groq" to "llama-3.3-70b-versatile",
                "huggingface" to "meta-llama/Llama-3.3-70B-Instruct",
                "sambanova" to "Meta-Llama-3.3-70B-Instruct",
                "cerebras" to "llama-3.3-70b",
                "pollinations" to "openai",
            )
            // Priority: fast free providers first, then paid
            val fallbackOrder = listOf("groq", "cerebras", "sambanova", "gemini", "google", "pollinations",
                "deepseek", "openrouter", "huggingface", "minimax", "openai", "anthropic")
            val fallbackProviders = fallbackOrder
                .filter { it != actualProvider && it != "custom" && (AgentConfig.getKeyForProvider(it).isNotBlank() || it in AgentConfig.NO_AUTH_PROVIDERS) }

            if (fallbackProviders.isEmpty()) return@withContext primary // No fallbacks available

            for (fallback in fallbackProviders) {
                ServiceState.addLog("LLM fallback: trying $fallback")
                try {
                    val fbConfig = AgentConfig.buildConfigForProvider(fallback)
                    // Override model with fast/efficient variant for fallback
                    val efficientModel = fallbackModels[fallback] ?: fbConfig.model
                    val optimizedConfig = fbConfig.copy(model = efficientModel)
                    val result = callProvider(optimizedConfig, messages, systemPrompt, tools)
                    if (result.error == null && result.content.isNotBlank()) {
                        ServiceState.addLog("LLM fallback: $fallback/$efficientModel succeeded")
                        return@withContext result
                    }
                } catch (e: Exception) {
                    ServiceState.addLog("LLM fallback $fallback failed: ${e.message?.take(60)}")
                }
            }

            // All fallbacks failed — return original error
            primary
        }
    }

    private suspend fun callProvider(config: Config, messages: List<Message>, systemPrompt: String?, tools: List<ToolDef>?): LlmResponse {
        return when (config.provider) {
            "anthropic", "minimax" -> chatAnthropic(config, messages, systemPrompt, tools)
            else -> chatOpenAI(config, messages, systemPrompt, tools)
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
            header("anthropic-beta", "max-tokens-3-5-sonnet-2024-07-15")
            setBody(body.toString())
        }

        val anthropicStatus = response.status.value
        if (anthropicStatus == 429) {
            ServiceState.addLog("Rate limited by ${config.provider} (429)")
            return LlmResponse(content = "", error = "Rate limit reached — retry later or switch provider.")
        }

        val respText = response.bodyAsText()
        if (respText.isBlank()) return LlmResponse(content = "", error = "Empty response from API")
        if (!respText.trimStart().startsWith("{")) return LlmResponse(content = "", error = "Not JSON: ${respText.take(150)}")
        val respJson = try {
            val reader = com.google.gson.stream.JsonReader(java.io.StringReader(respText))
            reader.isLenient = true
            gson.fromJson<JsonObject>(reader, JsonObject::class.java)
        } catch (e: Exception) { return LlmResponse(content = "", error = "Parse error: ${e.message?.take(100)}") }

        if (respJson.has("error")) {
            val errObj = respJson.get("error")
            val err = if (errObj.isJsonObject) errObj.asJsonObject.get("message")?.asString ?: errObj.toString() else errObj.toString()
            return LlmResponse(content = "", error = err)
        }

        val content = respJson.getAsJsonArray("content")
        val textParts = StringBuilder()
        var toolUse: JsonObject? = null

        content?.forEach { block ->
            val obj = block.asJsonObject
            when (obj.get("type")?.asString) {
                "text" -> textParts.append(obj.get("text")?.asString ?: "")
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
            // All OpenAI-compatible endpoints (including Google) use Bearer token
            // Skip auth only for no-auth providers (Pollinations)
            if (config.apiKey.isNotBlank()) {
                header("Authorization", "Bearer ${config.apiKey}")
            }
            setBody(body.toString())
        }

        val statusCode = response.status.value
        val respText = response.bodyAsText()

        // Rate limit detection
        if (statusCode == 429) {
            val retryAfter = response.headers["Retry-After"] ?: "60"
            ServiceState.addLog("Rate limited by ${config.provider} (429). Retry after ${retryAfter}s")
            return LlmResponse(content = "", error = "Rate limit reached. Free tier limit exceeded — retry in ${retryAfter}s or switch to another provider in Settings.")
        }
        if (statusCode == 402 || statusCode == 403) {
            ServiceState.addLog("Auth/quota error from ${config.provider} ($statusCode)")
            return LlmResponse(content = "", error = "API quota exceeded or invalid key ($statusCode). Check your API key or switch provider.")
        }

        if (respText.isBlank()) return LlmResponse(content = "", error = "Empty response from API")
        val trimmedResp = respText.trimStart()
        if (!trimmedResp.startsWith("{") && !trimmedResp.startsWith("[")) return LlmResponse(content = "", error = "Not JSON: ${respText.take(150)}")

        // Handle JSON array responses (some providers wrap errors in arrays)
        val jsonStr = if (trimmedResp.startsWith("[")) {
            try {
                val arr = gson.fromJson(trimmedResp, JsonArray::class.java)
                if (arr.size() > 0 && arr[0].isJsonObject) arr[0].asJsonObject.toString() else trimmedResp
            } catch (_: Exception) { trimmedResp }
        } else trimmedResp

        val respJson = try {
            val reader = com.google.gson.stream.JsonReader(java.io.StringReader(jsonStr))
            reader.isLenient = true
            gson.fromJson<JsonObject>(reader, JsonObject::class.java)
        } catch (e: Exception) { return LlmResponse(content = "", error = "Parse error: ${e.message?.take(100)}") }

        if (respJson.has("error")) {
            val errObj = respJson.get("error")
            val errMsg = if (errObj.isJsonObject) errObj.asJsonObject.get("message")?.asString ?: errObj.toString() else errObj.toString()
            // Detect rate limit in error message body too
            val isRateLimit = errMsg.contains("rate", ignoreCase = true) && errMsg.contains("limit", ignoreCase = true)
            if (isRateLimit) ServiceState.addLog("Rate limited: $errMsg")
            return LlmResponse(content = "", error = if (isRateLimit) "Rate limit: $errMsg — try another provider or wait." else errMsg)
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
