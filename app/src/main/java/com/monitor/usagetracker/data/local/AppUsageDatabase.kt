package com.monitor.usagetracker.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.monitor.usagetracker.data.local.dao.UsageDao
import com.monitor.usagetracker.data.local.entity.AppMetadataEntity
import com.monitor.usagetracker.data.local.entity.AppUsageSessionEntity
import com.monitor.usagetracker.data.local.entity.DailyAppSummaryEntity
import com.monitor.usagetracker.data.local.entity.SyncCursorEntity

@Database(
    entities = [
        AppMetadataEntity::class,
        AppUsageSessionEntity::class,
        DailyAppSummaryEntity::class,
        SyncCursorEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class AppUsageDatabase : RoomDatabase() {

    abstract fun usageDao(): UsageDao

    companion object {
        @Volatile
        private var INSTANCE: AppUsageDatabase? = null

        fun getInstance(context: Context): AppUsageDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppUsageDatabase::class.java,
                    "app_usage_tracker.db"
                )
                    .enableMultiInstanceInvalidation()
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
