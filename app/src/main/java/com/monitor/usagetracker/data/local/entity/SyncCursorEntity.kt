package com.monitor.usagetracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_cursor")
data class SyncCursorEntity(
    @PrimaryKey
    @ColumnInfo(name = "cursor_id")
    val cursorId: Int = 1,

    @ColumnInfo(name = "last_synced_event_time_ms")
    val lastSyncedEventTimeMs: Long,

    @ColumnInfo(name = "last_elapsed_realtime_ms")
    val lastElapsedRealtimeMs: Long,

    @ColumnInfo(name = "last_wall_clock_ms")
    val lastWallClockMs: Long
)
