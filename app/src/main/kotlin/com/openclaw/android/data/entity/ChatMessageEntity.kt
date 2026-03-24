package com.openclaw.android.data.entity

import androidx.room.*

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,              // "user", "assistant", "system"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val sessionId: String = "default",
    val attachmentName: String? = null
)
