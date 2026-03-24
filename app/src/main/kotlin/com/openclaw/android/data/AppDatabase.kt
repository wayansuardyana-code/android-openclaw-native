package com.openclaw.android.data

import android.content.Context
import androidx.room.*
import com.openclaw.android.data.dao.*
import com.openclaw.android.data.entity.*

@Database(
    entities = [
        ConnectorEntity::class,
        TaskEntity::class,
        MemoryEntity::class,
        AgentSessionEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun connectorDao(): ConnectorDao
    abstract fun taskDao(): TaskDao
    abstract fun memoryDao(): MemoryDao
    abstract fun agentSessionDao(): AgentSessionDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "openclaw.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}
