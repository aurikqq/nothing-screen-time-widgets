package com.aurikqq.noswidgets.update;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import androidx.work.*;
import java.io.File;
import java.lang.ref.WeakReference;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Foreground update state for an inline banner. All APK operations run off the UI thread. */
public final class UpdateController {
    static final String CHANNEL = "github_releases";
    static final int RELEASE_NOTIFICATION = 4101;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<Activity> foreground = new WeakReference<>(null);
    private static Listener listener;
    private static volatile UiState state = new UiState(false, false, false, false, "", "", "", -1);
    private static volatile PendingDownload pending;
    private static volatile GitHubUpdater.Release candidate;
    private static Intent pendingSystemConfirmation;
    private UpdateController() {}

    public interface Listener { void onUpdateState(UiState state); }
    public static final class UiState {
        public final boolean visible, checking, downloading, readyToInstall;
        public final String tag, notes, message;
        public final int progress;
        UiState(boolean visible, boolean checking, boolean downloading, boolean ready, String tag, String notes, String message, int progress) {
            this.visible = visible; this.checking = checking; this.downloading = downloading;
            this.readyToInstall = ready; this.tag = tag; this.notes = notes; this.message = message; this.progress = progress;
        }
    }
    public static UiState getState() { return state; }
    private static void publish(UiState next) {
        state = next;
        MAIN.post(() -> { if (listener != null && foreground.get() != null) listener.onUpdateState(state); });
    }
    private static UiState releaseState(GitHubUpdater.Release release, boolean downloading, boolean ready, String message, int progress) {
        return new UiState(true, false, downloading, ready, release.tag, release.notes, message, progress);
    }
    /** Bind once on each onResume. Rebinds immediately, then checks latest stable release. */
    public static void attach(Activity activity, Listener callback) {
        foreground = new WeakReference<>(activity); listener = callback;
        callback.onUpdateState(state);
        if (pendingSystemConfirmation != null) {
            Intent confirmation = pendingSystemConfirmation; pendingSystemConfirmation = null;
            activity.startActivity(confirmation); return;
        }
        if (preferences(activity).getBoolean("resume_install", false) && pending != null) {
            if (activity.getPackageManager().canRequestPackageInstalls()) { install(activity); return; }
            preferences(activity).edit().putBoolean("resume_install", false).apply();
        }
        check(activity);
    }
    public static void detach(Activity activity) {
        if (foreground.get() == activity) { foreground.clear(); listener = null; }
    }
    static SharedPreferences preferences(Context context) { return context.getSharedPreferences("github_updates", Context.MODE_PRIVATE); }
    private static String configuredString(Context context, String name) {
        int id = context.getResources().getIdentifier(name, "string", context.getPackageName());
        return id == 0 ? "" : context.getString(id).trim();
    }
    static GitHubUpdater configuredUpdater(Context context) {
        try { return new GitHubUpdater(configuredString(context, "update_github_owner"), configuredString(context, "update_github_repository"), configuredString(context, "update_github_apk_asset")); }
        catch (IllegalArgumentException invalid) { return null; }
    }
    static boolean eligible(Context context, GitHubUpdater.Release release) throws Exception {
        return ReleaseVersion.isNewerStable(release.tag, context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName);
    }
    /** Metadata only. Every foreground entry calls this; concurrent work is coalesced. */
    public static void check(Activity activity) { check(activity, false); }
    /** Manual checks expose offline/no-release results in the banner for an explicit retry. */
    public static void checkManual(Activity activity) { check(activity, true); }
    private static void check(Activity activity, boolean manual) {
        Context app = activity.getApplicationContext();
        GitHubUpdater updater = configuredUpdater(app);
        if (updater == null || !BUSY.compareAndSet(false, true)) return;
        if (!state.visible) publish(new UiState(false, true, false, false, "", "", "Проверяем обновления…", -1));
        EXECUTOR.execute(() -> {
            try {
                if (pending == null) pending = restore(app, updater);
                if (pending != null) {
                    candidate = pending.release;
                    publish(releaseState(candidate, false, true, "Обновление готово к установке", 100));
                }
                // Metadata is refreshed on every foreground entry, even with a downloaded APK.
                GitHubUpdater.Release release = updater.checkLatest();
                if (eligible(app, release)) {
                    if (pending != null && ReleaseVersion.isNewerStable(release.tag, pending.release.tag)) {
                        deletePrivate(app, pending.apk); pending = null;
                        preferences(app).edit().remove("pending_apk").putBoolean("resume_install", false).apply();
                    }
                    if (pending == null) {
                        candidate = release; recordCandidate(app, release);
                        publish(releaseState(release, false, false, "Доступна новая версия", -1));
                    }
                } else if (pending == null) {
                    candidate = null;
                    publish(new UiState(manual, false, false, false, "", "", "Установлена актуальная версия", -1));
                }
            } catch (GitHubUpdater.NoPublishedReleaseException noRelease) {
                if (pending == null) {
                    candidate = null;
                    publish(new UiState(manual, false, false, false, "", "", "Релизы ещё не опубликованы", -1));
                }
            } catch (Exception error) {
                if (pending == null) {
                    candidate = null;
                    publish(new UiState(manual, false, false, false, "", "", "Не удалось проверить обновления. Повторите проверку позже.", -1));
                }
            } finally {
                BUSY.set(false);
                if (preferences(app).getBoolean("resume_install", false)) MAIN.post(() -> {
                    Activity current = foreground.get();
                    if (current != null && current.getPackageManager().canRequestPackageInstalls() && pending != null) install(current);
                    else preferences(app).edit().putBoolean("resume_install", false).apply();
                });
            }
        });
    }
    /** Explicit Download banner action; starts no installer. */
    public static void download(Activity activity) {
        GitHubUpdater.Release release = candidate;
        GitHubUpdater updater = configuredUpdater(activity);
        if (release == null || updater == null || !BUSY.compareAndSet(false, true)) return;
        Context app = activity.getApplicationContext();
        publish(releaseState(release, true, false, "Скачиваем обновление…", 0));
        EXECUTOR.execute(() -> {
            try {
                File apk = updater.downloadAfterUserRequest(app, release, (done, total) -> {
                    int percent = (int) (done * 100 / total);
                    if (percent != state.progress) publish(releaseState(release, true, false, percent == 100 ? "Проверяем APK…" : "Скачиваем обновление…", percent));
                });
                long version = updater.verify(app, release, apk);
                pending = new PendingDownload(updater, release, apk, version);
                persist(app, pending);
                publish(releaseState(release, false, true, "Обновление проверено и готово к установке", 100));
            } catch (Exception error) {
                publish(releaseState(release, false, false, "Не удалось скачать или проверить APK. Нажмите, чтобы повторить.", -1));
            } finally { BUSY.set(false); }
        });
    }
    /** Explicit Install action. Settings return resumes the already requested installation. */
    public static void install(Activity activity) {
        PendingDownload item = pending;
        if (item == null || BUSY.get()) return;
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            preferences(activity).edit().putBoolean("resume_install", true).apply();
            publish(releaseState(item.release, false, true, "Разрешите установку из этого приложения в настройках Android", 100));
            try { activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName()))); }
            catch (android.content.ActivityNotFoundException error) {
                preferences(activity).edit().putBoolean("resume_install", false).apply();
                publish(releaseState(item.release, false, true, "Экран разрешения установки недоступен", 100));
            }
            return;
        }
        if (!BUSY.compareAndSet(false, true)) return;
        Context app = activity.getApplicationContext();
        preferences(app).edit().putBoolean("resume_install", false).apply();
        publish(releaseState(item.release, true, false, "Открываем системный установщик…", -1));
        EXECUTOR.execute(() -> {
            try {
                Intent callback = new Intent(app, UpdateInstallReceiver.class).setAction(app.getPackageName() + ".UPDATE_INSTALL_RESULT");
                PendingIntent result = PendingIntent.getBroadcast(app, 4104, callback, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                item.updater.installAfterUserConfirmation(app, item.release, item.apk, result.getIntentSender());
                // Keep the private verified APK until the installer reports success, allowing cancellation retry.
                MAIN.post(() -> { if (pending == item) publish(releaseState(item.release, false, true, "Подтвердите установку в системном окне Android", 100)); });
            } catch (Exception error) {
                MAIN.post(() -> { if (pending == item) publish(releaseState(item.release, false, true, "Не удалось начать установку. Повторите попытку.", 100)); });
            } finally { BUSY.set(false); }
        });
    }
    private static void persist(Context context, PendingDownload item) throws Exception {
        JSONObject json = new JSONObject().put("tag", item.release.tag).put("notes", item.release.notes)
                .put("page", item.release.pageUrl).put("url", item.release.apkUrl).put("sha", item.release.sha256)
                .put("size", item.release.size).put("file", item.apk.getName());
        preferences(context).edit().putString("pending_apk", json.toString()).commit();
    }
    private static PendingDownload restore(Context context, GitHubUpdater updater) {
        String saved = preferences(context).getString("pending_apk", "");
        if (saved.isEmpty()) return null;
        File apk = null;
        try {
            JSONObject json = new JSONObject(saved);
            GitHubUpdater.Release release = new GitHubUpdater.Release(json.getString("tag"), json.optString("notes"), json.getString("page"), json.getString("url"), json.getString("sha"), json.getLong("size"));
            String name = json.getString("file");
            if (!name.matches("release-[A-Za-z0-9-]+\\.apk")) throw new IllegalArgumentException("Invalid private APK name");
            apk = new File(new File(context.getFilesDir(), "updates"), name);
            return new PendingDownload(updater, release, apk, updater.verify(context, release, apk));
        } catch (Exception invalid) {
            deletePrivate(context, apk);
            preferences(context).edit().remove("pending_apk").putBoolean("resume_install", false).apply(); return null;
        }
    }
    private static void deletePrivate(Context context, File file) {
        if (file == null) return;
        try {
            File directory = new File(context.getFilesDir(), "updates").getCanonicalFile();
            if (directory.equals(file.getCanonicalFile().getParentFile())) file.delete();
        } catch (java.io.IOException ignored) { /* Never delete a file with an unverified location. */ }
    }
    static void installationResult(Context context, boolean success, String message) {
        preferences(context).edit().putString("status", message).apply();
        if (success) {
            PendingDownload item = pending;
            if (item != null) {
                deletePrivate(context, item.apk);
            } else {
                // The installer may restart the app before delivering STATUS_SUCCESS.
                String saved = preferences(context).getString("pending_apk", "");
                try {
                    String name = new JSONObject(saved).getString("file");
                    if (name.matches("release-[A-Za-z0-9-]+\\.apk"))
                        deletePrivate(context, new File(new File(context.getFilesDir(), "updates"), name));
                } catch (Exception ignored) { /* No valid saved download to remove. */ }
            }
            pending = null; candidate = null;
            preferences(context).edit().remove("pending_apk").putBoolean("resume_install", false).apply();
            publish(new UiState(false, false, false, false, "", "", message, -1));
        } else if (candidate != null) publish(releaseState(candidate, false, pending != null, message, pending != null ? 100 : -1));
    }
    static boolean offerSystemConfirmation(Intent confirmation) {
        Activity activity = foreground.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) { pendingSystemConfirmation = confirmation; return false; }
        MAIN.post(() -> {
            Activity current = foreground.get();
            if (current == null) pendingSystemConfirmation = confirmation;
            else current.startActivity(confirmation);
        });
        return true;
    }
    static void recordCandidate(Context context, GitHubUpdater.Release release) {
        preferences(context).edit().putString("status", "Доступен релиз " + release.tag).apply();
    }
    public static String status(Context context) { return state.message.isEmpty() ? preferences(context).getString("status", "Обновления проверяются при открытии приложения.") : state.message; }
    public static void showManualCheck(Activity activity) { checkManual(activity); }
    public static void showPendingInstallation(Activity activity) { attach(activity, ignored -> {}); }
    public static void onPause(Activity activity) { detach(activity); }
    public static void scheduleConfiguredChecks(Context context) {
        if (configuredUpdater(context) == null) return;
        Constraints constraints = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        PeriodicWorkRequest work = new PeriodicWorkRequest.Builder(ReleaseCheckWorker.class, 1, TimeUnit.DAYS).setConstraints(constraints).setInitialDelay(1, TimeUnit.DAYS).build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("github-release-check", ExistingPeriodicWorkPolicy.KEEP, work);
    }
    public static void explainAndRequestNotifications(Activity activity) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity).setTitle("Уведомления об обновлениях").setMessage("Разрешить уведомления о новых версиях? Проверка при открытии приложения работает и без них.")
                .setNegativeButton("Не сейчас", null).setPositiveButton("Разрешить", (dialog, which) -> {
                    ensureChannel(activity);
                    if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                        activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 4103);
                    else if (!notificationsAllowed(activity)) activity.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName()));
                }).show();
    }
    static boolean notificationsAllowed(Context context) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        return manager.areNotificationsEnabled() && (channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
    }
    static void ensureChannel(Context context) { context.getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL, "Обновления GitHub", NotificationManager.IMPORTANCE_DEFAULT)); }
    private static final class PendingDownload {
        final GitHubUpdater updater; final GitHubUpdater.Release release; final File apk; final long version;
        PendingDownload(GitHubUpdater updater, GitHubUpdater.Release release, File apk, long version) { this.updater = updater; this.release = release; this.apk = apk; this.version = version; }
    }
}
