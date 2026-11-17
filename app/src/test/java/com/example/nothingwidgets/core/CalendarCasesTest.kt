package com.example.nothingwidgets.core

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class CalendarCasesTest {
    private val day = LocalDate.of(2026, 11, 15)
    private val zone = ZoneOffset.UTC
    private val start = day.atStartOfDay(zone).toInstant()
    private val sleep = SleepSchedule(LocalTime.of(23, 0), LocalTime.of(7, 0))
    @Test fun inactiveThroughoutWakingHours() {
        val result = DailyStatistics.calculate(emptyList(), day, zone, start.plusSeconds(86400), sleep, ScreenState(false, null))
        assertEquals(Duration.ofHours(16), result.awayDuration)
        assertEquals(Duration.ZERO, result.awakeUnknownDuration)
    }
    @Test fun activeThroughoutWakingHours() {
        val result = DailyStatistics.calculate(emptyList(), day, zone, start.plusSeconds(86400), sleep, ScreenState(true, true))
        assertEquals(Duration.ZERO, result.awayDuration)
        assertEquals(Duration.ofHours(24), result.usage.totalUsage)
    }
    @Test fun activeIntervalAcrossMidnightIsSplitByCalendarDay() {
        val events = listOf(ScreenEvent(start.plusSeconds(23*3600), ScreenEventType.SCREEN_INTERACTIVE),
            ScreenEvent(start.plusSeconds(23*3600), ScreenEventType.KEYGUARD_HIDDEN),
            ScreenEvent(start.plusSeconds(25*3600), ScreenEventType.SCREEN_NON_INTERACTIVE))
        val first = DailyStatistics.calculate(events, day, zone, start.plusSeconds(26*3600), null, ScreenState(false, null))
        val second = DailyStatistics.calculate(events, day.plusDays(1), zone, start.plusSeconds(26*3600), null, ScreenState(false, null))
        assertEquals(Duration.ofHours(1), first.usage.totalUsage)
        assertEquals(Duration.ofHours(1), second.usage.totalUsage)
    }
    @Test fun duplicateEventsDoNotDoubleCount() {
        val events = listOf(ScreenEvent(start, ScreenEventType.SCREEN_INTERACTIVE), ScreenEvent(start, ScreenEventType.SCREEN_INTERACTIVE),
            ScreenEvent(start, ScreenEventType.KEYGUARD_HIDDEN), ScreenEvent(start, ScreenEventType.KEYGUARD_HIDDEN))
        assertEquals(Duration.ofHours(1), ScreenUsage.calculate(events, start, start.plusSeconds(3600)).totalUsage)
    }
    @Test fun localDayUsesActualZoneBoundary() {
        val minsk = ZoneId.of("Europe/Minsk")
        val midnight = day.atStartOfDay(minsk).toInstant()
        val result = DailyStatistics.calculate(emptyList(), day, minsk, midnight.plusSeconds(3600), null, ScreenState(false, null))
        assertEquals(Duration.ofHours(1), result.observedDuration)
        assertEquals(Duration.ofHours(1), result.awayDuration)
    }
    @Test fun hiddenKeyguardRemainsHiddenAcrossScreenCycleWithoutLockEvent() {
        val events = listOf(ScreenEvent(start, ScreenEventType.SCREEN_NON_INTERACTIVE),
            ScreenEvent(start.plusSeconds(10), ScreenEventType.SCREEN_INTERACTIVE))
        val result = ScreenUsage.calculate(events, start, start.plusSeconds(70), ScreenState(true, true))
        assertEquals(Duration.ofSeconds(60), result.totalUsage)
    }
    @Test fun lockWhileScreenIsOffPreventsCountingUntilUnlock() {
        val events = listOf(ScreenEvent(start, ScreenEventType.SCREEN_NON_INTERACTIVE),
            ScreenEvent(start.plusSeconds(5), ScreenEventType.KEYGUARD_SHOWN),
            ScreenEvent(start.plusSeconds(10), ScreenEventType.SCREEN_INTERACTIVE),
            ScreenEvent(start.plusSeconds(30), ScreenEventType.KEYGUARD_HIDDEN))
        val result = ScreenUsage.calculate(events, start, start.plusSeconds(70), ScreenState(true, true))
        assertEquals(Duration.ofSeconds(40), result.totalUsage)
    }
    @Test fun futureDaysAreUnobservedRatherThanMeasuredZero() {
        val week = WeeklyStatistics.calculate(emptyList(), LocalDate.of(2026,11,11), zone,
            Instant.parse("2026-11-11T12:00:00Z"), null, ScreenState(false, null))
        assertEquals(LocalDate.of(2026,11,9), week.first().date)
        assertEquals(Duration.ZERO, week.last().observedDuration)
    }
}
