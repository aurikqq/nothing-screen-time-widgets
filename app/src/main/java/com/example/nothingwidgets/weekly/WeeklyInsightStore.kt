package com.aurikqq.noswidgets.weekly

import android.content.Context
import java.time.*
import java.time.temporal.TemporalAdjusters
import org.json.JSONArray
import com.aurikqq.noswidgets.data.StatisticsRepository

object WeeklyInsightStore {
    private fun week() = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    fun shouldOffer(date: LocalDate, seen: String): Boolean = date.dayOfWeek == DayOfWeek.MONDAY && seen != date.toString()
    fun pending(context: Context): Boolean = shouldOffer(LocalDate.now(), context.getSharedPreferences("insight",0).getString("seen", "") ?: "")
    fun acknowledge(context: Context) { context.getSharedPreferences("insight",0).edit().putString("seen",week().toString()).apply() }
    /** Freeze the previous week's observations until the next weekly insight. Null is unknown. */
    fun values(context: Context): List<Long?> {
        val p = context.getSharedPreferences("insight",0)
        if (p.getString("week","") != week().toString()) {
            val repository = StatisticsRepository(context)
            val monday = week().minusWeeks(1)
            val array = JSONArray()
            (0..6).forEach { i -> val day = repository.readDay(monday.plusDays(i.toLong())); array.put(day?.takeUnless { it.awayPartial }?.awayMillis?.div(60000) ?: org.json.JSONObject.NULL) }
            p.edit().putString("week",week().toString()).putString("values",array.toString()).apply()
        }
        val array = JSONArray(p.getString("values","[]"))
        return (0..6).map { if (array.isNull(it)) null else array.getLong(it) }
    }
}
