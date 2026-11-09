package com.monitor.usagetracker.data.repository

import android.content.Context
import android.os.SystemClock
import com.monitor.usagetracker.data.collector.PackageMetadataResolver
import com.monitor.usagetracker.data.collector.UsageEventParser
import com.monitor.usagetracker.data.collector.UsagePermissionHelper
import com.monitor.usagetracker.data.export.DataExportManager
import com.monitor.usagetracker.data.local.dao.UsageDao
import com.monitor.usagetracker.data.local.entity.AppUsageSessionEntity
import com.monitor.usagetracker.data.local.entity.SyncCursorEntity
import com.monitor.usagetracker.domain.model.AppSummaryItem
import com.monitor.usagetracker.domain.model.DailyTrendPoint
import com.monitor.usagetracker.domain.model.ExportFormat
import com.monitor.usagetracker.domain.model.HourlyUsageBucket
import com.monitor.usagetracker.domain.model.SessionTimelineItem
import com.monitor.usagetracker.domain.repository.UsageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

class UsageRepositoryImpl(
    private val context: Context,
    private val usageDao: UsageDao,
    private val metadataResolver: PackageMetadataResolver,
    private val eventParser: UsageEventParser,
    private val exportManager: DataExportManager
) : UsageRepository {

    private val syncMutex = Mutex()
    private val shortDateFormatter = DateTimeFormatter.ofPattern("MM/dd")

    companion object {
        private const val INITIAL_BACKFILL_DAYS = 7L
        private const val INCREMENTAL_OVERLAP_MS = 3 * 60 * 60 * 1000L
        private const val CLOCK_SKEW_TOLERANCE_MS = 5 * 60 * 1000L
    }

    override fun hasUsagePermission(): Boolean {
        return UsagePermissionHelper.hasUsageStatsPermission(context)
    }

    override suspend fun syncLatestUsageEvents(forceFullHistory: Boolean) = withContext(Dispatchers.IO) {
        if (!hasUsagePermission()) return@withContext

        syncMutex.withLock {
            val nowWallMs = System.currentTimeMillis()
            val nowElapsedMs = SystemClock.elapsedRealtime()
            val zoneId = ZoneId.systemDefault()
            val todayStartMs = LocalDate.now(zoneId).atStartOfDay(zoneId).toInstant().toEpochMilli()

            val cursor = usageDao.getSyncCursor()
            val queryStartMs = if (forceFullHistory || cursor == null) {
                LocalDate.now(zoneId)
                    .minusDays(INITIAL_BACKFILL_DAYS)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            } else {
                val elapsedDelta = nowElapsedMs - cursor.lastElapsedRealtimeMs
                val wallDelta = nowWallMs - cursor.lastWallClockMs
                val isDeviceRebooted = elapsedDelta < 0
                val isClockSkewed = abs(wallDelta - elapsedDelta) > CLOCK_SKEW_TOLERANCE_MS

                if (isDeviceRebooted || isClockSkewed || nowWallMs < cursor.lastSyncedEventTimeMs) {
                    LocalDate.now(zoneId)
                        .minusDays(1)
                        .atStartOfDay(zoneId)
                        .toInstant()
                        .toEpochMilli()
                } else {
                    maxOf(
                        0L,
                        minOf(todayStartMs, cursor.lastSyncedEventTimeMs - INCREMENTAL_OVERLAP_MS)
                    )
                }
            }

            val parsedSessions = eventParser.queryAndParseSessions(
                beginTimeMs = queryStartMs,
                endTimeMs = nowWallMs,
                zoneId = zoneId
            )

            val existingMetaMap = usageDao.getAllMetadata().associateBy { it.packageName }
            val distinctPackages = parsedSessions.map { it.packageName }.distinct()
            val metadataToUpsert = distinctPackages.mapNotNull { pkg ->
                val cached = existingMetaMap[pkg]
                if (cached == null || (nowWallMs - cached.lastUpdatedMs) > 24 * 3600_000L) {
                    metadataResolver.resolveAppMetadata(pkg)
                } else {
                    null
                }
            }
            if (metadataToUpsert.isNotEmpty()) {
                usageDao.upsertMetadataList(metadataToUpsert)
            }

            val affectedDates = (parsedSessions.map { it.dateKey } +
                LocalDate.now(zoneId).format(UsageEventParser.DATE_KEY_FORMATTER)).distinct()

            val newCursor = SyncCursorEntity(
                cursorId = 1,
                lastSyncedEventTimeMs = nowWallMs,
                lastElapsedRealtimeMs = nowElapsedMs,
                lastWallClockMs = nowWallMs
            )

            usageDao.commitSyncedSessionsAndRebuildSummaries(
                sessions = parsedSessions,
                affectedDateKeys = affectedDates,
                cursor = newCursor
            )
        }
    }

    override fun observeDailyAppSummaries(
        date: LocalDate,
        includeSystemApps: Boolean
    ): Flow<List<AppSummaryItem>> {
        val dateKey = date.format(UsageEventParser.DATE_KEY_FORMATTER)
        return usageDao.observeDailySummariesWithMeta(dateKey, includeSystemApps)
            .map { tuples ->
                val totalDayDuration = tuples.sumOf { it.totalDurationMs }.coerceAtLeast(1L)
                tuples.map { tuple ->
                    AppSummaryItem(
                        packageName = tuple.packageName,
                        appName = tuple.appName ?: tuple.packageName,
                        isSystemApp = tuple.isSystemApp ?: false,
                        totalDurationMs = tuple.totalDurationMs,
                        usagePercentage = (tuple.totalDurationMs.toFloat() / totalDayDuration.toFloat())
                            .coerceIn(0f, 1f),
                        launchCount = tuple.launchCount,
                        firstLaunchMs = tuple.firstLaunchMs,
                        lastLaunchMs = tuple.lastLaunchMs,
                        lastUsedMs = tuple.lastUsedMs
                    )
                }
            }
    }

    override fun observeHourlyDistribution(
        date: LocalDate,
        includeSystemApps: Boolean
    ): Flow<List<HourlyUsageBucket>> {
        val dateKey = date.format(UsageEventParser.DATE_KEY_FORMATTER)
        val zoneId = ZoneId.systemDefault()
        val dayStartMs = date.atStartOfDay(zoneId).toInstant().toEpochMilli()

        return usageDao.observeSessionsForDate(dateKey, includeSystemApps)
            .map { sessions ->
                val buckets = LongArray(24)
                for (session in sessions) {
                    addSessionToHourlyBuckets(
                        session = session,
                        dayStartMs = dayStartMs,
                        buckets = buckets
                    )
                }
                buckets.mapIndexed { hour, duration ->
                    HourlyUsageBucket(
                        hourOfDay = hour,
                        durationMs = duration.coerceAtMost(3600_000L)
                    )
                }
            }
    }

    private fun addSessionToHourlyBuckets(
        session: AppUsageSessionEntity,
        dayStartMs: Long,
        buckets: LongArray
    ) {
        for (hour in 0 until 24) {
            val hourStartMs = dayStartMs + hour * 3600_000L
            val hourEndMs = hourStartMs + 3600_000L
            val overlapStart = maxOf(session.startTimeMs, hourStartMs)
            val overlapEnd = minOf(session.endTimeMs, hourEndMs)
            if (overlapEnd > overlapStart) {
                buckets[hour] += (overlapEnd - overlapStart)
            }
        }
    }

    override fun observeAppTimelineSessions(
        date: LocalDate,
        packageName: String
    ): Flow<List<SessionTimelineItem>> {
        val dateKey = date.format(UsageEventParser.DATE_KEY_FORMATTER)
        return usageDao.observeAppSessionsForDate(dateKey, packageName).map { list ->
            list.map { entity ->
                SessionTimelineItem(
                    sessionId = entity.sessionId,
                    packageName = entity.packageName,
                    startTimeMs = entity.startTimeMs,
                    endTimeMs = entity.endTimeMs,
                    durationMs = entity.durationMs,
                    isOngoing = entity.isOngoing,
                    isEstimatedEnd = entity.isEstimatedEnd
                )
            }
        }
    }

    override fun observeAppLast7DaysTrend(
        anchorDate: LocalDate,
        packageName: String
    ): Flow<List<DailyTrendPoint>> {
        val startDate = anchorDate.minusDays(6)
        val startDateKey = startDate.format(UsageEventParser.DATE_KEY_FORMATTER)
        val endDateKey = anchorDate.format(UsageEventParser.DATE_KEY_FORMATTER)

        return usageDao.observeAppTrendSummaries(packageName, startDateKey, endDateKey)
            .map { dbList ->
                val summaryMap = dbList.associateBy { it.dateKey }
                (0L..6L).map { offset ->
                    val d = startDate.plusDays(offset)
                    val key = d.format(UsageEventParser.DATE_KEY_FORMATTER)
                    val matched = summaryMap[key]
                    DailyTrendPoint(
                        dateKey = key,
                        shortLabel = d.format(shortDateFormatter),
                        totalDurationMs = matched?.totalDurationMs ?: 0L,
                        launchCount = matched?.launchCount ?: 0
                    )
                }
            }
    }

    override suspend fun getAppDisplayName(packageName: String): String = withContext(Dispatchers.IO) {
        val cached = usageDao.getAppMetadata(packageName)
        if (cached != null) return@withContext cached.appName
        val resolved = metadataResolver.resolveAppMetadata(packageName)
        usageDao.upsertMetadataList(listOf(resolved))
        resolved.appName
    }

    override suspend fun exportUsageData(
        startDate: LocalDate,
        endDate: LocalDate,
        format: ExportFormat
    ): Result<File> = withContext(Dispatchers.IO) {
        val startKey = startDate.format(UsageEventParser.DATE_KEY_FORMATTER)
        val endKey = endDate.format(UsageEventParser.DATE_KEY_FORMATTER)
        val sessions = usageDao.getSessionsInDateRange(startKey, endKey)
        val metadataMap = usageDao.getAllMetadata().associateBy { it.packageName }
        exportManager.exportSessionsToFile(
            sessions = sessions,
            metadataMap = metadataMap,
            startDateKey = startKey,
            endDateKey = endKey,
            format = format
        )
    }
}
