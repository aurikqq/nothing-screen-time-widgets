package com.aurikqq.noswidgets.core

import java.time.*

/** Independent interval implementation of the observed stock widget rules. */
object IntervalMath {
 fun union(input: List<Interval>): List<Interval> {
  val out=mutableListOf<Interval>()
  input.filter{it.end>it.start}.sortedBy{it.start}.forEach { next ->
   val last=out.lastOrNull()
   if(last!=null && next.start<=last.end) out[out.lastIndex]=Interval(last.start,maxOf(last.end,next.end)) else out.add(next)
  }
  return out
 }
 fun subtract(input: List<Interval>, excluded: List<Interval>): List<Interval> {
  var out=union(input)
  union(excluded).forEach { cut -> out=out.flatMap { value ->
   val overlap=value.intersect(cut)
   if(overlap==null) listOf(value) else buildList {
    if(value.start<overlap.start)add(Interval(value.start,overlap.start))
    if(overlap.end<value.end)add(Interval(overlap.end,value.end))
   }
  } }
  return out
 }
 fun millis(input: List<Interval>)=union(input).sumOf{it.duration.toMillis()}
}

data class AwakeWindow(val date: LocalDate,val start: Instant,val end: Instant,val cycleEnd: Instant) {
 companion object {
  fun on(date: LocalDate, zone: ZoneId, sleep: SleepSchedule): AwakeWindow {
   val start=date.atTime(sleep.end).atZone(zone).toInstant()
   val endDate=if(sleep.start<=sleep.end)date.plusDays(1) else date
   return AwakeWindow(date,start,endDate.atTime(sleep.start).atZone(zone).toInstant(),date.plusDays(1).atTime(sleep.end).atZone(zone).toInstant())
  }
  fun at(now: Instant,zone: ZoneId,sleep: SleepSchedule): AwakeWindow {
   val date=now.atZone(zone).toLocalDate()
   return on(if(now<date.atTime(sleep.end).atZone(zone).toInstant())date.minusDays(1) else date,zone,sleep)
  }
 }
}

data class AwayResult(val window: AwakeWindow, val intervals: List<Interval>, val totalMillis: Long, val unknownMillis: Long)
object ReplicaAway {
 fun calculate(events: List<ScreenEvent>, now: Instant, zone: ZoneId, sleep: SleepSchedule): AwayResult {
  val window = AwakeWindow.at(now, zone, sleep)
  val until = maxOf(window.start, minOf(now, window.end))
  val usage = ScreenUsage.calculate(events, window.start, until)
  val offStart = mutableListOf<Interval>()
  var shutdown: Instant? = null
  events.sortedBy { it.timestamp }.filter { it.timestamp < until }.forEach { event ->
   when (event.type) {
    ScreenEventType.DEVICE_SHUTDOWN -> shutdown = event.timestamp
    ScreenEventType.DEVICE_STARTUP -> { shutdown?.let { start ->
     Interval(start, event.timestamp).intersect(Interval(window.start, until))?.let(offStart::add)
    }; shutdown = null }
    ScreenEventType.DATA_GAP -> shutdown = null
    else -> Unit
   }
  }
  shutdown?.let { start -> Interval(start, maxOf(start, until)).intersect(Interval(window.start, until))?.let(offStart::add) }
  val away = IntervalMath.union(usage.inactiveIntervals + offStart)
  val unknown = IntervalMath.subtract(usage.unknownIntervals, offStart)
  return AwayResult(window, away, IntervalMath.millis(away), IntervalMath.millis(unknown))
 }
}

enum class ActivityEventType{RESUMED,PAUSED,STOPPED,KEYGUARD_SHOWN,KEYGUARD_HIDDEN}
data class ActivityEvent(val timestamp:Instant,val type:ActivityEventType,val packageName:String="",val className:String="")
object ReplicaScreenTime {
 private data class Session(val start:Instant,var paused:Instant?=null,var stopped:Instant?=null)
 fun intervals(events:List<ActivityEvent>,from:Instant,until:Instant):List<Interval>{
  require(until>=from)
  val sessions=mutableMapOf<String,MutableList<Session>>()
  var lastKey:String?=null;var locked:Instant?=null
  val locks=mutableListOf<Interval>()
  val window=Interval(from,until)
  events.sortedBy{it.timestamp}.filter{it.timestamp<until}.forEach { event ->
   val t=event.timestamp
   val key=event.packageName+"/"+event.className
   when(event.type){
    ActivityEventType.RESUMED->{sessions.getOrPut(key){mutableListOf()}.add(Session(t));lastKey=key}
    ActivityEventType.PAUSED->sessions[key]?.lastOrNull{it.paused==null}?.paused=t
    ActivityEventType.STOPPED->sessions[key]?.lastOrNull{it.stopped==null}?.stopped=t
    ActivityEventType.KEYGUARD_SHOWN->locked=t
    ActivityEventType.KEYGUARD_HIDDEN->{if(locked!=null)Interval(locked!!,maxOf(locked!!,t)).intersect(window)?.let{locks.add(it)};locked=null}
   }
  }
  sessions[lastKey]?.lastOrNull()?.let{if(it.paused==null)it.paused=until}
  locked?.let{Interval(it,maxOf(it,until)).intersect(window)?.let{range->locks.add(range)}}
  val ranges=sessions.values.flatten().mapNotNull { s -> val end=s.stopped?:s.paused
   if(end!=null && end>s.start) Interval(s.start,end).intersect(window) else null
  }
  return IntervalMath.subtract(ranges,locks)
 }
}
