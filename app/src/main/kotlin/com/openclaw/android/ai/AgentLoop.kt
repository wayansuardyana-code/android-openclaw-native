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
        val toolsUsed = mutableListOf<String>() // Track for auto-learn

        try {
            while (step < maxSteps) {
                step++
                ServiceState.addLog("Agent: step $step/$maxSteps")

                val response = llmClient.chat(config, messages, systemPrompt, tools)
                _totalTokens.value += response.tokensUsed
                ConversationManager.recordTokensUsed(response.tokensUsed)

                if (response.error != null) {
                    ServiceState.addLog("Agent: LLM error — ${response.error}")
                    NotificationHelper.notifyError(response.error)
                    return "Error: ${response.error}"
                }

                val content = response.content

                // Check if it's a tool call (Anthropic format)
                if (content.startsWith("{") && content.contains("\"type\":\"tool_use\"")) {
                    try {
                        val toolCall = parseLenient(content, JsonObject::class.java)
                        val toolName = toolCall.get("name")?.asString ?: continue
                        val toolInput = toolCall.getAsJsonObject("input") ?: JsonObject()

                        toolsUsed.add(toolName)
                        ServiceState.addLog("Agent: calling tool $toolName")
                        val toolResult = AndroidTools.executeTool(toolName, toolInput)
                        ServiceState.addLog("Agent: tool $toolName returned ${toolResult.take(100)}...")

                        messages.add(LlmClient.Message("assistant", content))
                        messages.add(LlmClient.Message("user", "[Tool result for $toolName]: $toolResult"))
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
                            ServiceState.addLog("Agent: calling tool $toolName")
                            val toolResult = AndroidTools.executeTool(toolName, toolInput)
                            results.append("[$toolName]: $toolResult\n")
                        }

                        messages.add(LlmClient.Message("assistant", content))
                        messages.add(LlmClient.Message("user", results.toString()))
                        continue
                    } catch (e: Exception) {
                        ServiceState.addLog("Agent: tool parse error (OpenAI) — ${e.message?.take(80)}")
                        // Treat as text response if parse fails
                    }
                }

                // Plain text response — we're done
                ServiceState.addLog("Agent: final response (${content.length} chars, ${response.tokensUsed} tokens)")
                ConversationManager.addAssistantMessage(content)
                NotificationHelper.notifyAgentResponse("OpenClaw", content.take(200))

                // Auto-learn: save successful multi-step tasks as skills
                if (step >= 5 && toolsUsed.size >= 3) {
                    autoLearn(userMessage, toolsUsed, step)
                }

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
            // Non-critical — don't crash if auto-learn fails
            ServiceState.addLog("Auto-learn error: ${e.message?.take(60)}")
        }
    }
}
