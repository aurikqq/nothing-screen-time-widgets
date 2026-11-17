package com.example.nothingwidgets.weekly
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test
class WeeklyInsightTest {
 @Test fun shownOnceOnMondayAndOfferedAgainNextWeek() {
  val monday=LocalDate.of(2026,11,16)
  assertTrue(WeeklyInsightStore.shouldOffer(monday,""))
  assertFalse(WeeklyInsightStore.shouldOffer(monday,monday.toString()))
  assertFalse(WeeklyInsightStore.shouldOffer(monday.plusDays(1),""))
  assertTrue(WeeklyInsightStore.shouldOffer(monday.plusWeeks(1),monday.toString()))
 }
}
