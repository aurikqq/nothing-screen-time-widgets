package com.example.nothingwidgets.update;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/** Must be declared android:exported="false". Only an explicit mutable PendingIntent is issued. */
public final class UpdateInstallReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmation == null) { record(context, "Системный установщик не предоставил подтверждение."); return; }
            record(context, "Нужно подтверждение системного установщика. Откройте приложение.");
            if (UpdateController.offerSystemConfirmation(confirmation)) return;
            if (UpdateController.notificationsAllowed(context)) {
                UpdateController.ensureChannel(context);
                Intent open = new Intent(context, UpdateConfirmationActivity.class).setAction("confirm-install")
                        .putExtra(UpdateConfirmationActivity.EXTRA_CONFIRMATION, confirmation);
                PendingIntent content = PendingIntent.getActivity(context, 4102, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                Notification notification = new Notification.Builder(context, UpdateController.CHANNEL)
                        .setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("Подтвердите установку APK")
                        .setContentText("Нажмите, чтобы открыть системное подтверждение.")
                        .setContentIntent(content).setAutoCancel(true).build();
                context.getSystemService(NotificationManager.class).notify(4102, notification);
            }
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            UpdateController.installationResult(context, true, "Системный установщик подтвердил успешную установку.");
            context.getSystemService(NotificationManager.class).cancel(4102);
        } else {
            UpdateController.installationResult(context, false, status == PackageInstaller.STATUS_FAILURE_ABORTED ? "Установка отменена пользователем." : "Системный установщик отклонил APK (код " + status + ").");
            context.getSystemService(NotificationManager.class).cancel(4102);
        }
    }
    private static void record(Context context, String value) { UpdateController.preferences(context).edit().putString("status", value).apply(); }
}
