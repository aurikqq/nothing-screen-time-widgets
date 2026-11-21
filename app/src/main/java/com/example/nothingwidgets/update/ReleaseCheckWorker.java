package com.aurikqq.noswidgets.update;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** Daily metadata-only check. This class never calls the APK download or installation methods. */
public final class ReleaseCheckWorker extends Worker {
    public ReleaseCheckWorker(Context context, WorkerParameters parameters) { super(context, parameters); }

    @Override public Result doWork() {
        Context context = getApplicationContext();
        GitHubUpdater updater = UpdateController.configuredUpdater(context);
        if (updater == null) return Result.success();
        try {
            GitHubUpdater.Release release = updater.checkLatest();
            UpdateController.recordCandidate(context, release);
            if (!UpdateController.eligible(context, release)) return Result.success();
            String seen = UpdateController.preferences(context).getString("notified_tag", "");
            if (!seen.equals(release.tag) && UpdateController.notificationsAllowed(context)) {
                UpdateController.ensureChannel(context);
                Intent open = new Intent(context, UpdateConfirmationActivity.class).setAction("check-release");
                PendingIntent content = PendingIntent.getActivity(context, 4101, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                Notification notification = new Notification.Builder(context, UpdateController.CHANNEL)
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setContentTitle(context.getString(com.aurikqq.noswidgets.R.string.release_published, release.tag))
                        .setContentText(context.getString(com.aurikqq.noswidgets.R.string.loc_72))
                        .setStyle(new Notification.BigTextStyle().bigText(context.getString(com.aurikqq.noswidgets.R.string.release_notice, release.tag)))
                        .setContentIntent(content).setAutoCancel(true).build();
                context.getSystemService(NotificationManager.class).notify(UpdateController.RELEASE_NOTIFICATION, notification);
                UpdateController.preferences(context).edit().putString("notified_tag", release.tag).apply();
            }
            return Result.success();
        } catch (Exception error) {
            UpdateController.preferences(context).edit().putString("status", context.getString(com.aurikqq.noswidgets.R.string.worker_error, error.getClass().getSimpleName())).apply();
            // Periodic work will try on its next scheduled run; avoid repeatedly exhausting GitHub API limits.
            return Result.success();
        }
    }
}
