package com.openclaw.android.data.entity

import androidx.room.*

@Entity(tableName = "agent_sessions")
data class AgentSessionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val model: String,                      // "claude-sonnet-4.6"
    val status: String = "idle",            // "idle", "thinking", "executing", "error", "stopped"
    val currentTask: String? = null,
    val tokensUsed: Long = 0,
    val messagesCount: Int = 0,
    val startedAt: Long = System.currentTimeMillis(),
    val lastActivityAt: Long = System.currentTimeMillis(),
    val configJson: String = "{}"
)
