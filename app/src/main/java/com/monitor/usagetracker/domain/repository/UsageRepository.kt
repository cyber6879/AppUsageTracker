package com.monitor.usagetracker.domain.repository

import com.monitor.usagetracker.domain.model.AppSummaryItem
import com.monitor.usagetracker.domain.model.DailyTrendPoint
import com.monitor.usagetracker.domain.model.ExportFormat
import com.monitor.usagetracker.domain.model.HourlyUsageBucket
import com.monitor.usagetracker.domain.model.SessionTimelineItem
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.time.LocalDate

interface UsageRepository {
    fun hasUsagePermission(): Boolean
    suspend fun syncLatestUsageEvents(forceFullHistory: Boolean = false)
    fun observeDailyAppSummaries(date: LocalDate, includeSystemApps: Boolean): Flow<List<AppSummaryItem>>
    fun observeHourlyDistribution(date: LocalDate, includeSystemApps: Boolean): Flow<List<HourlyUsageBucket>>
    fun observeAppTimelineSessions(date: LocalDate, packageName: String): Flow<List<SessionTimelineItem>>
    fun observeAppLast7DaysTrend(anchorDate: LocalDate, packageName: String): Flow<List<DailyTrendPoint>>
    suspend fun getAppDisplayName(packageName: String): String
    suspend fun exportUsageData(startDate: LocalDate, endDate: LocalDate, format: ExportFormat): Result<File>
}
