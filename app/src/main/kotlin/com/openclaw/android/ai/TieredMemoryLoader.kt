package com.openclaw.android.ai

import com.openclaw.android.OpenClawApplication
import com.openclaw.android.data.AppDatabase

/**
 * L0/L1/L2 Tiered Memory Loading (inspired by OpenViking, ByteDance)
 *
 * L0 (Always loaded, ~5 items): Core identity, user preferences, critical skills
 *    - type = "preference" with importance >= 0.8
 *    - Most accessed memories (top 5 by accessCount)
 *
 * L1 (Loaded on relevance, ~10 items): Recent context, task-relevant facts
 *    - Recent conversation memories (last 24h)
 *    - Keyword-matched memories based on current user message
 *
 * L2 (On-demand via memory_search): Everything else in SQLite
 *    - Agent explicitly calls memory_search when needed
 *    - Not loaded into system prompt
 */
object TieredMemoryLoader {

    /**
     * Load L0 + L1 memories for system prompt injection.
     * Returns formatted string to append to system prompt.
     *
     * @param userMessage Current user message (for L1 relevance matching)
     * @return Formatted memory context string, or empty if no memories
     */
    suspend fun loadForPrompt(userMessage: String): String {
        val db = AppDatabase.getInstance(OpenClawApplication.instance)
        val dao = db.memoryDao()

        // L0: Core memories — always loaded
        val allMemories = dao.getTopMemories(200) // Limit to 200 for performance
        val loadedIds = mutableSetOf<Long>() // Track IDs to prevent duplicates
        val l0 = mutableListOf<String>()

        // L0a: High-importance preferences
        allMemories.filter { it.type == "preference" && it.importance >= 0.8f }
            .take(3)
            .forEach { loadedIds.add(it.id); l0.add("[pref] ${it.content.take(150)}") }

        // L0b: Most accessed memories (user keeps querying these)
        allMemories.sortedByDescending { it.accessCount }
            .filter { it.id !in loadedIds }
            .take(3)
            .forEach { loadedIds.add(it.id); l0.add("[core] ${it.content.take(150)}") }

        // L1: Context-relevant memories
        val l1 = mutableListOf<String>()

        // L1a: Recent (last 24h)
        val cutoff24h = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
        allMemories.filter { it.createdAt > cutoff24h && it.id !in loadedIds }
            .sortedByDescending { it.importance }
            .take(5)
            .forEach { loadedIds.add(it.id); l1.add("[recent] ${it.content.take(150)}") }

        // L1b: Keyword-matched from current message
        if (userMessage.length > 5) {
            val queryWords = userMessage.lowercase()
                .split(Regex("[\\s,.:;!?]+"))
                .filter { it.length > 2 }
                .toSet()

            if (queryWords.isNotEmpty()) {
                allMemories
                    .filter { mem ->
                        mem.id !in loadedIds &&
                        mem.content.lowercase().split(Regex("[\\s,.:;!?]+")).toSet().let { memWords ->
                            queryWords.any { qw -> memWords.any { it.contains(qw) || qw.contains(it) } }
                        }
                    }
                    .sortedByDescending { it.importance }
                    .take(5)
                    .forEach { loadedIds.add(it.id); l1.add("[relevant] ${it.content.take(150)}") }
            }
        }

        // Record access for all loaded memories
        loadedIds.forEach { dao.recordAccess(it) }

        if (l0.isEmpty() && l1.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine("## SQLite Memory (auto-loaded)")
        if (l0.isNotEmpty()) {
            sb.appendLine("### L0 — Core (always loaded)")
            l0.forEach { sb.appendLine("- $it") }
        }
        if (l1.isNotEmpty()) {
            sb.appendLine("### L1 — Context-relevant")
            l1.forEach { sb.appendLine("- $it") }
        }
        sb.appendLine("### L2 — Use memory_search for deeper recall")
        return sb.toString()
    }
}
