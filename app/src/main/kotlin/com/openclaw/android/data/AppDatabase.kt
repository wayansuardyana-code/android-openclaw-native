package com.openclaw.android.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import com.openclaw.android.data.dao.*
import com.openclaw.android.data.entity.*

@Database(
    entities = [
        ConnectorEntity::class,
        TaskEntity::class,
        MemoryEntity::class,
        AgentSessionEntity::class,
        ChatMessageEntity::class,
        ScheduledTaskEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun connectorDao(): ConnectorDao
    abstract fun taskDao(): TaskDao
    abstract fun memoryDao(): MemoryDao
    abstract fun agentSessionDao(): AgentSessionDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun scheduledTaskDao(): ScheduledTaskDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        // Migration from v2 to v3 (added ScheduledTaskEntity)
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS scheduled_tasks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    prompt TEXT NOT NULL,
                    intervalMinutes INTEGER NOT NULL,
                    nextRunAt INTEGER NOT NULL DEFAULT ${Long.MAX_VALUE},
                    lastRunAt INTEGER NOT NULL DEFAULT 0,
                    runCount INTEGER NOT NULL DEFAULT 0,
                    gateway TEXT NOT NULL DEFAULT 'chat'
                )""")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "openclaw.db"
                )
                .addMigrations(MIGRATION_2_3)
                // IMPORTANT: fallbackToDestructiveMigration DELETED chat history on every update!
                // Now using proper migrations. If schema changes, add a new Migration object.
                // Only use destructive as absolute last resort for unknown old versions.
                .fallbackToDestructiveMigrationFrom(1)  // Only destroy from v1 (very old)
                .build().also { INSTANCE = it }
            }
        }
    }
}
