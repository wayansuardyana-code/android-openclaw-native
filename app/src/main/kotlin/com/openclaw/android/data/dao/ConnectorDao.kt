package com.openclaw.android.data.dao

import androidx.room.*
import com.openclaw.android.data.entity.ConnectorEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConnectorDao {
    @Query("SELECT * FROM connectors ORDER BY category, name")
    fun getAll(): Flow<List<ConnectorEntity>>

    @Query("SELECT * FROM connectors WHERE category = :category")
    fun getByCategory(category: String): Flow<List<ConnectorEntity>>

    @Query("SELECT * FROM connectors WHERE id = :id")
    suspend fun getById(id: String): ConnectorEntity?

    @Query("SELECT * FROM connectors WHERE enabled = 1")
    fun getEnabled(): Flow<List<ConnectorEntity>>

    @Upsert
    suspend fun upsert(connector: ConnectorEntity)

    @Update
    suspend fun update(connector: ConnectorEntity)

    @Delete
    suspend fun delete(connector: ConnectorEntity)

    @Query("UPDATE connectors SET status = :status, errorMessage = :error WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, error: String? = null)

    @Query("UPDATE connectors SET configJson = :config WHERE id = :id")
    suspend fun updateConfig(id: String, config: String)
}
