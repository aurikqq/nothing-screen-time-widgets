package com.aurikqq.noswidgets.data

import android.content.Context
import com.aurikqq.noswidgets.core.*
import org.json.JSONObject
import java.time.*

/** Settings are local to this installation. Equal sleep endpoints mean no scheduled sleep. */
class SettingsStore(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var sleepStartMinute: Int
        get() = p.getInt("sleep_start", 23 * 60).coerceIn(0, 1439)
        set(value) { require(value in 0..1439); p.edit().putInt("sleep_start", value).apply() }
    var sleepEndMinute: Int
        get() = p.getInt("sleep_end", 7 * 60).coerceIn(0, 1439)
        set(value) { require(value in 0..1439); p.edit().putInt("sleep_end", value).apply() }
    var limitMinutes: Int
        get() = p.getInt("limit_minutes", 180).coerceAtLeast(1)
        set(value) { require(value in 1..1440); p.edit().putInt("limit_minutes", value).apply() }
    fun limitForWidget(id: Int): Int = p.getInt("widget_limit_$id", limitMinutes).coerceIn(1, 1440)
    fun setWidgetLimit(id: Int, minutes: Int) { require(minutes in 1..1440); p.edit().putInt("widget_limit_$id", minutes).apply() }
    fun removeWidget(id: Int) { p.edit().remove("widget_limit_$id").apply() }
    val dailyLimitMillis: Long get() = limitMinutes * 60_000L
    fun sleep(): SleepSchedule = SleepSchedule(LocalTime.of(sleepStartMinute / 60, sleepStartMinute % 60), LocalTime.of(sleepEndMinute / 60, sleepEndMinute % 60))
    fun signature(): String = "$sleepStartMinute:$sleepEndMinute"
}

data class DailySnapshot(
    val date: LocalDate, val awayMillis: Long?, val screenMillis: Long?, val awakeMillis: Long,
    val updatedAtMillis: Long, val awayIntervals: List<Interval> = emptyList(),
    val awayPartial: Boolean = false, val screenPartial: Boolean = false,
    val awayAxisDate: LocalDate = date, val calculationModel: Int = 4
)

/** Available intervals form a lower bound when part of the day is unknown. */
internal fun snapshotFrom(result: DailyResult, now: Instant, sleep: SleepSchedule? = null, zone: ZoneId = ZoneId.systemDefault()): DailySnapshot = DailySnapshot(
    result.date,
    result.awayDuration.toMillis().takeIf { !result.awakeKnownDuration.isZero || (result.awakeUnknownDuration.isZero && !result.observedDuration.isZero) },
    result.usage.knownUsage.toMillis().takeIf { result.observedDuration > result.usage.unknownDuration },
    result.awakeDuration.toMillis(), now.toEpochMilli(), result.usage.inactiveIntervals.flatMap { interval ->
        var pieces = listOf(interval)
        sleep?.sleepIntervals(interval.start, interval.end, zone).orEmpty().forEach { excluded ->
            pieces = pieces.flatMap { piece ->
                val overlap = piece.intersect(excluded)
                if (overlap == null) listOf(piece) else buildList {
                    if (piece.start < overlap.start) add(Interval(piece.start, overlap.start))
                    if (overlap.end < piece.end) add(Interval(overlap.end, piece.end))
                }
            }
        }
        pieces
    },
    awayPartial = !result.awakeUnknownDuration.isZero,
    screenPartial = !result.usage.unknownDuration.isZero
)

internal fun replicaSnapshot(events: UsageSnapshot, date: LocalDate, now: Instant, zone: ZoneId, sleep: SleepSchedule): DailySnapshot {
    val away = ReplicaAway.calculate(events.events, now, zone, sleep)
    val start = date.atStartOfDay(zone).toInstant()
    val until = minOf(now, date.plusDays(1).atStartOfDay(zone).toInstant())
    val activity = ReplicaScreenTime.intervals(events.activityEvents, start, until)
    val available = events.events.isNotEmpty() || events.activityEvents.isNotEmpty()
    return DailySnapshot(date, away.totalMillis.takeIf { available }, IntervalMath.millis(activity).takeIf { available },
        Duration.between(away.window.start,away.window.end).toMillis(),now.toEpochMilli(),away.intervals,
        awayPartial = !available || away.unknownMillis > 0, screenPartial = !available, awayAxisDate = away.window.date)
}

/** Call refresh on a background thread. Stored days are immutable after closing. */
class StatisticsRepository(context: Context) {
    private val ctx = context.applicationContext
    private val source = UsageSource(ctx)
    private val settings = SettingsStore(ctx)
    private val p = ctx.getSharedPreferences("daily_history", Context.MODE_PRIVATE)
    private fun key(date: LocalDate, zone: ZoneId) = "${zone.id}|$date"
    private fun encode(s: DailySnapshot, signature: String) = JSONObject().apply {
        put("model", s.calculationModel); put("axisDate", s.awayAxisDate.toString()); put("date", s.date.toString()); put("away", s.awayMillis ?: JSONObject.NULL)
        put("screen", s.screenMillis ?: JSONObject.NULL); put("awake", s.awakeMillis)
        put("intervals", org.json.JSONArray().apply { s.awayIntervals.forEach { put(org.json.JSONArray().put(it.start.toEpochMilli()).put(it.end.toEpochMilli())) } }); put("updated", s.updatedAtMillis); put("sleep", signature); put("awayPartial", s.awayPartial); put("screenPartial", s.screenPartial)
    }.toString()
    private fun decode(raw: String?): DailySnapshot? = try {
        if (raw == null) null else JSONObject(raw).let {
            DailySnapshot(LocalDate.parse(it.getString("date")), if (it.isNull("away")) null else it.getLong("away"),
                if (it.isNull("screen")) null else it.getLong("screen"), it.getLong("awake"), it.getLong("updated"),
                awayIntervals = it.optJSONArray("intervals")?.let { array -> (0 until array.length()).map { index -> val row = array.getJSONArray(index); Interval(Instant.ofEpochMilli(row.getLong(0)), Instant.ofEpochMilli(row.getLong(1))) } }.orEmpty(),
                awayPartial = it.optBoolean("awayPartial", false), screenPartial = it.optBoolean("screenPartial", false),
                awayAxisDate = LocalDate.parse(it.optString("axisDate",it.getString("date"))), calculationModel = it.optInt("model",1))
        }
    } catch (_: Exception) { null }
    fun readToday(): DailySnapshot? {
        if (!source.hasAccess()) return null
        val zone = ZoneId.systemDefault(); val date = LocalDate.now(zone)
        val raw = p.getString("current", null) ?: return null
        return try { if (JSONObject(raw).optString("sleep") == settings.signature() && p.getString("current_zone", "") == zone.id) decode(raw)?.takeIf { it.calculationModel == 4 && it.date == date && it.updatedAtMillis <= System.currentTimeMillis() && p.getString("clock_uncertain", "") != key(date, zone) } else null } catch (_: Exception) { null }
    }
    fun readDay(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): DailySnapshot? =
        if (zone == ZoneId.systemDefault() && date == LocalDate.now(zone)) readToday() else decode(p.getString(key(date, zone), null))?.let { snapshot ->
            when (snapshot.calculationModel) {
                4 -> snapshot
                3 -> {
                    val breaks = ReplicaAway.qualifyingBreaks(snapshot.awayIntervals)
                    snapshot.copy(
                        awayMillis = snapshot.awayMillis?.let { IntervalMath.millis(breaks) },
                        awayIntervals = breaks,
                        calculationModel = 4
                    )
                }
                else -> null
            }
        }
    fun markClockUncertain() {
        val zone = ZoneId.systemDefault()
        p.edit().putString("clock_uncertain", key(LocalDate.now(zone), zone)).remove("current").apply()
    }
    fun diagnosticSummary(): String = p.getString("diagnostic", if (ctx.resources.configuration.locales[0].language == "ru") "Обновление ещё не выполнялось." else "No update has been recorded yet.") ?: ""
    fun refresh(): DailySnapshot = synchronized(refreshLock) {
        val now = Instant.now(); val zone = ZoneId.systemDefault(); val date = now.atZone(zone).toLocalDate()
        val dayStart = date.atStartOfDay(zone).toInstant()
        val schedule = settings.sleep()
        val signature = "${schedule.start.hour * 60 + schedule.start.minute}:${schedule.end.hour * 60 + schedule.end.minute}"
        val currentRaw = p.getString("current", null)
        val current = decode(currentRaw)
        val currentZone = p.getString("current_zone", "")
        if (current != null && current.date == date.minusDays(1) && currentZone == zone.id &&
            p.getString("pending_day", null) == null) {
            p.edit().putString("pending_day", currentRaw).putString("pending_zone", currentZone).apply()
        }
        val oldRaw = p.getString("pending_day", null)
        val old = decode(oldRaw)
        val oldZone = p.getString("pending_zone", "")
        val oldSleep = try { oldRaw?.let { JSONObject(it).optString("sleep") } } catch (_: Exception) { null }
        // Keep a pending day if permission is temporarily unavailable after midnight.
        if (old != null && old.date == date.minusDays(1) && oldZone == zone.id && oldSleep == signature &&
            p.getString("clock_uncertain", "") != key(old.date, zone) &&
            now >= date.atTime(schedule.end).atZone(zone).toInstant() && now.toEpochMilli() >= old.updatedAtMillis && (decode(p.getString(key(old.date, zone), null))?.calculationModel != 4)) {
            val from = old.date.atStartOfDay(zone).toInstant()
            val closeTime = date.atTime(schedule.end).atZone(zone).toInstant().minusNanos(1)
            val events = source.read(from, closeTime)
            if (events.access == UsageAccess.GRANTED) {
                val closed = replicaSnapshot(events, old.date, closeTime, zone, schedule)
                p.edit().putString(key(old.date, zone), encode(closed, signature)).remove("pending_day").remove("pending_zone").apply()
            }
        } else if (old != null && (old.date < date.minusDays(1) || oldZone != zone.id || oldSleep != signature)) {
            p.edit().remove("pending_day").remove("pending_zone").apply()
        }
        val events = source.read(minOf(dayStart, AwakeWindow.at(now,zone,schedule).start), now)
        val reliableClock = p.getString("clock_uncertain", "") != key(date, zone) && System.currentTimeMillis() >= now.toEpochMilli()
        val result = if (events.access == UsageAccess.GRANTED && reliableClock)
            replicaSnapshot(events, date, now, zone, schedule)
        else DailySnapshot(date, null, null, Duration.between(dayStart, date.plusDays(1).atStartOfDay(zone).toInstant()).toMillis(), now.toEpochMilli())
        val russian = ctx.resources.configuration.locales[0].language == "ru"
        fun localized(ru: String, en: String) = if (russian) ru else en
        val diagnosticRu = "Методика: Nothing-compatible public API, Away ≥10 мин в часы бодрствования, Screen=Activity intervals\nДоступ: ${events.access}\nОбновлено: ${now.atZone(zone)}\nСон: ${schedule.start}–${schedule.end}\nСобытий: ${events.events.size}\n" +
            events.events.groupingBy { it.type }.eachCount().entries.joinToString("\n") { "${it.key}: ${it.value}" } +
            "\nЭкран: ${result.screenMillis?.div(60000)} мин; неполно: ${result.screenPartial}\nAway: ${result.awayMillis?.div(60000)} мин; неполно: ${result.awayPartial}"
        val diagnostic = if (russian) diagnosticRu else diagnosticRu
            .replace("Методика: Nothing-compatible public API, Away ≥10 мин в часы бодрствования, Screen=Activity intervals", "Method: 10-minute Away breaks during waking hours, Screen=Activity intervals")
            .replace("Доступ:", "Access:").replace("Обновлено:", "Updated:").replace("Сон:", "Sleep:")
            .replace("Событий:", "Events:").replace("Экран:", "Screen:").replace("Away:", "Away:")
            .replace(" мин; неполно:", " min; incomplete:")
        val detail = if (events.access == UsageAccess.GRANTED && reliableClock) {
            val daily = DailyStatistics.calculate(events.events, date, zone, now, schedule)
            val format = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(zone)
            val screenOnly = DailyStatistics.calculate(screenInteractiveEvents(events.events), date, zone, now, schedule)
            fun ranges(label: String, intervals: List<Interval>): String = "\n$label:\n" + intervals.joinToString("\n") { "${format.format(it.start)}–${format.format(it.end)}" }
            localized("\nСобытий за сегодня: ", "\nEvents today: ") + events.events.count { it.timestamp >= dayStart } +
                localized("\nДля сравнения: Away только при выключенном экране: ", "\nComparison: Away with screen off only: ") + screenOnly.awayDuration.toMinutes() + localized(" мин; неполно: ", " min; incomplete: ") + !screenOnly.awakeUnknownDuration.isZero +
                localized("\nСобытия экрана/блокировки за сегодня (без приложений):\n", "\nScreen and lock events today (excluding apps):\n") +
                events.events.filter { it.timestamp >= dayStart }.joinToString("\n") { "${format.format(it.timestamp)} ${it.type}" } +
                ranges(localized("Экран используется (Activity)", "Screen use (Activity)"), ReplicaScreenTime.intervals(events.activityEvents,dayStart,now)) +
                ranges(localized("Подтверждённый Away вне сна", "Confirmed Away during waking hours"), result.awayIntervals) +
                localized("\nПрежняя методика разблокированного экрана (для сравнения): ", "\nPrevious unlocked-screen method (comparison): ") + daily.usage.knownUsage.toMinutes() + localized(" мин", " min")
        } else ""
        p.edit().putString("diagnostic", diagnostic + detail).putString("current", encode(result, signature)).putString("current_zone", zone.id).apply()
        result
    }
    companion object { private val refreshLock = Any() }
}
