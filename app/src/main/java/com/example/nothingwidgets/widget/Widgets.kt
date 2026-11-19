package com.aurikqq.noswidgets.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.content.res.Configuration
import android.util.TypedValue
import android.util.SizeF
import android.os.Bundle
import android.util.Log
import android.widget.RemoteViews
import com.aurikqq.noswidgets.R
import com.aurikqq.noswidgets.background.scheduleRefresh
import com.aurikqq.noswidgets.data.DailySnapshot
import com.aurikqq.noswidgets.data.SettingsStore
import com.aurikqq.noswidgets.data.StatisticsRepository
import com.aurikqq.noswidgets.render.WidgetRenderer
import java.util.concurrent.Executors
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.Duration

/** Small home-screen cards backed only by StatisticsRepository's confirmed daily totals. */
class AwayWidgetProvider : StatisticsWidgetProvider() {
    override val screenTime = false
}

/** Native vertical ListView with full-height transparent pages and a stationary card.
 * FACE is first. Scrolling is continuous: RemoteViews offers no native page snapping here.
 */
class ScreenTimeWidgetProvider : StatisticsWidgetProvider() {
    override val screenTime = true
}

abstract class StatisticsWidgetProvider : AppWidgetProvider() {
    protected abstract val screenTime: Boolean

    override fun onEnabled(context: Context) {
        scheduleRefresh(context)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        refresh(context, manager, ids)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, manager: AppWidgetManager, id: Int, options: Bundle,
    ) {
        refresh(context, manager, intArrayOf(id))
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        val edit = pages(context).edit()
        ids.forEach {
            edit.remove(pageKey(it))
            edit.remove(resetKey(it))
            SettingsStore(context).removeWidget(it)
        }
        edit.apply()
    }

    private fun refresh(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val app = context.applicationContext
        val repository = StatisticsRepository(app)
        val cached = repository.readToday()
        ids.filter { owns(app, manager, it) }.forEach { draw(app, manager, it, cached) }
        // UsageStats queries run off the receiver thread. goAsync keeps this receiver alive
        // while the short local query runs; no timer or foreground service is introduced.
        val pending = goAsync()
        executor.execute {
            try {
                val snapshot = repository.refresh()
                ids.filter { owns(app, manager, it) }.forEach { draw(app, manager, it, snapshot) }
            } catch (_: RuntimeException) {
                Log.w("NothingWidgets", "Widget data refresh unavailable")
                ids.filter { owns(app, manager, it) }.forEach { draw(app, manager, it, null) }
            } finally {
                pending.finish()
            }
        }
    }

    private fun owns(context: Context, manager: AppWidgetManager, id: Int): Boolean =
        manager.getAppWidgetInfo(id)?.provider == ComponentName(context, javaClass)

    private fun draw(context: Context, manager: AppWidgetManager, id: Int, data: DailySnapshot?) {
        if (screenTime) {
            drawScreen(context, manager, id, data)
            return
        }
        // Away remains a standalone ImageView, separate from the experimental collection.
        val view = RemoteViews(context.packageName, R.layout.away_widget)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val axis = com.aurikqq.noswidgets.core.AwayTimeline(data?.awayAxisDate ?: com.aurikqq.noswidgets.core.AwakeWindow.at(Instant.now(),zone,SettingsStore(context).sleep()).date, zone, SettingsStore(context).sleep())
        val intervals = axis.segments(data?.awayIntervals.orEmpty()).map {
            WidgetRenderer.Interval(it.start.toFloat(), it.end.toFloat())
        }
        val options = manager.getAppWidgetOptions(id)
        val density = context.resources.displayMetrics.density
        val sideDp = minOf(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,180),
            options.getInt(if(context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,180)).coerceAtLeast(1)
        val bitmap = WidgetRenderer.away(BITMAP_SIZE, BITMAP_SIZE,
            WidgetRenderer.AwayData(data?.awayMillis?.div(60_000L), intervals,
                nowFraction = axis.fraction(Instant.now()).toFloat(),
                monday = com.aurikqq.noswidgets.weekly.WeeklyInsightStore.pending(context),
                partial = data?.awayPartial ?: false, displaySidePixels=sideDp*density, displayDensity=density),
            palette = widgetPalette(context), fonts = widgetFonts(context))
        view.setImageViewBitmap(R.id.widget_image, bitmap)
        view.setContentDescription(R.id.widget_image,
            summary("Away", data?.awayMillis, data?.awayPartial ?: false) + ". Tap for weekly statistics")
        val intent = Intent().setClassName(context.packageName,
            "com.aurikqq.noswidgets.weekly.WeeklyAwayActivity")
        val tap = PendingIntent.getActivity(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        view.setOnClickPendingIntent(R.id.widget_image, tap)
        manager.updateAppWidget(id, view)
    }

    private fun drawScreen(context: Context, manager: AppWidgetManager, id: Int, data: DailySnapshot?) {
        val palette = widgetPalette(context)
        val limit = SettingsStore(context).limitForWidget(id)
        val partial = data?.screenPartial ?: false
        val screen = WidgetRenderer.ScreenData(data?.screenMillis?.div(60_000L), limit.toLong(),
            overLimit = com.aurikqq.noswidgets.core.limitExceeded(data?.screenMillis, limit),
            partial = partial)
        val background = WidgetRenderer.screenBackground(128, 128, screen, palette)
        val pageOrder = listOf(WidgetRenderer.ScreenPage.FACE, WidgetRenderer.ScreenPage.TIME)
        val fonts = widgetFonts(context).copy(dot = try {
            android.graphics.Typeface.createFromAsset(context.assets, "fonts/NDot-55.otf")
        } catch (_: RuntimeException) {
            android.graphics.Typeface.create("monospace", android.graphics.Typeface.NORMAL)
        })
        val bitmaps = pageOrder.map { page ->
            WidgetRenderer.screen(BITMAP_SIZE, BITMAP_SIZE, screen, page,
                palette = palette, fonts = fonts, transparentBackground = true, drawEyes = false)
        }
        val eyes = WidgetRenderer.screenEyes(BITMAP_SIZE, BITMAP_SIZE, palette)
        val rootLayout = R.layout.screen_time_widget
        fun layout(sideDp: Float): RemoteViews {
            val view = RemoteViews(context.packageName, rootLayout)
            view.setViewLayoutWidth(R.id.screen_square, sideDp, TypedValue.COMPLEX_UNIT_DIP)
            view.setViewLayoutHeight(R.id.screen_square, sideDp, TypedValue.COMPLEX_UNIT_DIP)
            view.setImageViewBitmap(R.id.screen_background, background)
            val items = RemoteViews.RemoteCollectionItems.Builder()
                .setHasStableIds(true).setViewTypeCount(1)
            bitmaps.forEachIndexed { index, bitmap ->
                val face = pageOrder[index] == WidgetRenderer.ScreenPage.FACE
                val semanticPage = if (face) 0 else 1
                val item = RemoteViews(context.packageName, R.layout.screen_time_page)
                // ListView measures rows without a fixed parent height: match_parent alone
                // cannot make a viewport-sized row. Host-reported options are in dp.
                item.setViewLayoutHeight(R.id.screen_page_root, sideDp, TypedValue.COMPLEX_UNIT_DIP)
                item.setViewVisibility(R.id.blink_eyes, if (face && data?.screenMillis != null) android.view.View.VISIBLE else android.view.View.GONE)
                item.setImageViewBitmap(R.id.eyes_first, eyes)
                item.setImageViewBitmap(R.id.eyes_second, eyes)
                item.setImageViewBitmap(R.id.widget_image, bitmap)
                item.setContentDescription(R.id.widget_image,
                    summary("Screen time", data?.screenMillis, partial) +
                        if (face) ". Face page. Scroll vertically for time page"
                        else ". Time page. Scroll vertically for face page")
                items.addItem(semanticPage.toLong(), item)
            }
            view.setRemoteAdapter(R.id.screen_pages, items.build())
            // Clear templates from prior versions; screen pages now respond only to scrolling.
            view.setPendingIntentTemplate(R.id.screen_pages, null)
            return view
        }
        val options = manager.getAppWidgetOptions(id)
        @Suppress("DEPRECATION")
        val sizes = options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
            .orEmpty().filter { it.width > 0f && it.height > 0f }.distinct().take(16)
        val view = if (sizes.isNotEmpty()) {
            // API 31+: the launcher chooses its actual viewport variant, including rotation.
            // Bitmaps are shared across variants; only each row's dp height differs.
            RemoteViews(sizes.associateWith { layout(minOf(it.width, it.height)) })
        } else {
            val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val heightKey = if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT
                else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT
            val height = options.getInt(heightKey,
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 180)).coerceAtLeast(1)
            layout(minOf(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180), height).coerceAtLeast(1).toFloat())
        }
        // Full update replaces old bitmaps. No automatic selection or timer is installed.
        manager.updateAppWidget(id, view)
    }

    private fun widgetFonts(context: Context): WidgetRenderer.Fonts {
        val adjustment = context.resources.configuration.fontWeightAdjustment.let {
            if (it == Configuration.FONT_WEIGHT_ADJUSTMENT_UNDEFINED) 0 else it
        }
        fun adjusted(family: android.graphics.Typeface): android.graphics.Typeface =
            android.graphics.Typeface.create(family, (family.weight + adjustment).coerceIn(1,1000), family.isItalic)
        return WidgetRenderer.Fonts(label = adjusted(android.graphics.Typeface.SANS_SERIF),
            duration = adjusted(try {
                android.graphics.Typeface.createFromAsset(context.assets, "fonts/NType82-Regular.otf")
            } catch (_: RuntimeException) { android.graphics.Typeface.SERIF }), emboldenDot = adjustment > 0)
    }

    private fun summary(label: String, millis: Long?, partial: Boolean = false): String = millis?.div(60_000L)?.let {
        "$label: ${if (partial) "at least " else ""}${it / 60}h ${it % 60}m${if (partial) ". Partial data" else ""}"
    } ?: "$label: No data"

    private fun widgetPalette(context: Context): WidgetRenderer.Palette {
        val proposedBackground = context.getColor(android.R.color.system_neutral1_900)
        val background = if (1.05 / (Color.luminance(proposedBackground) + 0.05) >= 4.5)
            proposedBackground else Color.rgb(34, 26, 24)
        val proposedText = context.getColor(android.R.color.system_neutral1_50)
        val contrast = (Color.luminance(proposedText) + 0.05) /
            (Color.luminance(background) + 0.05)
        // Dark system neutral backgrounds retain the pale lettering of both references.
        val foreground = if (contrast >= 4.5) proposedText else Color.WHITE
        return WidgetRenderer.Palette(background, foreground,
            context.getColor(android.R.color.system_neutral1_700),
            context.getColor(android.R.color.system_accent1_600))
    }

    companion object {
        private const val BITMAP_SIZE = 564
        private val executor = Executors.newSingleThreadExecutor()
        private fun pageKey(id: Int) = "screen_page_$id"
        private fun resetKey(id: Int) = "screen_list_reset_$id"
        private fun pages(context: Context) =
            context.getSharedPreferences("widget_pages", Context.MODE_PRIVATE)

        /** Call after access or settings change; updates existing cards through their receivers. */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            listOf(AwayWidgetProvider::class.java, ScreenTimeWidgetProvider::class.java).forEach { type ->
                val ids = manager.getAppWidgetIds(ComponentName(context, type))
                if (ids.isNotEmpty()) context.sendBroadcast(Intent(context, type)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids))
            }
        }
    }
}
