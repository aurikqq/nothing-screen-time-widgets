package com.aurikqq.noswidgets.core

import org.junit.Test

class StatisticsTest {
    @Test fun countsOnlyInteractiveUnlockedIntervals() = StatisticsChecks.realUnlockedIntervals()
    @Test fun missingHistoryRemainsUnknown() = StatisticsChecks.missingStateRemainsUnknown()
    @Test fun shownKeyguardPreventsUsageUntilUnlock() = StatisticsChecks.shownKeyguardRemainsLocked()
    @Test fun replaysHistoryAndClipsWindow() = StatisticsChecks.historyAndClipping()
    @Test fun dataGapInvalidatesPreviousState() = StatisticsChecks.gapsInvalidateState()
    @Test fun sleepUsesElapsedTimeAcrossDst() = StatisticsChecks.scheduleAcrossDst()
    @Test fun sleepInsideSkippedHourCannotBeNegative() = StatisticsChecks.scheduleInsideDstGap()
    @Test fun calendarWeekContainsDstDay() = StatisticsChecks.calendarWeek()
    @Test fun remainingAwakeExcludesScheduledSleep() = StatisticsChecks.remainingAwakeTime()
    @Test fun awayExcludesSleepAndFuture() = StatisticsChecks.awayExcludesSleepAndFuture()
}
