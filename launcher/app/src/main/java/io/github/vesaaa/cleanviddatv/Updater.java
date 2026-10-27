package io.github.vesaaa.cleanviddatv;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Online update: finds a newer release (the CDN first, GitHub when the CDN cannot be reached), downloads it the same
 * way, checks its SHA-256 and that it carries this app's own signing certificate, and hands it to the system
 * installer, which asks the viewer to confirm. Blocking calls: run them off the main thread.
 */
final class Updater {
    static final String PREFS = "updates";
    static final String KEY_BETA = "beta";
    /** A newer version found by the daily check, shown on Home's Settings button and in Launcher settings. */
    static final String KEY_AVAILABLE = "available";
    private static final String KEY_LAST_CHECK = "last_check";
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    interface Progress {
        void onProgress(int percent);
    }

    private Updater() {
    }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String currentVersion(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    /** The beta channel: on by default while a beta is installed, so a beta tester keeps getting the next one. */
    static boolean beta(Context ctx) {
        return prefs(ctx).getBoolean(KEY_BETA, Updates.isPrerelease(currentVersion(ctx)));
    }

    /** The newest release for the channel if it is newer than this app, else null. Throws when neither source answers. */
    static Updates.Release check(Context ctx) throws IOException {
        boolean beta = beta(ctx);
        Updates.Release release;
        try {
            release = Updates.parseManifest(fetchText(Updates.manifestUrl(beta)));
            if (release == null) throw new IOException("bad manifest");
        } catch (IOException cdn) {
            release = Updates.parseGithub(fetchText(Updates.GITHUB_RELEASES), beta);
        }
        boolean newer = release != null && Updates.compare(release.version, currentVersion(ctx)) > 0;
        prefs(ctx).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                .putString(KEY_AVAILABLE, newer ? release.version : "").apply();
        return newer ? release : null;
    }

    /** The daily check from Home: quiet, and at most once a day. Returns the newer version found, or "". */
    static String checkDaily(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String known = p.getString(KEY_AVAILABLE, "");
        if (!known.isEmpty() && Updates.compare(known, currentVersion(ctx)) <= 0) known = "";
        if (System.currentTimeMillis() - p.getLong(KEY_LAST_CHECK, 0) < DAY_MS) return known;
        try {
            Updates.Release r = check(ctx);
            return r == null ? "" : r.version;
        } catch (IOException e) {
            return known;
        }
    }

    /** Downloads and checks the release's APK; the CDN copy first, GitHub's when that fails. */
    static File download(Context ctx, Updates.Release release, Progress progress) throws IOException {
        String sum = release.sha256;
        if (sum.startsWith("url:")) sum = Updates.checksum(fetchText(sum.substring(4)));
        File dir = new File(ctx.getCacheDir(), "update");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();
        File apk = new File(dir, "CleanViddaTV-" + release.version + ".apk");
        IOException failure = null;
        for (String url : new String[]{release.apk, release.githubApk}) {
            if (url == null || url.isEmpty()) continue;
            try {
                fetchFile(url, apk, progress);
                failure = null;
                break;
            } catch (IOException e) {
                failure = e;
            }
        }
        if (failure != null) throw failure;
        if (!sum.isEmpty() && !sum.equals(sha256(apk))) throw new IOException(ctx.getString(R.string.update_err_checksum));
        if (!sameSigner(ctx, apk)) throw new IOException(ctx.getString(R.string.update_err_signature));
        return apk;
    }

    /** Hands the APK to the system installer; UpdateResultReceiver shows its confirmation. */
    static void install(Context ctx, File apk) throws IOException {
        PackageInstaller installer = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(ctx.getPackageName());
        params.setSize(apk.length());
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                copy(in, out, apk.length(), null);
                session.fsync(out);
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent result = PendingIntent.getBroadcast(ctx, id, new Intent(ctx, UpdateResultReceiver.class), flags);
            session.commit(result.getIntentSender());
        }
    }

    private static boolean sameSigner(Context ctx, File apk) {
        PackageManager pm = ctx.getPackageManager();
        try {
            PackageInfo archive, installed;
            if (Build.VERSION.SDK_INT >= 28) {
                archive = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
                installed = pm.getPackageInfo(ctx.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                return archive != null && ctx.getPackageName().equals(archive.packageName)
                        && archive.signingInfo != null && installed.signingInfo != null
                        && Arrays.equals(archive.signingInfo.getApkContentsSigners(), installed.signingInfo.getApkContentsSigners());
            }
            archive = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNATURES);
            installed = pm.getPackageInfo(ctx.getPackageName(), PackageManager.GET_SIGNATURES);
            Signature[] a = archive == null ? null : archive.signatures;
            return a != null && ctx.getPackageName().equals(archive.packageName) && Arrays.equals(a, installed.signatures);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        if (!url.startsWith("https://")) throw new IOException("not https: " + url);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "CleanViddaTV");
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw new IOException("HTTP " + code + " " + url);
        }
        return c;
    }

    private static String fetchText(String url) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            copy(in, out, -1, null);
            if (out.size() > 1024 * 1024) throw new IOException("too large: " + url);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    private static void fetchFile(String url, File target, Progress progress) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(target)) {
            copy(in, out, c.getContentLengthLong(), progress);
        } finally {
            c.disconnect();
        }
    }

    private static void copy(InputStream in, OutputStream out, long total, Progress progress) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long done = 0;
        int last = -1;
        for (int n; (n = in.read(buffer)) > 0; ) {
            out.write(buffer, 0, n);
            done += n;
            if (progress != null && total > 0) {
                int percent = (int) (done * 100 / total);
                if (percent != last) progress.onProgress(last = percent);
            }
        }
    }

    private static String sha256(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) > 0; ) md.update(buffer, 0, n);
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest()) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }
}
