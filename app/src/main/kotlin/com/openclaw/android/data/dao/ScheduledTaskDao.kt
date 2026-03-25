package com.openclaw.android.data.dao

import androidx.room.*
import com.openclaw.android.data.entity.ScheduledTaskEntity

@Dao
interface ScheduledTaskDao {
    @Query("SELECT * FROM scheduled_tasks WHERE isEnabled = 1 AND nextRunAt <= :now ORDER BY nextRunAt ASC")
    suspend fun getDueTasks(now: Long = System.currentTimeMillis()): List<ScheduledTaskEntity>

    @Query("SELECT * FROM scheduled_tasks ORDER BY createdAt DESC")
    suspend fun getAll(): List<ScheduledTaskEntity>

    @Insert
    suspend fun insert(task: ScheduledTaskEntity): Long

    @Update
    suspend fun update(task: ScheduledTaskEntity)

    @Delete
    suspend fun delete(task: ScheduledTaskEntity)

    @Query("DELETE FROM scheduled_tasks WHERE id = :id")
    suspend fun deleteById(id: Long)
}
