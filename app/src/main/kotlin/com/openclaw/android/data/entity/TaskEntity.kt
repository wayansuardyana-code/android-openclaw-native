package com.openclaw.android.data.entity

import androidx.room.*

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String = "",
    val status: String = "inbox",           // "inbox", "in_progress", "review", "done", "failed"
    val priority: Int = 0,                  // 0=normal, 1=high, 2=critical
    val agentId: String? = null,            // which agent is working on this
    val parentTaskId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val metadata: String = "{}"             // JSON for extra data
)
