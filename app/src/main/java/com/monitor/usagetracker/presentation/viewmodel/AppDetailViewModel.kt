package com.monitor.usagetracker.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.monitor.usagetracker.domain.model.DailyTrendPoint
import com.monitor.usagetracker.domain.model.SessionTimelineItem
import com.monitor.usagetracker.domain.repository.UsageRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class AppDetailUiState(
    val packageName: String,
    val appName: String = "",
    val selectedDate: LocalDate = LocalDate.now(),
    val totalDayDurationMs: Long = 0L,
    val dayLaunchCount: Int = 0,
    val trend7Days: List<DailyTrendPoint> = emptyList(),
    val timelineSessions: List<SessionTimelineItem> = emptyList()
)

@OptIn(ExperimentalCoroutinesApi::class)
class AppDetailViewModel(
    private val packageName: String,
    initialDate: LocalDate,
    private val repository: UsageRepository
) : ViewModel() {

    private val selectedDateFlow = MutableStateFlow(initialDate)
    private val appNameFlow = MutableStateFlow(packageName)

    init {
        viewModelScope.launch {
            appNameFlow.value = repository.getAppDisplayName(packageName)
        }
    }

    private val sessionsFlow = selectedDateFlow.flatMapLatest { date ->
        repository.observeAppTimelineSessions(date, packageName)
    }

    private val trendFlow = selectedDateFlow.flatMapLatest { date ->
        repository.observeAppLast7DaysTrend(date, packageName)
    }

    val uiState: StateFlow<AppDetailUiState> = combine(
        selectedDateFlow,
        appNameFlow,
        sessionsFlow,
        trendFlow
    ) { date, appName, sessions, trend ->
        AppDetailUiState(
            packageName = packageName,
            appName = appName,
            selectedDate = date,
            totalDayDurationMs = sessions.sumOf { it.durationMs },
            dayLaunchCount = sessions.size,
            trend7Days = trend,
            timelineSessions = sessions
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppDetailUiState(packageName = packageName, selectedDate = initialDate)
    )

    fun selectDate(newDate: LocalDate) {
        selectedDateFlow.value = newDate
    }

    class Factory(
        private val packageName: String,
        private val initialDate: LocalDate,
        private val repository: UsageRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AppDetailViewModel(packageName, initialDate, repository) as T
        }
    }
}
