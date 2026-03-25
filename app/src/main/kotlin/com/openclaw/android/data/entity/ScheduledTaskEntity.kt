package com.openclaw.android.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scheduled_tasks")
data class ScheduledTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val prompt: String,                    // What to tell the agent
    val cronExpression: String = "",       // Cron-like: "0 8 * * *" (8am daily)
    val intervalMinutes: Int = 0,          // Alternative: run every N minutes
    val nextRunAt: Long = Long.MAX_VALUE,              // Next scheduled execution timestamp
    val lastRunAt: Long = 0,              // Last execution timestamp
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val runCount: Int = 0,
    val gateway: String = "chat"           // Where to send result: chat, telegram, group
)
