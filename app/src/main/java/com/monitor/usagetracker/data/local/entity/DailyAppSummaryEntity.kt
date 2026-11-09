package com.monitor.usagetracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "daily_app_summaries",
    primaryKeys = ["date_key", "package_name"],
    indices = [
        Index(
            name = "index_summary_date_duration",
            value = ["date_key", "total_duration_ms"]
        ),
        Index(
            name = "index_summary_pkg_date",
            value = ["package_name", "date_key"]
        )
    ]
)
data class DailyAppSummaryEntity(
    @ColumnInfo(name = "date_key")
    val dateKey: String,

    @ColumnInfo(name = "package_name")
    val packageName: String,

    @ColumnInfo(name = "total_duration_ms")
    val totalDurationMs: Long,

    @ColumnInfo(name = "launch_count")
    val launchCount: Int,

    @ColumnInfo(name = "first_launch_ms")
    val firstLaunchMs: Long,

    @ColumnInfo(name = "last_launch_ms")
    val lastLaunchMs: Long,

    @ColumnInfo(name = "last_used_ms")
    val lastUsedMs: Long
)
