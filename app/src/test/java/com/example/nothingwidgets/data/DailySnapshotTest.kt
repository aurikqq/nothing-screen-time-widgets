package com.aurikqq.noswidgets.data

import com.aurikqq.noswidgets.core.*
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class DailySnapshotTest {
    private val date = LocalDate.of(2026, 1, 1)
    private val start = date.atStartOfDay(ZoneOffset.UTC).toInstant()
    @Test fun unknownIntervalsDoNotBecomeZero() {
        val value = DailyStatistics.calculate(emptyList(), date, ZoneOffset.UTC, start.plusSeconds(3600))
        val snapshot = snapshotFrom(value, start.plusSeconds(3600))
        assertNull(snapshot.awayMillis)
        assertNull(snapshot.screenMillis)
    }
    @Test fun knownZeroIsRetained() {
        val events = listOf(ScreenEvent(start, ScreenEventType.SCREEN_INTERACTIVE), ScreenEvent(start, ScreenEventType.KEYGUARD_HIDDEN))
        val snapshot = snapshotFrom(DailyStatistics.calculate(events, date, ZoneOffset.UTC, start.plusSeconds(3600)), start.plusSeconds(3600))
        assertEquals(0L, snapshot.awayMillis)
        assertEquals(3600000L, snapshot.screenMillis)
    }
    @Test fun partialObservationsAreReportedAsLowerBounds() {
        val events = listOf(
            ScreenEvent(start.plusSeconds(600), ScreenEventType.SCREEN_INTERACTIVE),
            ScreenEvent(start.plusSeconds(600), ScreenEventType.KEYGUARD_HIDDEN),
            ScreenEvent(start.plusSeconds(1800), ScreenEventType.KEYGUARD_SHOWN))
        val snapshot = snapshotFrom(DailyStatistics.calculate(events, date, ZoneOffset.UTC, start.plusSeconds(3600)), start.plusSeconds(3600))
        assertEquals(1200000L, snapshot.screenMillis)
        assertEquals(1800000L, snapshot.awayMillis)
        assertTrue(snapshot.screenPartial)
        assertTrue(snapshot.awayPartial)
    }
    @Test fun beforeWakeAwayIsKnownZeroEvenIfPhoneStateUnknown() {
        val sleep = SleepSchedule(LocalTime.of(23, 0), LocalTime.of(7, 0))
        val snapshot = snapshotFrom(DailyStatistics.calculate(emptyList(), date, ZoneOffset.UTC, start.plusSeconds(3600), sleep), start.plusSeconds(3600))
        assertEquals(0L, snapshot.awayMillis)
        assertFalse(snapshot.awayPartial)
        assertNull(snapshot.screenMillis)
    }
    @Test fun fullyUnknownPeriodRemainsUnavailable() {
        val value = DailyStatistics.calculate(emptyList(), date, ZoneOffset.UTC, start.plusSeconds(3600))
        val snapshot = snapshotFrom(value, start.plusSeconds(3600))
        assertNull(snapshot.screenMillis)
        assertNull(snapshot.awayMillis)
    }

}
