package com.aurikqq.noswidgets.core
import java.time.*
import org.junit.Assert.*
import org.junit.Test
class AwayTimelineTest {
 private val date=LocalDate.of(2026,11,17)
 private fun at(h:Int)=date.atTime(h,0).toInstant(ZoneOffset.UTC)
 @Test fun sleepDoesNotOccupyTheLeftOfTheWakingScale() {
  val axis=AwayTimeline(date,ZoneOffset.UTC,SleepSchedule(LocalTime.MIDNIGHT,LocalTime.of(7,0)))
  assertEquals(0.0,axis.fraction(at(7)),0.000001)
  assertEquals(0.5,axis.fraction(date.atTime(15,30).toInstant(ZoneOffset.UTC)),0.000001)
 }
 @Test fun intervalsPreserveDurationAndMergeDuplicates() {
  val axis=AwayTimeline(date,ZoneOffset.UTC,SleepSchedule(LocalTime.MIDNIGHT,LocalTime.of(7,0)))
  val interval=Interval(at(7),at(9))
  val segments=axis.segments(listOf(interval,interval))
  assertEquals(1,segments.size)
  assertEquals(2.0/17,segments.sumOf{it.end-it.start},0.000001)
 }
 @Test fun tinySegmentsRetainTheirAreaWithoutBecomingSolidHairlines() {
  val bins=AwayTimeline.coverage(listOf(AwayTimeline.Segment(0.101,0.102)),100)
  assertEquals(0.1,bins.sum(),0.000001)
  assertTrue(bins.all{it<1.0})
 }
 @Test fun stockMergesOnlyWithinFivePhysicalPixels() {
  val source=listOf(AwayTimeline.Segment(0.1,0.2),AwayTimeline.Segment(0.21,0.3),AwayTimeline.Segment(0.32,0.4))
  val bars=AwayTimeline.stockBars(source,400.0)
  assertEquals(listOf(AwayTimeline.Segment(0.1,0.3),AwayTimeline.Segment(0.32,0.4)),bars)
 }
 @Test fun minimumVisibleWidthDoesNotChangeSourceDuration() {
  val source=listOf(AwayTimeline.Segment(0.101,0.102))
  val bar=AwayTimeline.stockBars(source,400.0).single()
  assertEquals(0.005,bar.end-bar.start,0.000001)
  assertEquals(0.001,source.single().end-source.single().start,0.000001)
 }
 @Test fun dstUsesActualElapsedWakingTime() {
  val zone=ZoneId.of("Europe/Berlin");val d=LocalDate.of(2026,10,25)
  val axis=AwayTimeline(d,zone,SleepSchedule(LocalTime.of(23,0),LocalTime.of(7,0)))
  assertEquals(0.5,axis.fraction(d.atTime(15,0).atZone(zone).toInstant()),0.000001)
 }
}
