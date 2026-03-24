package com.openclaw.android.data.dao

import androidx.room.*
import com.openclaw.android.data.entity.AgentSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentSessionDao {
    @Query("SELECT * FROM agent_sessions ORDER BY lastActivityAt DESC")
    fun getAll(): Flow<List<AgentSessionEntity>>

    @Query("SELECT * FROM agent_sessions WHERE status != 'stopped'")
    fun getActive(): Flow<List<AgentSessionEntity>>

    @Query("SELECT * FROM agent_sessions WHERE id = :id")
    suspend fun getById(id: String): AgentSessionEntity?

    @Upsert
    suspend fun upsert(session: AgentSessionEntity)

    @Query("UPDATE agent_sessions SET status = :status, lastActivityAt = :now WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, now: Long = System.currentTimeMillis())

    @Delete
    suspend fun delete(session: AgentSessionEntity)
}
