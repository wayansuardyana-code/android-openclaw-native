package com.openclaw.android.data.entity

import androidx.room.*

@Entity(tableName = "connectors")
data class ConnectorEntity(
    @PrimaryKey val id: String,            // "llm_anthropic", "db_postgres", etc
    val category: String,                   // "llm", "database", "tool", "channel", "file_gen"
    val name: String,                       // "Anthropic Claude"
    val description: String,
    val iconName: String,                   // material icon name
    val enabled: Boolean = false,
    val configJson: String = "{}",          // JSON config (API keys, connection strings, etc)
    val status: String = "disconnected",    // "connected", "disconnected", "error"
    val lastUsed: Long = 0,
    val errorMessage: String? = null
)
