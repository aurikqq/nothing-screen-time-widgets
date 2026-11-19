package com.aurikqq.noswidgets.weekly

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import com.aurikqq.noswidgets.core.DailyResult
import com.aurikqq.noswidgets.data.StatisticsRepository
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Translucent detail window. The caller supplies measured aggregates; no example data is generated. */
class WeeklyAwayActivity : Activity() {
    private var insight = false
    override fun finish() {
        if (insight) { WeeklyInsightStore.acknowledge(this); com.aurikqq.noswidgets.background.updateWidgets(this) }
        super.finish()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        window.attributes = window.attributes.apply { dimAmount = 0.32f; blurBehindRadius = 48 }
        // Unsupported or disabled system blur falls back to the dimmed transparent window.
        if (!intent.hasExtra(EXTRA_MINUTES) && WeeklyInsightStore.pending(this)) {
            insight = true
            setContentView(InsightView(this, WeeklyInsightStore.values(this)) { finish() })
            window.setBackgroundBlurRadius(48)
            return
        }
        val today = LocalDate.now()
        val defaultMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val monday = runCatching { LocalDate.ofEpochDay(intent.getLongExtra(EXTRA_MONDAY, defaultMonday.toEpochDay())) }.getOrElse {
            defaultMonday
        }
        val values = intent.getLongArrayExtra(EXTRA_MINUTES)
        val minutes = (0..6).map { index -> values?.getOrNull(index)?.takeIf { it in 0L..1500L } }
        val selected = runCatching { LocalDate.ofEpochDay(intent.getLongExtra(EXTRA_SELECTED,
            LocalDate.now().toEpochDay())) }.getOrDefault(LocalDate.now())
        setContentView(WeeklyAwayView(this, monday, minutes, selected, intent.getBooleanExtra(EXTRA_PARTIAL, true)) {
            finish()
        })
        window.setBackgroundBlurRadius(48)
        if (!intent.hasExtra(EXTRA_MINUTES)) {
            Thread({
                val measured = runCatching {
                    val repository = StatisticsRepository(applicationContext)
                    repository.refresh()
                    (0..6).map { index ->
                        val date = monday.plusDays(index.toLong())
                        if (date.isAfter(today)) null else repository.readDay(date)?.let { if (it.awayPartial) null else it.awayMillis?.div(60_000L) }
                    }
                }.getOrElse { List(7) { null } }
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        val incomplete = measured.withIndex().any { (i, value) -> !monday.plusDays(i.toLong()).isAfter(today) && value == null }
                        setContentView(WeeklyAwayView(this, monday, measured, selected, incomplete) { finish() })
                    }
                }
            }, "weekly-away-read").start()
        }
    }

    companion object {
        private const val EXTRA_MONDAY = "weekly.monday"
        private const val EXTRA_MINUTES = "weekly.minutes"
        private const val EXTRA_SELECTED = "weekly.selected"
        private const val EXTRA_PARTIAL = "weekly.partial"

        /** Null days are unknown/future, distinct from a confirmed zero. Durations are rounded down. */
        fun createIntent(context: Context, monday: LocalDate, awayMinutes: List<Long?>,
                         selected: LocalDate = LocalDate.now(), partial: Boolean = awayMinutes.any { it == null }): Intent {
            require(monday.dayOfWeek == DayOfWeek.MONDAY && awayMinutes.size == 7)
            require(awayMinutes.all { it == null || it in 0L..1500L })
            return Intent(context, WeeklyAwayActivity::class.java)
                .putExtra(EXTRA_MONDAY, monday.toEpochDay())
                .putExtra(EXTRA_MINUTES, awayMinutes.map { it ?: -1L }.toLongArray())
                .putExtra(EXTRA_SELECTED, selected.toEpochDay())
                .putExtra(EXTRA_PARTIAL, partial)
        }

        /** Pass WeeklyStatistics results in calendar order, including empty future days. */
        fun createIntent(context: Context, days: List<DailyResult>, selected: LocalDate = LocalDate.now()): Intent {
            require(days.size == 7)
            require(days.first().date.dayOfWeek == DayOfWeek.MONDAY)
            require(days.withIndex().all { (index, day) -> day.date == days.first().date.plusDays(index.toLong()) })
            val minutes = days.map { day ->
                day.awayDuration.toMinutes().takeIf {
                    !day.observedDuration.isZero && day.awakeUnknownDuration.isZero
                }
            }
            val partial = days.any { day ->
                !day.usage.complete || day.observedDuration.isZero || !day.date.isBefore(LocalDate.now())
            }
            return createIntent(context, days.first().date, minutes, selected, partial)
        }
    }
}
