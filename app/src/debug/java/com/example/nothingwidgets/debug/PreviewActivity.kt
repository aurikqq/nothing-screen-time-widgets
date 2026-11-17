package com.example.nothingwidgets.debug

import android.app.Activity
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.Color
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.nothingwidgets.render.WidgetRenderer as R
import com.example.nothingwidgets.weekly.WeeklyAwayActivity
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters

/** Synthetic visual fixtures are confined to the debug build. */
class PreviewActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra("pin")?.let { type ->
            val provider = if (type == "screen") com.example.nothingwidgets.widget.ScreenTimeWidgetProvider::class.java else com.example.nothingwidgets.widget.AwayWidgetProvider::class.java
            android.appwidget.AppWidgetManager.getInstance(this).requestPinAppWidget(android.content.ComponentName(this, provider), null, null)
            finish()
            return
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(110, 65, 60)); setPadding(16,16,16,16) }
        setContentView(ScrollView(this).apply { addView(column) })
        fun item(name: String, bitmap: Bitmap) {
            column.addView(TextView(this).apply { text = name; setTextColor(Color.WHITE); textSize = 20f })
            column.addView(ImageView(this).apply { setImageBitmap(bitmap); adjustViewBounds = true }, LinearLayout.LayoutParams(500, 500))
        }
        val a = R.AwayData(742, listOf(R.Interval(0f,.75f)))
        item("Away", R.away(564,564,a))
        item("Away Monday", R.away(564,564,a.copy(monday=true)))
        item("Screen face", R.screen(564,564,R.ScreenData(118,180),R.ScreenPage.FACE))
        item("Screen over limit", R.screen(564,564,R.ScreenData(181,180,overLimit=true),R.ScreenPage.FACE))
        item("Screen statistics", R.screen(564,564,R.ScreenData(118,180),R.ScreenPage.TIME))
        val monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        column.addView(Button(this).apply { text = "Weekly fixture"; setOnClickListener { startActivity(WeeklyAwayActivity.createIntent(this@PreviewActivity, monday, listOf(600L,570L,510L,480L,200L,660L,640L))) } })
        column.addView(Button(this).apply { text = "Previous week fixture"; setOnClickListener { startActivity(WeeklyAwayActivity.createIntent(this@PreviewActivity, monday.minusWeeks(1), listOf(600L,570L,510L,480L,200L,660L,640L))) } })
    }
}
