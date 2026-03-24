package com.openclaw.android.ai

import com.openclaw.android.util.ServiceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

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
        "custom" to 128_000,
    )

    // Compaction triggers at 70% of context window
    private const val COMPACTION_THRESHOLD = 0.70

    private val _history = mutableListOf<LlmClient.Message>()
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
    fun addUserMessage(content: String) {
        _history.add(LlmClient.Message("user", content))
        _tokenCount.value += estimateTokens(content)
    }

    /** Add an assistant response */
    fun addAssistantMessage(content: String) {
        _history.add(LlmClient.Message("assistant", content))
        _tokenCount.value += estimateTokens(content)
    }

    /** Record actual tokens used from API response */
    fun recordTokensUsed(tokens: Int) {
        _totalTokensUsed.value += tokens
    }

    /** Check if compaction is needed and do it */
    fun maybeCompact(provider: String) {
        val limit = getContextLimit(provider)
        val threshold = (limit * COMPACTION_THRESHOLD).toLong()

        if (_tokenCount.value > threshold && _history.size > 6) {
            ServiceState.addLog("Context compaction: ${_tokenCount.value} tokens > ${threshold} threshold")
            compact()
        }
    }

    /** Compact conversation by summarizing older messages */
    private fun compact() {
        if (_history.size <= 4) return

        // Keep last 4 messages, summarize the rest
        val toSummarize = _history.dropLast(4)
        val kept = _history.takeLast(4)

        val summary = buildString {
            append("[Conversation summary: ")
            var userCount = 0
            var assistantCount = 0
            toSummarize.forEach { msg ->
                if (msg.role == "user") userCount++
                else assistantCount++
            }
            append("$userCount user messages and $assistantCount assistant responses were compacted. ")

            // Keep key topics
            val topics = toSummarize
                .filter { it.role == "user" }
                .joinToString("; ") { it.content.take(100) }
            append("Topics discussed: $topics")
            append("]")
        }

        _history.clear()
        _history.add(LlmClient.Message("user", summary))
        _history.addAll(kept)

        // Recalculate token count
        _tokenCount.value = _history.sumOf { estimateTokens(it.content).toLong() }
        ServiceState.addLog("Compacted to ${_history.size} messages, ${_tokenCount.value} tokens")
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
