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
     * Single heartbeat tick. Runs scheduled tasks first, then autonomous HEARTBEAT.md program.
     *
     * Proactive behavior rules:
     * - ALWAYS check scheduled tasks (these are user-requested, always execute)
     * - Run HEARTBEAT.md program (self-improvement, notifications, daily log)
     * - NEVER spam user — only notify for: scheduled task results, important notifications, failures
     * - Silent on idle — no notification if nothing meaningful happened
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
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        ServiceState.addLog("Heartbeat: tick at $now")

        try {
            // ── Phase 1: Execute due scheduled tasks (user-requested, always run) ──
            runScheduledTasks(config)

            // ── Phase 2: Autonomous program (HEARTBEAT.md) ──
            // Skip overnight (23:00-06:00) unless there are pending tasks
            if (hour >= 22 || hour in 0..5) {
                ServiceState.addLog("Heartbeat: overnight — skipping autonomous program")
                return
            }

            val heartbeat = Bootstrap.readFile("HEARTBEAT.md")
            val memory = Bootstrap.readFile("memory.md")
            val user = Bootstrap.readFile("USER.md")
            val skills = Bootstrap.readFile("skills.md")
            val soul = Bootstrap.readFile("SOUL.md")

            val systemPrompt = buildString {
                append(soul)
                append("\n\n## Heartbeat Mode (AUTONOMOUS — no user message)")
                append("\nYou are running autonomously on a timer. No human sent you a message.")
                append("\nYou have the same tools as always. Current time: $now")
                append("\n\n## ANTI-SPAM RULES (CRITICAL)")
                append("\n- Do NOT send_telegram_message unless you found something genuinely important")
                append("\n- 'Important' means: urgent notification, scheduled task result, or critical failure")
                append("\n- Self-improvement (updating skills, reviewing failures) is SILENT — do it without notifying")
                append("\n- If nothing needs doing: reply 'heartbeat: idle' and stop immediately")
                append("\n- Do NOT make up tasks. Only act on: HEARTBEAT instructions, scheduled tasks, or real notifications")
                append("\n\nRead the HEARTBEAT instructions below and execute any applicable tasks.")
                append("\n\n--- HEARTBEAT INSTRUCTIONS ---\n$heartbeat")
                if (memory.isNotBlank()) append("\n\n--- MEMORY (recent) ---\n${memory.takeLast(1000)}")
                if (user.isNotBlank()) append("\n\n--- USER PROFILE ---\n$user")
                if (skills.isNotBlank()) append("\n\n--- SKILLS ---\n${skills.takeLast(500)}")
            }

            val result = agentLoop.run(config, "[HEARTBEAT TICK — $now]", systemPrompt)

            val resultSummary = result.take(200).replace("\n", " ")
            ServiceState.addLog("Heartbeat: result — $resultSummary")

            // Only notify if agent actually DID something meaningful (not idle/self-improvement)
            val shouldNotify = !result.contains("idle", ignoreCase = true) &&
                !result.contains("self-improvement", ignoreCase = true) &&
                !result.contains("updated skills", ignoreCase = true) &&
                !result.contains("no action needed", ignoreCase = true) &&
                result.length > 50
            if (shouldNotify) {
                NotificationHelper.notifyAgentResponse("OpenClaw (auto)", result.take(200))
            }

        } catch (e: Exception) {
            ServiceState.addLog("Heartbeat: execution error — ${e.message?.take(80)}")
        } finally {
            isExecuting = false
        }
    }

    /**
     * Check and execute due scheduled tasks from Room DB.
     * These are user-requested recurring tasks — always execute and notify.
     */
    private suspend fun runScheduledTasks(config: LlmClient.Config) {
        try {
            val db = com.openclaw.android.data.AppDatabase.getInstance(
                com.openclaw.android.OpenClawApplication.instance
            )
            val dueTasks = db.scheduledTaskDao().getDueTasks()
            if (dueTasks.isEmpty()) return

            ServiceState.addLog("Heartbeat: ${dueTasks.size} scheduled task(s) due")
            for (task in dueTasks) {
                // Update nextRunAt BEFORE execution (optimistic) to prevent
                // re-execution if the task takes longer than the heartbeat interval
                val nextRun = System.currentTimeMillis() + (task.intervalMinutes * 60 * 1000L)
                db.scheduledTaskDao().update(task.copy(
                    nextRunAt = nextRun,
                    runCount = task.runCount + 1
                ))

                ServiceState.addLog("Heartbeat: executing task #${task.id}: ${task.prompt.take(40)}")
                try {
                    val taskPrompt = "You are executing a scheduled task. Do this and send results to ${task.gateway}. Be concise.\n\nTask: ${task.prompt}"
                    val result = agentLoop.run(config, task.prompt, taskPrompt)
                    ServiceState.addLog("Heartbeat: task #${task.id} done: ${result.take(80)}")
                } catch (e: Exception) {
                    ServiceState.addLog("Heartbeat: task #${task.id} failed: ${e.message?.take(60)}")
                }
                // Update lastRunAt after execution completes
                db.scheduledTaskDao().update(task.copy(
                    lastRunAt = System.currentTimeMillis(),
                    nextRunAt = nextRun,
                    runCount = task.runCount + 1
                ))
            }
        } catch (e: Exception) {
            ServiceState.addLog("Heartbeat: scheduled tasks error: ${e.message?.take(60)}")
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
