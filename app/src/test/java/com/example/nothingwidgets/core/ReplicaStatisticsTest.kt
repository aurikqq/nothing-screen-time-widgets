package com.aurikqq.noswidgets.core
import java.time.*
import org.junit.Assert.*
import org.junit.Test
class ReplicaStatisticsTest {
 private val day=LocalDate.of(2026,11,17)
 private val zone=ZoneOffset.UTC
 private fun t(h:Int,m:Int=0)=day.atTime(h,m).toInstant(zone)
 @Test fun awayRejectsShortBreaksAndClipsAtWake(){
  val e=listOf(ScreenEvent(t(0),ScreenEventType.SCREEN_NON_INTERACTIVE),ScreenEvent(t(8),ScreenEventType.SCREEN_INTERACTIVE),
   ScreenEvent(t(9),ScreenEventType.SCREEN_NON_INTERACTIVE),ScreenEvent(t(9,5),ScreenEventType.SCREEN_INTERACTIVE))
  val result=ReplicaAway.calculate(e,t(10),zone,SleepSchedule(LocalTime.of(1,0),LocalTime.of(7,0)))
  assertEquals(3600000L,result.totalMillis)
  assertEquals(listOf(Interval(t(7),t(8))),result.intervals)
 }
 @Test fun poweredOffAndKeyguardIntervalsAreUnioned(){
  val e=listOf(ScreenEvent(t(4),ScreenEventType.DEVICE_SHUTDOWN),ScreenEvent(t(8),ScreenEventType.DEVICE_STARTUP),
   ScreenEvent(t(7,30),ScreenEventType.KEYGUARD_SHOWN),ScreenEvent(t(9),ScreenEventType.KEYGUARD_HIDDEN))
  val result=ReplicaAway.calculate(e,t(10),zone,SleepSchedule(LocalTime.of(1,0),LocalTime.of(7,0)))
  assertEquals(3600000L,result.totalMillis)
  assertEquals(7200000L,result.unknownMillis)
 }
 @Test fun lockedScreenWakeupsDoNotBreakAwayTime() {
  val e=listOf(ScreenEvent(t(7),ScreenEventType.KEYGUARD_SHOWN), ScreenEvent(t(7,2),ScreenEventType.SCREEN_INTERACTIVE),
   ScreenEvent(t(7,3),ScreenEventType.SCREEN_NON_INTERACTIVE), ScreenEvent(t(7,5),ScreenEventType.SCREEN_INTERACTIVE),
   ScreenEvent(t(7,6),ScreenEventType.SCREEN_NON_INTERACTIVE), ScreenEvent(t(7,10),ScreenEventType.KEYGUARD_HIDDEN),
   ScreenEvent(t(7,10),ScreenEventType.SCREEN_INTERACTIVE))
  val result=ReplicaAway.calculate(e,t(7,12),zone,SleepSchedule(LocalTime.of(23,0),LocalTime.of(7,0)))
  assertEquals(10*60000L,result.totalMillis)
  assertEquals(0L,result.unknownMillis)
 }
 @Test fun activityUsesStoppedAndDeduplicatesOverlappingWindows(){
  val e=listOf(ActivityEvent(t(9),ActivityEventType.RESUMED,"a","A"),ActivityEvent(t(9,10),ActivityEventType.PAUSED,"a","A"),
   ActivityEvent(t(9,15),ActivityEventType.STOPPED,"a","A"),ActivityEvent(t(9,5),ActivityEventType.RESUMED,"b","B"),
   ActivityEvent(t(9,20),ActivityEventType.PAUSED,"b","B"),ActivityEvent(t(9,12),ActivityEventType.KEYGUARD_SHOWN),ActivityEvent(t(9,14),ActivityEventType.KEYGUARD_HIDDEN))
  assertEquals(18*60000L,IntervalMath.millis(ReplicaScreenTime.intervals(e,t(0),t(10))))
 }
 @Test fun overnightHistoryClipsAtMidnight(){
  val e=listOf(ActivityEvent(t(0).minusSeconds(600),ActivityEventType.RESUMED,"a","A"),ActivityEvent(t(0,10),ActivityEventType.STOPPED,"a","A"))
  assertEquals(600000L,IntervalMath.millis(ReplicaScreenTime.intervals(e,t(0),t(1))))
 }
}
