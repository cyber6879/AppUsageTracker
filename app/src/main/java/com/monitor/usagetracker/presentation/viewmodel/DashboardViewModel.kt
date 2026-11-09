package com.monitor.usagetracker.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.monitor.usagetracker.domain.model.AppSortOrder
import com.monitor.usagetracker.domain.model.AppSummaryItem
import com.monitor.usagetracker.domain.model.ExportFormat
import com.monitor.usagetracker.domain.model.HourlyUsageBucket
import com.monitor.usagetracker.domain.repository.UsageRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

data class DashboardFilterState(
    val selectedDate: LocalDate = LocalDate.now(),
    val includeSystemApps: Boolean = false,
    val searchQuery: String = "",
    val sortOrder: AppSortOrder = AppSortOrder.TOTAL_DURATION
)

data class DashboardUiState(
    val hasPermission: Boolean = false,
    val isSyncing: Boolean = false,
    val selectedDate: LocalDate = LocalDate.now(),
    val includeSystemApps: Boolean = false,
    val searchQuery: String = "",
    val sortOrder: AppSortOrder = AppSortOrder.TOTAL_DURATION,
    val totalScreenTimeMs: Long = 0L,
    val totalLaunchCount: Int = 0,
    val top5Apps: List<AppSummaryItem> = emptyList(),
    val hourlyBuckets: List<HourlyUsageBucket> = List(24) { HourlyUsageBucket(it, 0L) },
    val appRankingList: List<AppSummaryItem> = emptyList(),
    val exportedFileResult: File? = null,
    val errorMessage: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel(
    private val repository: UsageRepository
) : ViewModel() {

    private val filterState = MutableStateFlow(DashboardFilterState())
    private val hasPermissionState = MutableStateFlow(repository.hasUsagePermission())
    private val isSyncingState = MutableStateFlow(false)
    private val exportResultState = MutableStateFlow<File?>(null)
    private val errorState = MutableStateFlow<String?>(null)

    private val rawDailySummariesFlow = filterState
        .flatMapLatest { filter ->
            repository.observeDailyAppSummaries(filter.selectedDate, filter.includeSystemApps)
        }

    private val hourlyDistributionFlow = filterState
        .flatMapLatest { filter ->
            repository.observeHourlyDistribution(filter.selectedDate, filter.includeSystemApps)
        }

    val uiState: StateFlow<DashboardUiState> = combine(
        filterState,
        hasPermissionState,
        isSyncingState,
        rawDailySummariesFlow,
        hourlyDistributionFlow
    ) { filter, hasPerm, syncing, summaries, hourly ->
        val totalScreenMs = summaries.sumOf { it.totalDurationMs }
        val totalLaunches = summaries.sumOf { it.launchCount }
        val top5 = summaries.sortedByDescending { it.totalDurationMs }.take(5)

        val filteredList = summaries.filter { item ->
            if (filter.searchQuery.isBlank()) true
            else item.appName.contains(filter.searchQuery, ignoreCase = true) ||
                item.packageName.contains(filter.searchQuery, ignoreCase = true)
        }

        val sortedList = when (filter.sortOrder) {
            AppSortOrder.TOTAL_DURATION -> filteredList.sortedByDescending { it.totalDurationMs }
            AppSortOrder.LAUNCH_COUNT -> filteredList.sortedByDescending { it.launchCount }
            AppSortOrder.LAST_LAUNCH -> filteredList.sortedByDescending { it.lastLaunchMs }
        }

        DashboardUiState(
            hasPermission = hasPerm,
            isSyncing = syncing,
            selectedDate = filter.selectedDate,
            includeSystemApps = filter.includeSystemApps,
            searchQuery = filter.searchQuery,
            sortOrder = filter.sortOrder,
            totalScreenTimeMs = totalScreenMs,
            totalLaunchCount = totalLaunches,
            top5Apps = top5,
            hourlyBuckets = hourly,
            appRankingList = sortedList
        )
    }.combine(exportResultState) { base, exportedFile ->
        base.copy(exportedFileResult = exportedFile)
    }.combine(errorState) { base, errorMsg ->
        base.copy(errorMessage = errorMsg)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DashboardUiState(hasPermission = repository.hasUsagePermission())
    )

    fun checkPermissionAndRefresh() {
        val granted = repository.hasUsagePermission()
        hasPermissionState.value = granted
        if (granted) {
            triggerSync(forceFull = false)
        }
    }

    fun triggerSync(forceFull: Boolean = false) {
        if (!repository.hasUsagePermission()) {
            hasPermissionState.value = false
            return
        }
        viewModelScope.launch {
            isSyncingState.value = true
            try {
                repository.syncLatestUsageEvents(forceFullHistory = forceFull)
            } catch (e: Exception) {
                errorState.value = "同步统计数据失败: ${e.localizedMessage}"
            } finally {
                isSyncingState.value = false
            }
        }
    }

    fun selectDate(date: LocalDate) {
        filterState.update { it.copy(selectedDate = date) }
        if (date == LocalDate.now()) {
            triggerSync(forceFull = false)
        }
    }

    fun updateSearchQuery(query: String) {
        filterState.update { it.copy(searchQuery = query) }
    }

    fun updateSortOrder(sortOrder: AppSortOrder) {
        filterState.update { it.copy(sortOrder = sortOrder) }
    }

    fun toggleIncludeSystemApps(include: Boolean) {
        filterState.update { it.copy(includeSystemApps = include) }
    }

    fun exportData(format: ExportFormat, daysBack: Long = 7L) {
        viewModelScope.launch {
            val endDate = filterState.value.selectedDate
            val startDate = endDate.minusDays(daysBack.coerceAtLeast(0L))
            val result = repository.exportUsageData(startDate, endDate, format)
            result.onSuccess { file ->
                exportResultState.value = file
            }.onFailure { err ->
                errorState.value = "导出文件失败: ${err.localizedMessage}"
            }
        }
    }

    fun clearTransientNotifications() {
        exportResultState.value = null
        errorState.value = null
    }

    class Factory(private val repository: UsageRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DashboardViewModel(repository) as T
        }
    }
}
