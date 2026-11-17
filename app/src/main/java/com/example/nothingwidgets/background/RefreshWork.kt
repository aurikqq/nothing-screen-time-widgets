package com.example.nothingwidgets.background

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.work.*
import com.example.nothingwidgets.widget.AwayWidgetProvider
import com.example.nothingwidgets.widget.ScreenTimeWidgetProvider
import java.util.concurrent.TimeUnit

class RefreshWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        updateWidgets(applicationContext)
        return Result.success()
    }
}
fun updateWidgets(context: Context) {
    val manager = AppWidgetManager.getInstance(context)
    for (provider in listOf(AwayWidgetProvider::class.java, ScreenTimeWidgetProvider::class.java)) {
        val component = ComponentName(context, provider)
        val ids = manager.getAppWidgetIds(component)
        if (ids.isNotEmpty()) context.sendBroadcast(Intent(context, provider).setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE).putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids))
    }
}
fun scheduleRefresh(context: Context) {
    WorkManager.getInstance(context).enqueueUniquePeriodicWork("widget-refresh", ExistingPeriodicWorkPolicy.KEEP,
        PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES).build())
}
class SystemChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_TIME_CHANGED) com.example.nothingwidgets.data.StatisticsRepository(context).markClockUncertain()
        scheduleRefresh(context)
        WorkManager.getInstance(context).enqueueUniqueWork("widget-refresh-now", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<RefreshWorker>().build())
    }
}
