package com.openclaw.android.data.entity

import androidx.room.*

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val type: String = "general",           // "general", "skill", "fact", "conversation"
    val source: String = "user",            // "user", "agent", "system"
    val embedding: String = "",             // JSON float array for vector search
    val importance: Float = 0.5f,
    val accessCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val lastAccessedAt: Long = System.currentTimeMillis(),
    val metadata: String = "{}"
)
