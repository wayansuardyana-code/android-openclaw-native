package com.openclaw.android.service

import com.openclaw.android.ai.*
import com.openclaw.android.util.NotificationHelper
import com.openclaw.android.util.ServiceState
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Autonomous Heartbeat — the proactive agent loop.
 *
 * Runs on a configurable interval (default 30 min).
 * Each heartbeat:
 * 1. Reads HEARTBEAT.md for scheduled tasks and triggers
 * 2. Checks conditions (time, notifications, idle state)
 * 3. Executes matching tasks via AgentLoop
 * 4. Logs results to memory.md
 *
 * This is what makes the agent PROACTIVE — it acts without being asked.
 * Inspired by Karpathy's autoresearch: program.md drives autonomous experiments.
 * Here, HEARTBEAT.md drives autonomous device tasks.
 */
class HeartbeatService {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val llmClient = LlmClient()
    private val agentLoop = AgentLoop(llmClient)
    private var heartbeatJob: Job? = null

    @Volatile
    private var isRunning = false

    @Volatile
    private var intervalMs = 30 * 60 * 1000L // 30 minutes default

    @Volatile
    private var lastHeartbeat = 0L

    @Volatile
    private var isExecuting = false // Prevent overlapping runs

    fun start() {
        if (isRunning) return
        isRunning = true
        ServiceState.addLog("Heartbeat: starting (interval ${intervalMs / 60000}min)")

        heartbeatJob = scope.launch {
            // Initial delay — let other services settle
            delay(10_000)

            while (isRunning && isActive) {
                try {
                    tick()
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    ServiceState.addLog("Heartbeat: error — ${e.message?.take(80)}")
                }
                delay(intervalMs)
            }
        }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        heartbeatJob?.cancel()
        heartbeatJob = null
        ServiceState.addLog("Heartbeat: stopped")
    }

    fun isActive(): Boolean = isRunning

    /**
     * Single heartbeat tick. Reads HEARTBEAT.md, evaluates conditions,
     * executes tasks if needed.
     */
    private suspend fun tick() {
        if (isExecuting) {
            ServiceState.addLog("Heartbeat: skipped (previous still running)")
            return
        }

        // Check if LLM is configured
        val config = try { AgentConfig.toLlmConfig() } catch (_: Exception) { return }
        if (config.apiKey.isBlank()) return

        isExecuting = true
        lastHeartbeat = System.currentTimeMillis()
        val now = SimpleDateFormat("HH:mm", Locale.US).format(Date())
        ServiceState.addLog("Heartbeat: tick at $now")

        try {
            // Read HEARTBEAT.md for instructions
            val heartbeat = Bootstrap.readFile("HEARTBEAT.md")
            val memory = Bootstrap.readFile("memory.md")
            val user = Bootstrap.readFile("USER.md")
            val skills = Bootstrap.readFile("skills.md")
            val soul = Bootstrap.readFile("SOUL.md")

            // Build the autonomous prompt
            val systemPrompt = buildString {
                append(soul)
                append("\n\n## Heartbeat Mode (AUTONOMOUS — no user message)")
                append("\nYou are running autonomously on a timer. No human sent you a message.")
                append("\nYou have the same tools as always. Current time: $now")
                append("\n\nRead the HEARTBEAT instructions below and execute any applicable tasks.")
                append("\nDo NOT send messages to the user unless the heartbeat instructions say to.")
                append("\nIf nothing needs doing, just reply 'heartbeat: idle' and stop.")
                append("\n\n--- HEARTBEAT INSTRUCTIONS ---\n$heartbeat")
                if (memory.isNotBlank()) append("\n\n--- MEMORY ---\n$memory")
                if (user.isNotBlank()) append("\n\n--- USER PROFILE ---\n$user")
                if (skills.isNotBlank()) append("\n\n--- SKILLS ---\n$skills")
            }

            // Run the agent
            val result = agentLoop.run(config, "[HEARTBEAT TICK — $now]", systemPrompt)

            // Log the result
            val resultSummary = result.take(200).replace("\n", " ")
            ServiceState.addLog("Heartbeat: result — $resultSummary")

            // If agent did something meaningful (not just "idle"), notify
            if (!result.contains("idle", ignoreCase = true) && result.length > 20) {
                NotificationHelper.notifyAgentResponse("Nate (auto)", result.take(200))
            }

        } catch (e: Exception) {
            ServiceState.addLog("Heartbeat: execution error — ${e.message?.take(80)}")
        } finally {
            isExecuting = false
        }
    }

    /**
     * Force an immediate heartbeat (for testing or manual trigger).
     */
    fun triggerNow() {
        scope.launch { tick() }
    }

    fun setInterval(minutes: Int) {
        intervalMs = minutes.coerceAtLeast(5).toLong() * 60 * 1000
        ServiceState.addLog("Heartbeat: interval set to ${minutes}min")
    }

    fun getLastHeartbeat(): Long = lastHeartbeat
}
