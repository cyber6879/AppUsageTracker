package com.monitor.usagetracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "app_usage_sessions",
    indices = [
        Index(
            name = "index_sessions_date_pkg_start",
            value = ["date_key", "package_name", "start_time_ms"]
        ),
        Index(
            name = "index_sessions_start_time",
            value = ["start_time_ms"]
        )
    ]
)
data class AppUsageSessionEntity(
    @PrimaryKey
    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "package_name")
    val packageName: String,

    @ColumnInfo(name = "date_key")
    val dateKey: String,

    @ColumnInfo(name = "start_time_ms")
    val startTimeMs: Long,

    @ColumnInfo(name = "end_time_ms")
    val endTimeMs: Long,

    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,

    @ColumnInfo(name = "is_ongoing")
    val isOngoing: Boolean = false,

    @ColumnInfo(name = "is_estimated_end")
    val isEstimatedEnd: Boolean = false
)
