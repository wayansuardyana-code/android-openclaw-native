package com.openclaw.android.ai

import com.openclaw.android.util.ServiceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Manages conversation history with token tracking and auto-compaction.
 *
 * - Tracks all messages in the conversation
 * - Estimates token count (1 token ≈ 4 chars)
 * - Auto-compacts when approaching context limit
 * - Shows token usage: "used / max"
 */
object ConversationManager {

    // Context window limits per provider family
    private val contextLimits = mapOf(
        "anthropic" to 200_000,
        "minimax" to 80_000,
        "openai" to 128_000,
        "google" to 1_000_000,
        "deepseek" to 64_000,
        "mistral" to 128_000,
        "groq" to 32_768,
        "xai" to 128_000,
        "together" to 128_000,
        "fireworks" to 128_000,
        "openrouter" to 200_000,
        "ollama" to 8_192,
        "kimi" to 128_000,
        "moonshot" to 128_000,
        "custom" to 128_000,
    )

    // Compaction triggers at 70% of context window
    private const val COMPACTION_THRESHOLD = 0.70

    private val _history = java.util.Collections.synchronizedList(mutableListOf<LlmClient.Message>())
    private val _tokenCount = MutableStateFlow(0L)
    val tokenCount = _tokenCount.asStateFlow()

    private val _totalTokensUsed = MutableStateFlow(0L)
    val totalTokensUsed = _totalTokensUsed.asStateFlow()

    fun getContextLimit(provider: String): Int =
        contextLimits[provider] ?: 128_000

    fun getContextDisplay(provider: String): String {
        val used = _tokenCount.value
        val max = getContextLimit(provider)
        return "${formatTokens(used)} / ${formatTokens(max.toLong())}"
    }

    private fun formatTokens(n: Long): String = when {
        n >= 1_000_000 -> "${n / 1_000}k"
        n >= 1_000 -> "${n / 1_000}k"
        else -> "$n"
    }

    /** Estimate tokens from text (rough: 1 token ≈ 4 chars) */
    private fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)

    /** Get conversation history for the LLM */
    fun getHistory(): List<LlmClient.Message> = _history.toList()

    /** Add a user message */
    @Synchronized fun addUserMessage(content: String) {
        _history.add(LlmClient.Message("user", content))
        _tokenCount.update { it + estimateTokens(content) }
    }

    /** Add an assistant response */
    @Synchronized fun addAssistantMessage(content: String) {
        _history.add(LlmClient.Message("assistant", content))
        _tokenCount.update { it + estimateTokens(content) }
    }

    /** Record actual tokens used from API response */
    fun recordTokensUsed(tokens: Int) {
        _totalTokensUsed.update { it + tokens }
    }

    /** Check if compaction is needed and do it */
    @Synchronized fun maybeCompact(provider: String) {
        val limit = getContextLimit(provider)
        val threshold = (limit * COMPACTION_THRESHOLD).toLong()

        if (_tokenCount.value > threshold && _history.size > 8) {
            ServiceState.addLog("Context compaction: ${_tokenCount.value} tokens > ${threshold} threshold")
            compact()
        }
    }

    /**
     * Hierarchical compaction — preserves more context than naive summarization.
     *
     * Instead of summarizing ALL old messages into one blob, this:
     * 1. Keeps last 6 messages (immediate context)
     * 2. Extracts key facts from tool results (facts survive compactions)
     * 3. Summarizes user requests and agent actions separately
     * 4. Preserves any existing compacted summary (chain of compactions)
     */
    @Synchronized private fun compact() {
        if (_history.size <= 6) return

        val keep = _history.takeLast(6)
        val old = _history.dropLast(6)

        // Extract key facts from tool results in old messages
        val facts = mutableListOf<String>()
        old.forEach { msg ->
            if (msg.role == "user" && msg.content.startsWith("[Tool result for ")) {
                val toolName = msg.content
                    .substringAfter("[Tool result for ")
                    .substringBefore("]")
                val result = msg.content.substringAfter("]: ").take(100)
                if (result.length > 20 && !result.startsWith("{\"error\"")) {
                    facts.add("$toolName: $result")
                }
            }
        }

        // Build hierarchical summary
        val summaryParts = mutableListOf<String>()

        // Part 1: Conversation summary (what was discussed)
        val userMessages = old.filter { msg ->
            msg.role == "user" &&
                !msg.content.startsWith("[Tool result") &&
                !msg.content.startsWith("[SYSTEM")
        }
        val assistantMessages = old.filter { it.role == "assistant" }

        if (userMessages.isNotEmpty()) {
            summaryParts.add(
                "User asked: ${userMessages.joinToString("; ") { it.content.take(60) }}"
            )
        }
        if (assistantMessages.isNotEmpty()) {
            summaryParts.add(
                "Agent did: ${assistantMessages.joinToString("; ") { it.content.take(60) }}"
            )
        }

        // Part 2: Key facts from tool results
        if (facts.isNotEmpty()) {
            summaryParts.add(
                "Key facts discovered:\n${facts.take(10).joinToString("\n") { "- $it" }}"
            )
        }

        // Part 3: Preserve any existing compacted summary (chain of compactions)
        val existingCompaction = old.firstOrNull { msg ->
            msg.role == "system" && msg.content.startsWith("[Context summary")
        }
        if (existingCompaction != null) {
            summaryParts.add("Previous context: ${existingCompaction.content.take(300)}")
        }

        val summary = "[Context summary \u2014 earlier conversation compacted]\n${summaryParts.joinToString("\n\n")}"
        val originalSize = old.size + keep.size

        _history.clear()
        _history.add(LlmClient.Message("system", summary))
        _history.addAll(keep)

        // Recalculate token count
        _tokenCount.value = _history.sumOf { estimateTokens(it.content).toLong() }
        ServiceState.addLog(
            "Context compacted: $originalSize \u2192 ${_history.size} messages, ${facts.size} facts preserved"
        )
    }

    /** Clear all history */
    fun clear() {
        _history.clear()
        _tokenCount.value = 0
    }

    /** Get stats */
    fun getStats(): Map<String, Any> = mapOf(
        "messages" to _history.size,
        "estimatedTokens" to _tokenCount.value,
        "totalTokensUsed" to _totalTokensUsed.value
    )
}
