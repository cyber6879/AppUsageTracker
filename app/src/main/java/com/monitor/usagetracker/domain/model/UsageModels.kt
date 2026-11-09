package com.monitor.usagetracker.domain.model

enum class AppSortOrder(val displayName: String) {
    TOTAL_DURATION("按总时长排序"),
    LAUNCH_COUNT("按启动次数排序"),
    LAST_LAUNCH("按最近启动排序")
}

data class AppSummaryItem(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean,
    val totalDurationMs: Long,
    val usagePercentage: Float,
    val launchCount: Int,
    val firstLaunchMs: Long,
    val lastLaunchMs: Long,
    val lastUsedMs: Long
)

data class HourlyUsageBucket(
    val hourOfDay: Int,
    val durationMs: Long
)

data class SessionTimelineItem(
    val sessionId: String,
    val packageName: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val durationMs: Long,
    val isOngoing: Boolean,
    val isEstimatedEnd: Boolean
)

data class DailyTrendPoint(
    val dateKey: String,
    val shortLabel: String,
    val totalDurationMs: Long,
    val launchCount: Int
)

enum class ExportFormat {
    CSV,
    JSON
}
