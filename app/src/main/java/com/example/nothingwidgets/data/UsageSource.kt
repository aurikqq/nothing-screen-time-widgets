package com.aurikqq.noswidgets.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.UserManager
import android.provider.Settings
import com.aurikqq.noswidgets.core.ActivityEvent
import com.aurikqq.noswidgets.core.ActivityEventType
import android.content.pm.LauncherApps
import com.aurikqq.noswidgets.core.ScreenEvent
import com.aurikqq.noswidgets.core.ScreenEventType
import java.time.Duration
import java.time.Instant

/** A denied/unavailable query is distinct from a successful query with no retained events. */
enum class UsageAccess { GRANTED, REQUIRED, USER_LOCKED, UNAVAILABLE }

data class UsageSnapshot(
    val access: UsageAccess,
    val events: List<ScreenEvent>,
    val queriedFrom: Instant,
    val queriedUntil: Instant,
    val activityEvents: List<ActivityEvent> = emptyList()
)

/**
 * Reads only real Android events. Run read() off the main thread.
 * A successful query does not prove complete retention: the system keeps only a few days.
 * Consumers must pass the events to the core with ScreenState.UNKNOWN, including when empty.
 */
class UsageSource(context: Context) {
    private val context = context.applicationContext

    fun permissionIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    @Suppress("DEPRECATION")
    fun hasAccess(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        return try {
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
        } catch (_: SecurityException) {
            false
        }
    }

    /**
     * Includes preceding events to recover state at from when Android still retains them.
     * No current screen/lock sample is projected backwards into history. Shutdown/startup
     * explicitly invalidate state; missing initial state stays unknown in the core.
     */
    fun read(from: Instant, until: Instant): UsageSnapshot {
        require(!until.isBefore(from))
        val queryStart = from.minus(Duration.ofDays(2)).coerceAtLeast(Instant.EPOCH)
        fun result(access: UsageAccess, events: List<ScreenEvent> = emptyList(), activities: List<ActivityEvent> = emptyList()) =
            UsageSnapshot(access, events, queryStart, until, activities)
        if (Build.VERSION.SDK_INT < 28) return result(UsageAccess.UNAVAILABLE)
        if (!hasAccess()) return result(UsageAccess.REQUIRED)
        val users = context.getSystemService(UserManager::class.java)
            ?: return result(UsageAccess.UNAVAILABLE)
        if (!users.isUserUnlocked) return result(UsageAccess.USER_LOCKED)
        val manager = context.getSystemService(UsageStatsManager::class.java)
            ?: return result(UsageAccess.UNAVAILABLE)
        return try {
            val source = manager.queryEvents(queryStart.toEpochMilli(), until.toEpochMilli())
                ?: return result(UsageAccess.UNAVAILABLE)
            val launchable = context.getSystemService(LauncherApps::class.java)
                ?.getActivityList(null, Process.myUserHandle())?.map { it.componentName.packageName }?.toSet().orEmpty()
            if (launchable.isEmpty()) return result(UsageAccess.UNAVAILABLE)
            val activities = mutableListOf<ActivityEvent>()
            val events = mutableListOf<ScreenEvent>()
            val event = UsageEvents.Event()
            while (source.hasNextEvent()) {
                if (!source.getNextEvent(event)) break
                val activityType = when(event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> ActivityEventType.RESUMED
                    UsageEvents.Event.ACTIVITY_PAUSED -> ActivityEventType.PAUSED
                    UsageEvents.Event.ACTIVITY_STOPPED -> ActivityEventType.STOPPED
                    UsageEvents.Event.KEYGUARD_SHOWN -> ActivityEventType.KEYGUARD_SHOWN
                    UsageEvents.Event.KEYGUARD_HIDDEN -> ActivityEventType.KEYGUARD_HIDDEN
                    else -> null
                }
                if(activityType != null && (event.packageName in launchable || activityType == ActivityEventType.KEYGUARD_SHOWN || activityType == ActivityEventType.KEYGUARD_HIDDEN))
                    activities.add(ActivityEvent(Instant.ofEpochMilli(event.timeStamp), activityType, event.packageName ?: "", event.className ?: ""))
                mapUsageEvent(event.timeStamp, event.eventType)?.let {
                    if (it.timestamp >= queryStart && it.timestamp < until) events.add(it)
                }
            }
            // Permission can be revoked while a query is running; do not present that as zero.
            if (!hasAccess()) result(UsageAccess.REQUIRED)
            else result(UsageAccess.GRANTED, events.sortedBy { it.timestamp }, activities)
        } catch (_: SecurityException) {
            result(UsageAccess.REQUIRED)
        } catch (_: IllegalStateException) {
            result(UsageAccess.UNAVAILABLE)
        }
    }
}

/** Deliberately ignores per-activity events: they do not describe whole-device screen use. */
internal fun mapUsageEvent(timestampMillis: Long, eventType: Int): ScreenEvent? {
    val type = when (eventType) {
        UsageEvents.Event.SCREEN_INTERACTIVE -> ScreenEventType.SCREEN_INTERACTIVE
        UsageEvents.Event.SCREEN_NON_INTERACTIVE -> ScreenEventType.SCREEN_NON_INTERACTIVE
        UsageEvents.Event.KEYGUARD_SHOWN -> ScreenEventType.KEYGUARD_SHOWN
        UsageEvents.Event.KEYGUARD_HIDDEN -> ScreenEventType.KEYGUARD_HIDDEN
        UsageEvents.Event.DEVICE_SHUTDOWN -> ScreenEventType.DEVICE_SHUTDOWN
        UsageEvents.Event.DEVICE_STARTUP -> ScreenEventType.DEVICE_STARTUP
        else -> return null
    }
    return ScreenEvent(Instant.ofEpochMilli(timestampMillis), type)
}
