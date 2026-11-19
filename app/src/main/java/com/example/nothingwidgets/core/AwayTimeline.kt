package com.aurikqq.noswidgets.core

import java.time.*

/** Coordinates are elapsed waking time / full waking duration, not wall-clock hours. */
class AwayTimeline(date: LocalDate, zone: ZoneId, sleep: SleepSchedule) {
    data class Segment(val start: Double, val end: Double)
    private val window = AwakeWindow.on(date,zone,sleep)
    private val start = window.start
    private val end = window.end
    private val total = Duration.between(start,end).toMillis()
    fun fraction(time: Instant): Double = if(total == 0L) 0.0 else Duration.between(start,time.coerceIn(start,end)).toMillis().toDouble()/total
    fun segments(intervals: List<Interval>): List<Segment> {
        val projected = intervals.mapNotNull {
            val a = fraction(it.start); val b = fraction(it.end)
            if (b > a) Segment(a,b) else null
        }.sortedBy { it.start }
        val merged = mutableListOf<Segment>()
        projected.forEach { s ->
            val previous = merged.lastOrNull()
            if (previous != null && s.start <= previous.end) merged[merged.lastIndex] = Segment(previous.start, maxOf(s.end, previous.end))
            else merged.add(s)
        }
        return merged
    }
    companion object {
        /** Stock geometry: merge gaps up to five physical pixels, min visible width two. */
        fun stockBars(segments: List<Segment>, trackPixels: Double): List<Segment> {
            require(trackPixels > 0 && trackPixels.isFinite())
            val merged = mutableListOf<Segment>()
            segments.filter { it.start.isFinite() && it.end.isFinite() && it.end > it.start }
                .sortedBy { it.start }.forEach { item ->
                    val next=Segment(item.start.coerceIn(0.0,1.0),item.end.coerceIn(0.0,1.0))
                    val last=merged.lastOrNull()
                    if(last!=null && (next.start-last.end)*trackPixels <= 5.0)
                        merged[merged.lastIndex]=Segment(last.start,maxOf(last.end,next.end))
                    else merged.add(next)
                }
            return merged.map { Segment(it.start,minOf(1.0,maxOf(it.end,it.start+2.0/trackPixels))) }
        }

        /** Area-preserving occupancy of display columns. Never invents a minimum duration. */
        fun coverage(segments: List<Segment>, columns: Int): DoubleArray {
            require(columns > 0)
            return DoubleArray(columns) { i ->
                val left = i.toDouble()/columns; val right=(i+1.0)/columns
                segments.sumOf { maxOf(0.0, minOf(right,it.end)-maxOf(left,it.start)) }.times(columns).coerceIn(0.0,1.0)
            }
        }
    }
}
