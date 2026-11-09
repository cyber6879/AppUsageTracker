package com.monitor.usagetracker.data.collector

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.monitor.usagetracker.data.local.entity.AppUsageSessionEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class UsageEventParser(
    private val context: Context,
    private val metadataResolver: PackageMetadataResolver
) {

    companion object {
        const val MIN_VALID_SESSION_DURATION_MS = 1_000L
        const val INTERNAL_TRANSITION_BRIDGE_MS = 600L
        const val MAX_UNCLOSED_SESSION_CAP_MS = 2 * 60 * 60 * 1000L
        const val FOREGROUND_PREEMPT_GRACE_MS = 3_000L

        val DATE_KEY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        private const val EVENT_ACTIVITY_RESUMED = UsageEvents.Event.ACTIVITY_RESUMED
        private const val EVENT_ACTIVITY_PAUSED = UsageEvents.Event.ACTIVITY_PAUSED
        private const val EVENT_ACTIVITY_STOPPED = 23
        private const val EVENT_DEVICE_SHUTDOWN = 26
        private const val EVENT_KEYGUARD_SHOWN = 17
        private const val EVENT_SCREEN_NON_INTERACTIVE = 16
    }

    private data class ActiveAppState(
        val packageName: String,
        var sessionStartMs: Long,
        var lastActiveSignalMs: Long,
        val activeComponents: MutableSet<String> = mutableSetOf(),
        var pendingBackgroundMs: Long? = null
    )

    private data class RawSession(
        val packageName: String,
        val startTimeMs: Long,
        val endTimeMs: Long,
        val isOngoing: Boolean,
        val isEstimatedEnd: Boolean
    )

    fun queryAndParseSessions(
        beginTimeMs: Long,
        endTimeMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<AppUsageSessionEntity> {
        if (endTimeMs <= beginTimeMs) return emptyList()
        val usageStatsManager =
            context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return emptyList()

        val usageEvents: UsageEvents = try {
            usageStatsManager.queryEvents(beginTimeMs, endTimeMs) ?: return emptyList()
        } catch (e: SecurityException) {
            return emptyList()
        } catch (e: Exception) {
            return emptyList()
        }

        val activeStates = mutableMapOf<String, ActiveAppState>()
        val rawSessions = mutableListOf<RawSession>()
        val currentEvent = UsageEvents.Event()

        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(currentEvent)
            val pkg = currentEvent.packageName ?: continue
            val timestamp = currentEvent.timeStamp
            if (timestamp < beginTimeMs || timestamp > endTimeMs) continue

            val eventType = currentEvent.eventType
            val className = currentEvent.className ?: DEFAULT_COMPONENT_NAME

            flushExpiredPendingTransitions(activeStates, rawSessions, timestamp)

            if (eventType == EVENT_DEVICE_SHUTDOWN ||
                eventType == EVENT_KEYGUARD_SHOWN ||
                eventType == EVENT_SCREEN_NON_INTERACTIVE
            ) {
                forceCloseAllActiveSessions(
                    activeStates = activeStates,
                    rawSessions = rawSessions,
                    closeTimestamp = timestamp,
                    isEstimated = false
                )
                continue
            }

            when (eventType) {
                EVENT_ACTIVITY_RESUMED -> {
                    preemptStaleForegroundApps(
                        newForegroundPkg = pkg,
                        currentTimestamp = timestamp,
                        activeStates = activeStates,
                        rawSessions = rawSessions
                    )

                    val existingState = activeStates[pkg]
                    if (existingState == null) {
                        activeStates[pkg] = ActiveAppState(
                            packageName = pkg,
                            sessionStartMs = timestamp,
                            lastActiveSignalMs = timestamp,
                            activeComponents = mutableSetOf(className),
                            pendingBackgroundMs = null
                        )
                    } else {
                        val pendingBg = existingState.pendingBackgroundMs
                        if (pendingBg != null) {
                            if (timestamp - pendingBg <= INTERNAL_TRANSITION_BRIDGE_MS) {
                                existingState.pendingBackgroundMs = null
                                existingState.activeComponents.add(className)
                                existingState.lastActiveSignalMs = timestamp
                            } else {
                                emitRawSessionIfValid(
                                    rawSessions = rawSessions,
                                    packageName = pkg,
                                    startMs = existingState.sessionStartMs,
                                    endMs = pendingBg,
                                    isOngoing = false,
                                    isEstimatedEnd = false
                                )
                                activeStates[pkg] = ActiveAppState(
                                    packageName = pkg,
                                    sessionStartMs = timestamp,
                                    lastActiveSignalMs = timestamp,
                                    activeComponents = mutableSetOf(className),
                                    pendingBackgroundMs = null
                                )
                            }
                        } else {
                            val elapsedSinceLastSignal = timestamp - existingState.lastActiveSignalMs
                            if (elapsedSinceLastSignal > MAX_UNCLOSED_SESSION_CAP_MS) {
                                val estimatedEnd = existingState.lastActiveSignalMs +
                                    minOf(elapsedSinceLastSignal / 2, 5 * 60 * 1000L)
                                emitRawSessionIfValid(
                                    rawSessions = rawSessions,
                                    packageName = pkg,
                                    startMs = existingState.sessionStartMs,
                                    endMs = estimatedEnd,
                                    isOngoing = false,
                                    isEstimatedEnd = true
                                )
                                activeStates[pkg] = ActiveAppState(
                                    packageName = pkg,
                                    sessionStartMs = timestamp,
                                    lastActiveSignalMs = timestamp,
                                    activeComponents = mutableSetOf(className),
                                    pendingBackgroundMs = null
                                )
                            } else {
                                existingState.activeComponents.add(className)
                                existingState.lastActiveSignalMs = timestamp
                            }
                        }
                    }
                }

                EVENT_ACTIVITY_PAUSED, EVENT_ACTIVITY_STOPPED -> {
                    val existingState = activeStates[pkg] ?: continue
                    existingState.activeComponents.remove(className)
                    existingState.lastActiveSignalMs = timestamp
                    if (existingState.activeComponents.isEmpty() && existingState.pendingBackgroundMs == null) {
                        existingState.pendingBackgroundMs = timestamp
                    }
                }
            }
        }

        for ((pkg, state) in activeStates) {
            val pendingBg = state.pendingBackgroundMs
            if (pendingBg != null) {
                emitRawSessionIfValid(
                    rawSessions = rawSessions,
                    packageName = pkg,
                    startMs = state.sessionStartMs,
                    endMs = pendingBg,
                    isOngoing = false,
                    isEstimatedEnd = false
                )
            } else {
                val idleDuration = endTimeMs - state.lastActiveSignalMs
                if (idleDuration <= MAX_UNCLOSED_SESSION_CAP_MS) {
                    emitRawSessionIfValid(
                        rawSessions = rawSessions,
                        packageName = pkg,
                        startMs = state.sessionStartMs,
                        endMs = endTimeMs,
                        isOngoing = true,
                        isEstimatedEnd = false
                    )
                } else {
                    val cappedEnd = state.lastActiveSignalMs + 60_000L
                    emitRawSessionIfValid(
                        rawSessions = rawSessions,
                        packageName = pkg,
                        startMs = state.sessionStartMs,
                        endMs = minOf(cappedEnd, endTimeMs),
                        isOngoing = false,
                        isEstimatedEnd = true
                    )
                }
            }
        }

        val resultEntities = mutableListOf<AppUsageSessionEntity>()
        for (raw in rawSessions) {
            resultEntities.addAll(splitSessionAcrossMidnights(raw, zoneId))
        }
        return resultEntities
    }

    private fun flushExpiredPendingTransitions(
        activeStates: MutableMap<String, ActiveAppState>,
        rawSessions: MutableList<RawSession>,
        currentTimestamp: Long
    ) {
        val iterator = activeStates.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val state = entry.value
            val pendingBg = state.pendingBackgroundMs ?: continue
            if (currentTimestamp - pendingBg > INTERNAL_TRANSITION_BRIDGE_MS) {
                emitRawSessionIfValid(
                    rawSessions = rawSessions,
                    packageName = state.packageName,
                    startMs = state.sessionStartMs,
                    endMs = pendingBg,
                    isOngoing = false,
                    isEstimatedEnd = false
                )
                iterator.remove()
            }
        }
    }

    private fun preemptStaleForegroundApps(
        newForegroundPkg: String,
        currentTimestamp: Long,
        activeStates: MutableMap<String, ActiveAppState>,
        rawSessions: MutableList<RawSession>
    ) {
        val iterator = activeStates.entries.iterator()
        while (iterator.hasNext()) {
            val (pkg, state) = iterator.next()
            if (pkg == newForegroundPkg) continue
            val pendingBg = state.pendingBackgroundMs
            if (pendingBg != null) {
                emitRawSessionIfValid(
                    rawSessions = rawSessions,
                    packageName = pkg,
                    startMs = state.sessionStartMs,
                    endMs = pendingBg,
                    isOngoing = false,
                    isEstimatedEnd = false
                )
                iterator.remove()
            } else if (currentTimestamp - state.lastActiveSignalMs > FOREGROUND_PREEMPT_GRACE_MS) {
                emitRawSessionIfValid(
                    rawSessions = rawSessions,
                    packageName = pkg,
                    startMs = state.sessionStartMs,
                    endMs = currentTimestamp,
                    isOngoing = false,
                    isEstimatedEnd = true
                )
                iterator.remove()
            }
        }
    }

    private fun forceCloseAllActiveSessions(
        activeStates: MutableMap<String, ActiveAppState>,
        rawSessions: MutableList<RawSession>,
        closeTimestamp: Long,
        isEstimated: Boolean
    ) {
        for ((pkg, state) in activeStates) {
            val effectiveEnd = state.pendingBackgroundMs ?: closeTimestamp
            emitRawSessionIfValid(
                rawSessions = rawSessions,
                packageName = pkg,
                startMs = state.sessionStartMs,
                endMs = effectiveEnd,
                isOngoing = false,
                isEstimatedEnd = isEstimated
            )
        }
        activeStates.clear()
    }

    private fun emitRawSessionIfValid(
        rawSessions: MutableList<RawSession>,
        packageName: String,
        startMs: Long,
        endMs: Long,
        isOngoing: Boolean,
        isEstimatedEnd: Boolean
    ) {
        val duration = endMs - startMs
        if (duration < MIN_VALID_SESSION_DURATION_MS) {
            return
        }
        rawSessions.add(
            RawSession(
                packageName = packageName,
                startTimeMs = startMs,
                endTimeMs = endMs,
                isOngoing = isOngoing,
                isEstimatedEnd = isEstimatedEnd
            )
        )
    }

    private fun splitSessionAcrossMidnights(
        raw: RawSession,
        zoneId: ZoneId
    ): List<AppUsageSessionEntity> {
        val segments = mutableListOf<AppUsageSessionEntity>()
        var cursorMs = raw.startTimeMs
        val finalEndMs = raw.endTimeMs

        while (cursorMs < finalEndMs) {
            val cursorZoned = Instant.ofEpochMilli(cursorMs).atZone(zoneId)
            val currentLocalDate: LocalDate = cursorZoned.toLocalDate()
            val nextMidnightMs: Long = currentLocalDate
                .plusDays(1)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()

            val segmentEndMs = minOf(finalEndMs, nextMidnightMs)
            val segmentDurationMs = segmentEndMs - cursorMs
            val isLastSegment = (segmentEndMs == finalEndMs)

            if (segmentDurationMs > 0L) {
                val dateKey = currentLocalDate.format(DATE_KEY_FORMATTER)
                segments.add(
                    AppUsageSessionEntity(
                        sessionId = "${raw.packageName}_${cursorMs}",
                        packageName = raw.packageName,
                        dateKey = dateKey,
                        startTimeMs = cursorMs,
                        endTimeMs = segmentEndMs,
                        durationMs = segmentDurationMs,
                        isOngoing = raw.isOngoing && isLastSegment,
                        isEstimatedEnd = raw.isEstimatedEnd && isLastSegment
                    )
                )
            }
            cursorMs = segmentEndMs
        }
        return segments
    }
}

private const val DEFAULT_COMPONENT_NAME = "DefaultActivity"
