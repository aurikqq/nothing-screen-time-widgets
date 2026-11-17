package com.example.nothingwidgets.core

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

data class AwayResult(val window: AwakeWindow,val intervals:List<Interval>,val totalMillis:Long)
object ReplicaAway {
 fun calculate(events: List<ScreenEvent>,now:Instant,zone:ZoneId,sleep:SleepSchedule,minimum:Duration=Duration.ofMinutes(10)):AwayResult {
  val window=AwakeWindow.at(now,zone,sleep)
  val until=minOf(now,window.cycleEnd)
  val observed=events.filter{it.timestamp>=window.start.minusSeconds(1800) && it.timestamp<=until}.sortedBy{it.timestamp}
  val firstBoot=observed.filter{it.type==ScreenEventType.DEVICE_STARTUP && it.timestamp>=window.start}.minOfOrNull{it.timestamp} ?: window.start
  val raw=mutableListOf<Interval>()
  var locked:Instant?=null;var off:Instant?=null;var shutdown:Instant?=null
  var boot:Instant?=null;var lastShutdown:Instant?=null
  fun append(a:Instant,b:Instant){Interval(a,minOf(maxOf(a,b),until)).intersect(Interval(window.start,until))?.let{raw.add(it)}}
  fun fallback()=maxOf(window.start,boot?:firstBoot,lastShutdown?:firstBoot)
  observed.forEach { event -> val t=event.timestamp
   when(event.type){
    ScreenEventType.KEYGUARD_SHOWN->locked=t
    ScreenEventType.KEYGUARD_HIDDEN->{append(locked?:fallback(),t);locked=null}
    ScreenEventType.SCREEN_NON_INTERACTIVE->off=t
    ScreenEventType.SCREEN_INTERACTIVE->{append(off?:fallback(),t);off=null}
    ScreenEventType.DEVICE_SHUTDOWN->{shutdown=t;lastShutdown=t}
    ScreenEventType.DEVICE_STARTUP->{append(shutdown?:window.start,t);shutdown=null;boot=t}
    ScreenEventType.DATA_GAP->{locked=null;off=null;shutdown=null}
   }
  }
  listOfNotNull(locked,off,shutdown).forEach{append(it,until)}
  val active=Interval(window.start,window.end)
  val effective=IntervalMath.union(raw).filter{it.duration>=minimum}.mapNotNull{it.intersect(active)}
  return AwayResult(window,effective,IntervalMath.millis(effective))
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
