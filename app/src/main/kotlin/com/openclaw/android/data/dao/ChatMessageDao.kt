package com.openclaw.android.data.dao

import androidx.room.*
import com.openclaw.android.data.entity.ChatMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {
    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getBySession(sessionId: String = "default"): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(sessionId: String = "default", limit: Int = 200): List<ChatMessageEntity>

    @Insert
    suspend fun insert(message: ChatMessageEntity): Long

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun clearSession(sessionId: String = "default")

    @Query("SELECT COUNT(*) FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun countBySession(sessionId: String = "default"): Int

    // For semantic search — find messages containing text
    @Query("SELECT * FROM chat_messages WHERE content LIKE '%' || :query || '%' ORDER BY timestamp DESC LIMIT :limit")
    suspend fun search(query: String, limit: Int = 20): List<ChatMessageEntity>

    @Query("DELETE FROM chat_messages")
    suspend fun deleteAll()
}
