package com.example.nothingwidgets.update;

import android.content.Context;
import android.content.IntentSender;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URI;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Blocking, explicit operations. No default source, background scheduling or automatic download. */
public final class GitHubUpdater {
    public static final class NoPublishedReleaseException extends IOException {
        public NoPublishedReleaseException() { super("No stable release published"); }
    }
    private static final long MAX_APK = 150L * 1024 * 1024;
    private final String owner, repository, assetName;

    /** The application must explicitly supply its trusted release repository and exact APK name. */
    public GitHubUpdater(String owner, String repository, String assetName) {
        if (owner == null || !owner.matches("[A-Za-z0-9][A-Za-z0-9-]{0,38}") ||
                repository == null || !repository.matches("[A-Za-z0-9_.-]{1,100}") ||
                repository.equals(".") || repository.equals("..") ||
                assetName == null || !assetName.matches("[A-Za-z0-9_.-]+\\.apk"))
            throw new IllegalArgumentException("Configure a trusted GitHub repository and exact APK asset name");
        this.owner = owner; this.repository = repository; this.assetName = assetName;
    }

    public static final class Release {
        public final String tag, notes, pageUrl, apkUrl, sha256;
        public final long size;
        Release(String tag, String notes, String pageUrl, String apkUrl, String sha256, long size) {
            this.tag = tag; this.notes = notes; this.pageUrl = pageUrl; this.apkUrl = apkUrl;
            this.sha256 = sha256; this.size = size;
        }
    }

    /** Reads metadata only. A tag is a display label, not a trusted versionCode comparison. */
    public Release checkLatest() throws Exception {
        worker();
        HttpsURLConnection connection = open("https://api.github.com/repos/" + owner + "/" + repository + "/releases/latest", true);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream input = connection.getInputStream()) { copy(input, bytes, 1024 * 1024, null); }
        finally { connection.disconnect(); }
        JSONObject json = new JSONObject(bytes.toString("UTF-8"));
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) throw new IOException("Stable published release required");
        JSONArray assets = json.getJSONArray("assets");
        JSONObject selected = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject candidate = assets.getJSONObject(i);
            if (assetName.equals(candidate.optString("name"))) {
                if (selected != null) throw new IOException("Duplicate APK asset");
                selected = candidate;
            }
        }
        if (selected == null) throw new IOException("Configured APK asset missing");
        String digest = selected.optString("digest");
        if (!digest.matches("sha256:[0-9a-fA-F]{64}")) throw new IOException("Release must provide SHA-256 asset digest");
        long size = selected.getLong("size");
        if (size <= 0 || size > MAX_APK) throw new IOException("Unsupported APK size");
        String apk = selected.getString("browser_download_url"), page = json.getString("html_url");
        repositoryUrl(apk, "/releases/download/");
        repositoryUrl(page, "/releases/tag/");
        return new Release(json.getString("tag_name"), json.optString("body", ""), page, apk, digest.substring(7).toLowerCase(Locale.ROOT), size);
    }

    /** Only invoke after a user presses Download; checkLatest never calls this method. */
    public interface ProgressListener { void onProgress(long downloaded, long total); }

    public File downloadAfterUserRequest(Context context, Release release) throws Exception {
        return downloadAfterUserRequest(context, release, null);
    }

    public File downloadAfterUserRequest(Context context, Release release, ProgressListener progress) throws Exception {
        worker();
        repositoryUrl(release.apkUrl, "/releases/download/");
        File directory = new File(context.getFilesDir(), "updates");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create private update directory");
        File file = File.createTempFile("release-", ".apk", directory);
        boolean success = false;
        try {
            HttpsURLConnection connection = open(release.apkUrl, false);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long count;
            try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(file)) {
                count = copy(input, output, release.size, digest, progress);
                output.getFD().sync();
            } finally { connection.disconnect(); }
            if (count != release.size || !hex(digest.digest()).equals(release.sha256)) throw new IOException("APK size or digest mismatch");
            verify(context, release, file);
            success = true;
            return file;
        } finally { if (!success) file.delete(); }
    }

    /** Actual versionCode, same package, same current signing certificates. Key rotation is rejected.
     * Android's package installer performs authoritative signature verification before installation. */
    public long verify(Context context, Release release, File apk) throws Exception {
        worker();
        File directory = new File(context.getFilesDir(), "updates").getCanonicalFile();
        if (!apk.isFile() || !directory.equals(apk.getCanonicalFile().getParentFile())) throw new IOException("APK must be app-private");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long count;
        try (InputStream input = new FileInputStream(apk)) { count = copy(input, null, MAX_APK, digest); }
        if (count != release.size || !hex(digest.digest()).equals(release.sha256)) throw new IOException("APK integrity check failed");
        PackageManager manager = context.getPackageManager();
        PackageInfo current = manager.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        PackageInfo next = manager.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (next == null || !context.getPackageName().equals(next.packageName)) throw new IOException("APK package mismatch");
        if (next.getLongVersionCode() <= current.getLongVersionCode()) throw new IOException("APK is not newer than installed version");
        if (current.signingInfo == null || next.signingInfo == null || !signers(current).equals(signers(next)))
            throw new IOException("APK signing certificate mismatch");
        return next.getLongVersionCode();
    }

    /** Call after separate Install confirmation. Requires REQUEST_INSTALL_PACKAGES + user allowance.
     * statusReceiver must handle STATUS_PENDING_USER_ACTION by opening Intent.EXTRA_INTENT for
     * the foreground user, and report final SUCCESS/FAILURE. Never interpret commit as success.
     * With target 35+, use a mutable explicit PendingIntent for this callback. */
    public void installAfterUserConfirmation(Context context, Release release, File apk, IntentSender statusReceiver) throws Exception {
        worker();
        if (statusReceiver == null) throw new IllegalArgumentException("Installation status receiver required");
        if (!context.getPackageManager().canRequestPackageInstalls()) throw new SecurityException("User must allow package installation in Settings");
        verify(context, release, apk);
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        params.setSize(release.size);
        params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
        int id = installer.createSession(params);
        boolean committed = false;
        try (PackageInstaller.Session session = installer.openSession(id)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long count;
            try (InputStream input = new FileInputStream(apk); OutputStream output = session.openWrite("base.apk", 0, release.size)) {
                count = copy(input, output, release.size, digest);
                session.fsync(output);
            }
            // Hash the bytes actually staged, so replacement of the source cannot alter installed bytes.
            if (count != release.size || !hex(digest.digest()).equals(release.sha256)) throw new IOException("APK changed before installation");
            session.commit(statusReceiver);
            committed = true;
        } finally { if (!committed) installer.abandonSession(id); }
    }

    private static Set<String> signers(PackageInfo info) throws Exception {
        Set<String> result = new HashSet<>();
        Signature[] signatures = info.signingInfo.getApkContentsSigners();
        if (signatures == null || signatures.length == 0) throw new IOException("Missing signing certificates");
        for (Signature signature : signatures) result.add(hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())));
        return result;
    }

    private void repositoryUrl(String value, String section) throws IOException {
        URI uri = https(value);
        if (!"github.com".equals(uri.getHost()) || !uri.getPath().startsWith("/" + owner + "/" + repository + section))
            throw new IOException("URL does not belong to configured release repository");
    }

    static URI https(String value) throws IOException {
        try {
            URI uri = new URI(value);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null ||
                    (uri.getPort() != -1 && uri.getPort() != 443) || uri.getFragment() != null)
                throw new IOException("Only HTTPS URLs without credentials or fragments are allowed");
            return uri;
        } catch (java.net.URISyntaxException e) { throw new IOException("Invalid update URL", e); }
    }

    private static HttpsURLConnection open(String address, boolean metadata) throws IOException {
        for (int redirects = 0; redirects <= 5; redirects++) {
            URI uri = https(address);
            String host = uri.getHost();
            boolean allowed = metadata ? "api.github.com".equals(host) : "github.com".equals(host) ||
                    "release-assets.githubusercontent.com".equals(host) || "objects.githubusercontent.com".equals(host);
            if (!allowed) throw new IOException("Untrusted update host");
            HttpsURLConnection connection = (HttpsURLConnection) uri.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("User-Agent", "NothingWidgets-Updater");
            connection.setRequestProperty("Accept", metadata ? "application/vnd.github+json" : "application/octet-stream");
            int status;
            try { status = connection.getResponseCode(); }
            catch (IOException e) { connection.disconnect(); throw e; }
            if (status == 200) return connection;
            String location = connection.getHeaderField("Location");
            connection.disconnect();
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                if (location == null) throw new IOException("Missing redirect address");
                address = uri.resolve(location).toString();
            } else if (metadata && status == 404) throw new NoPublishedReleaseException();
            else throw new IOException("GitHub HTTP error " + status);
        }
        throw new IOException("Too many update redirects");
    }

    private static long copy(InputStream input, OutputStream output, long limit, MessageDigest digest) throws IOException {
        return copy(input, output, limit, digest, null);
    }

    private static long copy(InputStream input, OutputStream output, long limit, MessageDigest digest, ProgressListener progress) throws IOException {
        byte[] buffer = new byte[16384];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Update operation cancelled");
            total += read;
            if (total > limit) throw new IOException("Response exceeds size limit");
            if (output != null) output.write(buffer, 0, read);
            if (digest != null) digest.update(buffer, 0, read);
            if (progress != null) progress.onProgress(total, limit);
        }
        return total;
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    private static void worker() {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Use a worker thread for update operations");
    }
}
