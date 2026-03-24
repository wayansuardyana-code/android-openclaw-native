package com.openclaw.android.data.dao

import androidx.room.*
import com.openclaw.android.data.entity.MemoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY lastAccessedAt DESC")
    fun getAll(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE type = :type ORDER BY importance DESC")
    fun getByType(type: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories ORDER BY importance DESC LIMIT :limit")
    suspend fun getTopMemories(limit: Int = 20): List<MemoryEntity>

    @Insert
    suspend fun insert(memory: MemoryEntity): Long

    @Update
    suspend fun update(memory: MemoryEntity)

    @Delete
    suspend fun delete(memory: MemoryEntity)

    @Query("UPDATE memories SET accessCount = accessCount + 1, lastAccessedAt = :now WHERE id = :id")
    suspend fun recordAccess(id: Long, now: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM memories")
    fun count(): Flow<Int>

    @Query("DELETE FROM memories WHERE importance < 0.1 AND accessCount = 0 AND createdAt < :cutoff")
    suspend fun pruneOldUnused(cutoff: Long)
}
