package com.openclaw.android.ai

import com.openclaw.android.ui.screens.KanbanTask
import com.openclaw.android.ui.screens.TaskBoard
import com.openclaw.android.util.NotificationHelper
import com.openclaw.android.util.ServiceState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sub-agent manager. The main chat agent acts as an orchestrator —
 * it spawns sub-agents for long-running tasks so the chat doesn't block.
 *
 * Flow:
 * 1. User says "scrape these 5 URLs and summarize them"
 * 2. Main agent adds task to Pending via TaskBoard
 * 3. SubAgentManager picks it up, moves to Active, runs it
 * 4. On completion, moves to Done, notifies user
 *
 * The orchestrator pattern keeps chat responsive while tasks run in background.
 */
object SubAgentManager {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val llmClient = LlmClient()

    data class SubAgent(
        val id: String,
        val taskId: Long,
        val title: String,
        val status: String, // "running", "completed", "failed"
        val result: String? = null
    )

    private val _agents = MutableStateFlow<List<SubAgent>>(emptyList())
    val agents = _agents.asStateFlow()

    /**
     * Spawn a sub-agent to execute a task.
     * Called by the main agent when it decides to delegate work.
     */
    fun spawn(taskTitle: String, prompt: String, config: LlmClient.Config): String {
        val agentId = "sub_${System.currentTimeMillis()}"

        // Add to pending kanban
        val taskId = TaskBoard.addPending(taskTitle)

        // Register sub-agent
        _agents.value = _agents.value + SubAgent(agentId, taskId, taskTitle, "running")
        ServiceState.addLog("SubAgent spawned: $agentId — $taskTitle")

        // Move to active
        TaskBoard.moveToActive(taskId, agentId)

        // Run in background
        scope.launch {
            try {
                val agentLoop = AgentLoop(llmClient)
                val systemPrompt = """You are a sub-agent of OpenClaw, executing a specific task.
Complete the task thoroughly and return a clear result.
You have access to all tools: screen control, web scraping, search, shell, files, APIs.
Be concise in your response — this will be reported back to the user."""

                val result = agentLoop.run(config, prompt, systemPrompt)

                // Update state
                TaskBoard.moveToDone(taskId)
                _agents.value = _agents.value.map {
                    if (it.id == agentId) it.copy(status = "completed", result = result) else it
                }
                ServiceState.addLog("SubAgent completed: $agentId — ${result.take(100)}")
                NotificationHelper.notifyTaskComplete("$taskTitle\n${result.take(200)}")

            } catch (e: Exception) {
                TaskBoard.moveToDone(taskId) // Still move to done (failed)
                _agents.value = _agents.value.map {
                    if (it.id == agentId) it.copy(status = "failed", result = "Error: ${e.message}") else it
                }
                ServiceState.addLog("SubAgent failed: $agentId — ${e.message}")
                NotificationHelper.notifyError("Task failed: $taskTitle — ${e.message}")
            }
        }

        return "Task '$taskTitle' spawned as sub-agent ($agentId). It's running in the background — I'll notify you when it's done."
    }

    /**
     * Get results of completed sub-agents.
     */
    fun getCompletedResults(): List<SubAgent> =
        _agents.value.filter { it.status == "completed" || it.status == "failed" }

    /**
     * Get running sub-agents.
     */
    fun getRunning(): List<SubAgent> =
        _agents.value.filter { it.status == "running" }

    fun cleanup() {
        scope.cancel()
        llmClient.close()
    }
}
