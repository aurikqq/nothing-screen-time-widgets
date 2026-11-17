package com.example.nothingwidgets.core

import java.time.*

/** Dependency-free regression suite; also invoked by the JUnit adapter. */
object StatisticsChecks {
    private val base = Instant.parse("2026-11-15T00:00:00Z")
    private fun t(minutes: Long) = base.plusSeconds(minutes * 60)
    private fun event(minutes: Long, type: ScreenEventType) = ScreenEvent(t(minutes), type)
    private fun equal(expected: Any?, actual: Any?) = check(expected == actual) { "Expected $expected, got $actual" }

    @JvmStatic fun main(args: Array<String>) {
        realUnlockedIntervals()
        missingStateRemainsUnknown()
        shownKeyguardRemainsLocked()
        historyAndClipping()
        gapsInvalidateState()
        scheduleAcrossDst()
        scheduleInsideDstGap()
        calendarWeek()
        remainingAwakeTime()
        awayExcludesSleepAndFuture()
        println("10 core regression checks passed")
    }

    fun realUnlockedIntervals() {
        val usage = ScreenUsage.calculate(listOf(
            event(0, ScreenEventType.SCREEN_INTERACTIVE),
            event(2, ScreenEventType.KEYGUARD_HIDDEN),
            event(12, ScreenEventType.KEYGUARD_SHOWN),
            event(15, ScreenEventType.SCREEN_NON_INTERACTIVE)
        ), t(0), t(20))
        equal(Duration.ofMinutes(10), usage.knownUsage)
        equal(Duration.ofMinutes(2), usage.unknownDuration)
        equal(listOf(Interval(t(2), t(12))), usage.intervals)
        equal(false, usage.complete)
    }

    fun missingStateRemainsUnknown() {
        val usage = ScreenUsage.calculate(emptyList(), t(0), t(30))
        equal(Duration.ofMinutes(30), usage.unknownDuration)
        equal(null, usage.totalUsage)
        equal(listOf(Interval(t(0), t(30))), usage.unknownIntervals)
        val off = ScreenUsage.calculate(emptyList(), t(0), t(30), ScreenState(false, null))
        equal(Duration.ZERO, off.totalUsage)
    }

    fun shownKeyguardRemainsLocked() {
        val usage = ScreenUsage.calculate(listOf(
            event(0, ScreenEventType.KEYGUARD_HIDDEN),
            event(5, ScreenEventType.SCREEN_NON_INTERACTIVE),
            event(6, ScreenEventType.KEYGUARD_SHOWN),
            event(10, ScreenEventType.SCREEN_INTERACTIVE),
            event(12, ScreenEventType.KEYGUARD_HIDDEN)
        ), t(0), t(15), ScreenState(true, false))
        equal(Duration.ofMinutes(8), usage.knownUsage)
        equal(Duration.ZERO, usage.unknownDuration)
    }

    fun historyAndClipping() {
        val usage = ScreenUsage.calculate(listOf(
            event(15, ScreenEventType.SCREEN_NON_INTERACTIVE),
            event(-2, ScreenEventType.SCREEN_INTERACTIVE),
            event(-1, ScreenEventType.KEYGUARD_HIDDEN)
        ), t(0), t(10))
        equal(Duration.ofMinutes(10), usage.totalUsage)
    }

    fun gapsInvalidateState() {
        val usage = ScreenUsage.calculate(listOf(event(5, ScreenEventType.DATA_GAP)),
            t(0), t(10), ScreenState(true, true))
        equal(Duration.ofMinutes(5), usage.knownUsage)
        equal(Duration.ofMinutes(5), usage.unknownDuration)
    }

    fun scheduleAcrossDst() {
        val zone = ZoneId.of("Europe/Berlin")
        val schedule = SleepSchedule(LocalTime.of(23, 0), LocalTime.of(7, 0))
        val spring = schedule.intervalStarting(LocalDate.of(2026, 3, 28), zone)
        val autumn = schedule.intervalStarting(LocalDate.of(2026, 10, 24), zone)
        equal(Duration.ofHours(7), spring.duration)
        equal(Duration.ofHours(9), autumn.duration)
        val dayStart = LocalDate.of(2026, 3, 29).atStartOfDay(zone).toInstant()
        val dayEnd = LocalDate.of(2026, 3, 30).atStartOfDay(zone).toInstant()
        equal(Duration.ofHours(7), schedule.sleepWithin(dayStart, dayEnd, zone))
    }

    fun scheduleInsideDstGap() {
        val schedule = SleepSchedule(LocalTime.of(2, 30), LocalTime.of(3, 0))
        equal(Duration.ZERO, schedule.intervalStarting(LocalDate.of(2026, 3, 29), ZoneId.of("Europe/Berlin")).duration)
    }

    fun calendarWeek() {
        val week = WeeklyStatistics.calculate(emptyList(), LocalDate.of(2026, 10, 25),
            ZoneId.of("Europe/Berlin"), Instant.parse("2026-10-26T00:00:00Z"))
        equal(LocalDate.of(2026, 10, 19), week.first().date)
        equal(7, week.size)
        equal(Duration.ofHours(25), week.last().usage.unknownDuration)
        equal(null, week.last().usage.totalUsage)
    }

    fun awayExcludesSleepAndFuture() {
        val daily = DailyStatistics.calculate(emptyList(), LocalDate.of(2026, 11, 15),
            ZoneOffset.UTC, Instant.parse("2026-11-15T12:00:00Z"),
            SleepSchedule(LocalTime.of(23, 0), LocalTime.of(7, 0)), ScreenState(false, null))
        equal(Duration.ofHours(16), daily.awakeDuration)
        equal(Duration.ofHours(5), daily.awayDuration)
        equal(Duration.ZERO, daily.awakeUnknownDuration)
        equal(Duration.ZERO, daily.usage.totalUsage)
    }

    fun remainingAwakeTime() {
        val schedule = SleepSchedule(LocalTime.of(23, 0), LocalTime.of(7, 0))
        val zone = ZoneOffset.UTC
        equal(Duration.ofHours(11), schedule.remainingAwake(Instant.parse("2026-11-15T12:00:00Z"), zone))
        equal(Duration.ZERO, schedule.remainingAwake(Instant.parse("2026-11-15T23:30:00Z"), zone))
    }
}
