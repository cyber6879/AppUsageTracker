package com.monitor.usagetracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "app_metadata",
    indices = [
        Index(value = ["is_system_app"]),
        Index(value = ["app_name"])
    ]
)
data class AppMetadataEntity(
    @PrimaryKey
    @ColumnInfo(name = "package_name")
    val packageName: String,

    @ColumnInfo(name = "app_name")
    val appName: String,

    @ColumnInfo(name = "is_system_app")
    val isSystemApp: Boolean,

    @ColumnInfo(name = "is_core_filtered")
    val isCoreFiltered: Boolean,

    @ColumnInfo(name = "last_updated_ms")
    val lastUpdatedMs: Long
)
