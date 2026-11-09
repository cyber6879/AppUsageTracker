package com.monitor.usagetracker.data.local.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.monitor.usagetracker.data.local.entity.AppMetadataEntity
import com.monitor.usagetracker.data.local.entity.AppUsageSessionEntity
import com.monitor.usagetracker.data.local.entity.DailyAppSummaryEntity
import com.monitor.usagetracker.data.local.entity.SyncCursorEntity
import kotlinx.coroutines.flow.Flow

data class DailyAppSummaryWithMetaTuple(
    @ColumnInfo(name = "date_key") val dateKey: String,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "total_duration_ms") val totalDurationMs: Long,
    @ColumnInfo(name = "launch_count") val launchCount: Int,
    @ColumnInfo(name = "first_launch_ms") val firstLaunchMs: Long,
    @ColumnInfo(name = "last_launch_ms") val lastLaunchMs: Long,
    @ColumnInfo(name = "last_used_ms") val lastUsedMs: Long,
    @ColumnInfo(name = "app_name") val appName: String?,
    @ColumnInfo(name = "is_system_app") val isSystemApp: Boolean?,
    @ColumnInfo(name = "is_core_filtered") val isCoreFiltered: Boolean?
)

@Dao
interface UsageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMetadataList(items: List<AppMetadataEntity>)

    @Query("SELECT * FROM app_metadata WHERE package_name = :packageName LIMIT 1")
    suspend fun getAppMetadata(packageName: String): AppMetadataEntity?

    @Query("SELECT * FROM app_metadata")
    suspend fun getAllMetadata(): List<AppMetadataEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSessions(sessions: List<AppUsageSessionEntity>)

    @Query("DELETE FROM app_usage_sessions WHERE is_ongoing = 1 AND date_key = :dateKey")
    suspend fun deleteOngoingSessionsForDate(dateKey: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDailySummaries(summaries: List<DailyAppSummaryEntity>)

    @Query(
        "SELECT date_key, package_name, SUM(duration_ms) AS total_duration_ms, " +
        "COUNT(session_id) AS launch_count, MIN(start_time_ms) AS first_launch_ms, " +
        "MAX(start_time_ms) AS last_launch_ms, MAX(end_time_ms) AS last_used_ms " +
        "FROM app_usage_sessions WHERE date_key IN (:dateKeys) GROUP BY date_key, package_name"
    )
    suspend fun aggregateSummariesForDates(dateKeys: List<String>): List<DailyAppSummaryEntity>

    @Transaction
    suspend fun commitSyncedSessionsAndRebuildSummaries(
        sessions: List<AppUsageSessionEntity>,
        affectedDateKeys: List<String>,
        cursor: SyncCursorEntity
    ) {
        if (affectedDateKeys.isNotEmpty()) {
            affectedDateKeys.forEach { dateKey ->
                deleteOngoingSessionsForDate(dateKey)
            }
        }
        if (sessions.isNotEmpty()) {
            upsertSessions(sessions)
        }
        if (affectedDateKeys.isNotEmpty()) {
            val recalculated = aggregateSummariesForDates(affectedDateKeys)
            upsertDailySummaries(recalculated)
        }
        upsertSyncCursor(cursor)
    }

    @Query(
        "SELECT s.date_key, s.package_name, s.total_duration_ms, s.launch_count, " +
        "s.first_launch_ms, s.last_launch_ms, s.last_used_ms, " +
        "m.app_name, m.is_system_app, m.is_core_filtered " +
        "FROM daily_app_summaries s LEFT JOIN app_metadata m ON s.package_name = m.package_name " +
        "WHERE s.date_key = :dateKey " +
        "AND (:includeSystemApps = 1 OR COALESCE(m.is_system_app, 0) = 0) " +
        "AND COALESCE(m.is_core_filtered, 0) = 0"
    )
    fun observeDailySummariesWithMeta(
        dateKey: String,
        includeSystemApps: Boolean
    ): Flow<List<DailyAppSummaryWithMetaTuple>>

    @Query(
        "SELECT s.* FROM app_usage_sessions s " +
        "LEFT JOIN app_metadata m ON s.package_name = m.package_name " +
        "WHERE s.date_key = :dateKey " +
        "AND (:includeSystemApps = 1 OR COALESCE(m.is_system_app, 0) = 0) " +
        "AND COALESCE(m.is_core_filtered, 0) = 0 " +
        "ORDER BY s.start_time_ms ASC"
    )
    fun observeSessionsForDate(
        dateKey: String,
        includeSystemApps: Boolean
    ): Flow<List<AppUsageSessionEntity>>

    @Query(
        "SELECT * FROM app_usage_sessions " +
        "WHERE date_key = :dateKey AND package_name = :packageName " +
        "ORDER BY start_time_ms DESC"
    )
    fun observeAppSessionsForDate(
        dateKey: String,
        packageName: String
    ): Flow<List<AppUsageSessionEntity>>

    @Query(
        "SELECT * FROM daily_app_summaries " +
        "WHERE package_name = :packageName AND date_key BETWEEN :startDateKey AND :endDateKey " +
        "ORDER BY date_key ASC"
    )
    fun observeAppTrendSummaries(
        packageName: String,
        startDateKey: String,
        endDateKey: String
    ): Flow<List<DailyAppSummaryEntity>>

    @Query(
        "SELECT * FROM app_usage_sessions " +
        "WHERE date_key BETWEEN :startDateKey AND :endDateKey " +
        "ORDER BY start_time_ms ASC"
    )
    suspend fun getSessionsInDateRange(
        startDateKey: String,
        endDateKey: String
    ): List<AppUsageSessionEntity>

    @Query("SELECT * FROM sync_cursor WHERE cursor_id = 1 LIMIT 1")
    suspend fun getSyncCursor(): SyncCursorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSyncCursor(cursor: SyncCursorEntity)
}
