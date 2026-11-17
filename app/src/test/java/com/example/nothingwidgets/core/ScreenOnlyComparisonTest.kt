package com.example.nothingwidgets.core
import java.time.*
import org.junit.Assert.*
import org.junit.Test
class ScreenOnlyComparisonTest {
 @Test fun lockedInteractiveScreenIsNotOffScreenTime() {
  val start=Instant.parse("2026-11-17T00:00:00Z")
  val events=listOf(ScreenEvent(start,ScreenEventType.SCREEN_INTERACTIVE),ScreenEvent(start,ScreenEventType.KEYGUARD_SHOWN),
    ScreenEvent(start.plusSeconds(60),ScreenEventType.SCREEN_NON_INTERACTIVE))
  val actual=ScreenUsage.calculate(events,start,start.plusSeconds(120))
  val comparison=ScreenUsage.calculate(screenInteractiveEvents(events),start,start.plusSeconds(120))
  assertEquals(Duration.ZERO,actual.totalUsage)
  assertEquals(Duration.ofSeconds(60),comparison.totalUsage)
  assertEquals(Duration.ofSeconds(120),actual.inactiveIntervals.fold(Duration.ZERO){sum,i->sum.plus(i.duration)})
 }
 @Test fun restartBoundariesNeverCreateFictitiousUsage() {
  val start=Instant.parse("2026-11-17T00:00:00Z")
  val events=listOf(ScreenEvent(start,ScreenEventType.DEVICE_SHUTDOWN),ScreenEvent(start.plusSeconds(60),ScreenEventType.DEVICE_STARTUP))
  val comparison=ScreenUsage.calculate(screenInteractiveEvents(events),start,start.plusSeconds(120))
  assertNull(comparison.totalUsage)
 }
}
