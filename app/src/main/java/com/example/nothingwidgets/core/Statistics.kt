package com.example.nothingwidgets.core

import java.time.*
import java.time.temporal.TemporalAdjusters

/** All ranges are half-open [start, end). */
data class Interval(val start: Instant, val end: Instant) {
    init { require(!end.isBefore(start)) }
    val duration: Duration get() = Duration.between(start, end)
    fun intersect(other: Interval): Interval? {
        val left = maxOf(start, other.start)
        val right = minOf(end, other.end)
        return if (left < right) Interval(left, right) else null
    }
}

enum class ScreenEventType {
    SCREEN_INTERACTIVE, SCREEN_NON_INTERACTIVE, KEYGUARD_SHOWN, KEYGUARD_HIDDEN,
    /** Reboot, missing retention, or collector interruption: no state may cross this boundary. */
    DATA_GAP, DEVICE_SHUTDOWN, DEVICE_STARTUP
}

data class ScreenEvent(val timestamp: Instant, val type: ScreenEventType)
data class ScreenState(val interactive: Boolean? = null, val unlocked: Boolean? = null) {
    companion object { val UNKNOWN = ScreenState() }
    internal val using: Boolean? get() = when {
        interactive == false || unlocked == false -> false
        interactive == true && unlocked == true -> true
        else -> null
    }
    internal fun apply(type: ScreenEventType): ScreenState = when (type) {
        ScreenEventType.SCREEN_INTERACTIVE -> copy(interactive = true)
        // The screen turning off does not itself change keyguard visibility.
        // Some devices do not emit KEYGUARD_HIDDEN again when no lock was shown.
        ScreenEventType.SCREEN_NON_INTERACTIVE -> copy(interactive = false)
        ScreenEventType.KEYGUARD_SHOWN -> copy(unlocked = false)
        ScreenEventType.KEYGUARD_HIDDEN -> copy(unlocked = true)
        ScreenEventType.DATA_GAP, ScreenEventType.DEVICE_SHUTDOWN, ScreenEventType.DEVICE_STARTUP -> UNKNOWN
    }
}

data class UsageResult(
    val intervals: List<Interval>,
    val inactiveIntervals: List<Interval>,
    val unknownIntervals: List<Interval>
) {
    val knownUsage: Duration get() = intervals.totalDuration()
    val unknownDuration: Duration get() = unknownIntervals.totalDuration()
    val complete: Boolean get() = unknownIntervals.isEmpty()
    /** Null is unknown; zero is only returned when the complete window is known. */
    val totalUsage: Duration? get() = knownUsage.takeIf { complete }
}

object ScreenUsage {
    /**
     * initialState describes the state before the earliest supplied event (or at from if none).
     * Callers should include retained history before from; sorting is stable for equal timestamps.
     * Missing history must use UNKNOWN, never infer an initial screen-off state.
     */
    fun calculate(events: List<ScreenEvent>, from: Instant, until: Instant,
                  initialState: ScreenState = ScreenState.UNKNOWN): UsageResult {
        require(!until.isBefore(from))
        var state = initialState
        var cursor = from
        val active = mutableListOf<Interval>()
        val inactive = mutableListOf<Interval>()
        val unknown = mutableListOf<Interval>()
        fun record(end: Instant) {
            if (end <= cursor) return
            val destination = when (state.using) { true -> active; false -> inactive; null -> unknown }
            if (destination.lastOrNull()?.end == cursor) {
                destination[destination.lastIndex] = Interval(destination.last().start, end)
            } else destination.add(Interval(cursor, end))
            cursor = end
        }
        for (event in events.sortedBy { it.timestamp }) {
            if (event.timestamp >= until) break
            if (event.timestamp > from) record(event.timestamp)
            state = state.apply(event.type)
        }
        record(until)
        return UsageResult(active, inactive, unknown)
    }
}

/** User-configured sleep schedule, not measured sleep. Equal endpoints mean no scheduled sleep. */
data class SleepSchedule(val start: LocalTime, val end: LocalTime) {
    fun intervalStarting(date: LocalDate, zone: ZoneId): Interval {
        val endDate = if (end < start) date.plusDays(1) else date
        // java.time shifts nonexistent local times forward and picks the earlier offset at overlap.
        val left = date.atTime(start).atZone(zone).toInstant()
        val right = endDate.atTime(end).atZone(zone).toInstant()
        // A short schedule wholly inside a skipped hour can collapse after gap resolution.
        return Interval(left, maxOf(left, right))
    }

    fun sleepIntervals(from: Instant, until: Instant, zone: ZoneId): List<Interval> {
        require(!until.isBefore(from))
        if (from == until || start == end) return emptyList()
        val window = Interval(from, until)
        var date = from.atZone(zone).toLocalDate().minusDays(1)
        val last = until.atZone(zone).toLocalDate()
        val result = mutableListOf<Interval>()
        while (!date.isAfter(last)) {
            intervalStarting(date, zone).intersect(window)?.let { result.add(it) }
            date = date.plusDays(1)
        }
        return result
    }
    fun sleepWithin(from: Instant, until: Instant, zone: ZoneId): Duration =
        sleepIntervals(from, until, zone).totalDuration()

    fun remainingAwake(now: Instant, zone: ZoneId): Duration {
        val endOfDay = now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
        return Duration.between(now, endOfDay).minus(sleepWithin(now, endOfDay, zone))
    }
}

data class DailyResult(
    val date: LocalDate,
    val usage: UsageResult,
    /** Full day's available waking time, including time that has not elapsed yet. */
    val awakeDuration: Duration,
    /** Confirmed inactive time during elapsed waking time. */
    val awayDuration: Duration,
    val awakeUnknownDuration: Duration,
    val observedDuration: Duration,
    val awakeKnownDuration: Duration
)

object DailyStatistics {
    fun calculate(events: List<ScreenEvent>, date: LocalDate, zone: ZoneId, now: Instant,
                  sleep: SleepSchedule? = null, initialState: ScreenState = ScreenState.UNKNOWN): DailyResult {
        val start = date.atStartOfDay(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
        val until = maxOf(start, minOf(end, now))
        val usage = ScreenUsage.calculate(events, start, until, initialState)
        val sleepIntervals = sleep?.sleepIntervals(start, end, zone).orEmpty()
        fun awakePart(intervals: List<Interval>): Duration = intervals.totalDuration().minus(
            intervals.flatMap { value -> sleepIntervals.mapNotNull { it.intersect(value) } }.totalDuration())
        return DailyResult(date, usage,
            Duration.between(start, end).minus(sleepIntervals.totalDuration()),
            awakePart(usage.inactiveIntervals), awakePart(usage.unknownIntervals), Duration.between(start, until),
            awakePart(usage.inactiveIntervals).plus(awakePart(usage.intervals)))
    }
}

object WeeklyStatistics {
    /** Calendar week Monday–Sunday; future days contain no observations. */
    fun calculate(events: List<ScreenEvent>, containing: LocalDate, zone: ZoneId, now: Instant,
                  sleep: SleepSchedule? = null, initialState: ScreenState = ScreenState.UNKNOWN): List<DailyResult> {
        val monday = containing.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return (0L..6L).map { DailyStatistics.calculate(events, monday.plusDays(it), zone, now, sleep, initialState) }
    }
}

private fun List<Interval>.totalDuration(): Duration = fold(Duration.ZERO) { sum, interval -> sum.plus(interval.duration) }

/** Diagnostic comparison only: screen interactivity, irrespective of keyguard visibility. */
fun screenInteractiveEvents(events: List<ScreenEvent>): List<ScreenEvent> = events.flatMap { event ->
    when (event.type) {
        ScreenEventType.SCREEN_INTERACTIVE -> listOf(event, ScreenEvent(event.timestamp, ScreenEventType.KEYGUARD_HIDDEN))
        ScreenEventType.KEYGUARD_HIDDEN, ScreenEventType.KEYGUARD_SHOWN -> emptyList()
        else -> listOf(event)
    }
}
