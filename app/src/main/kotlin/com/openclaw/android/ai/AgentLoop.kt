package com.openclaw.android.ai

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.openclaw.android.util.NotificationHelper
import com.openclaw.android.util.ServiceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

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
                    val toolCall = gson.fromJson(content, JsonObject::class.java)
                    val toolName = toolCall.get("name").asString
                    val toolInput = toolCall.getAsJsonObject("input") ?: JsonObject()
                    val toolId = toolCall.get("id")?.asString ?: "tool_$step"

                    ServiceState.addLog("Agent: calling tool $toolName")
                    val toolResult = AndroidTools.executeTool(toolName, toolInput)
                    ServiceState.addLog("Agent: tool $toolName returned ${toolResult.take(100)}...")

                    // Add assistant message with tool use
                    messages.add(LlmClient.Message("assistant", content))
                    // Add tool result
                    messages.add(LlmClient.Message("user", "[Tool result for $toolName]: $toolResult"))
                    continue
                }

                // Check if it's a tool call (OpenAI format)
                if (content.startsWith("[") && content.contains("\"function\"")) {
                    val toolCalls = gson.fromJson(content, JsonArray::class.java)
                    val results = StringBuilder()

                    for (tc in toolCalls) {
                        val call = tc.asJsonObject
                        val fn = call.getAsJsonObject("function")
                        val toolName = fn.get("name").asString
                        val argsStr = fn.get("arguments").asString
                        val toolInput = gson.fromJson(argsStr, JsonObject::class.java)

                        ServiceState.addLog("Agent: calling tool $toolName")
                        val toolResult = AndroidTools.executeTool(toolName, toolInput)
                        results.append("[$toolName]: $toolResult\n")
                    }

                    messages.add(LlmClient.Message("assistant", content))
                    messages.add(LlmClient.Message("user", results.toString()))
                    continue
                }

                // Plain text response — we're done
                ServiceState.addLog("Agent: final response (${content.length} chars, ${response.tokensUsed} tokens)")
                ConversationManager.addAssistantMessage(content)
                NotificationHelper.notifyAgentResponse("OpenClaw", content.take(200))
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
}
