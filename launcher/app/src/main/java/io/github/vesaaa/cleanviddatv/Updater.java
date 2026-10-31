package io.github.vesaaa.cleanviddatv;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;

import androidx.core.content.FileProvider;

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
 * way, checks its SHA-256 and that it carries this app's own signing certificate, and opens the system installer
 * on it, which asks the viewer to confirm. Blocking calls: run them off the main thread.
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

    /** The beta channel: off until the viewer turns it on in Launcher settings, whatever version is installed. */
    static boolean beta(Context ctx) {
        return prefs(ctx).getBoolean(KEY_BETA, false);
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

    /**
     * The system installer's own screen for the APK, opened by the settings screen itself. A PackageInstaller session
     * was tried first: on the 65V1N-X its commit sometimes never came back with the confirmation, so nothing appeared.
     * Null when this TV has no installer for it.
     */
    static Intent installIntent(Context ctx, File apk) {
        Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".updates", apk);
        PackageManager pm = ctx.getPackageManager();
        Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (install.resolveActivity(pm) != null) return install;
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return view.resolveActivity(pm) != null ? view : null;
    }

    /**
     * False only when the APK's certificates can be read and differ from this app's. Some TVs return no certificates
     * for a downloaded archive (signingInfo or signatures null, even for a good v2-signed APK): the checksum from the
     * release manifest has already matched, and the system installer refuses a different signer by itself.
     */
    private static boolean sameSigner(Context ctx, File apk) {
        PackageManager pm = ctx.getPackageManager();
        try {
            PackageInfo installed = pm.getPackageInfo(ctx.getPackageName(), PackageManager.GET_SIGNATURES);
            Signature[] own = installed.signatures;
            Signature[] archive = null;
            String archivePackage = null;
            if (Build.VERSION.SDK_INT >= 28) {
                PackageInfo a = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
                if (a != null) {
                    archivePackage = a.packageName;
                    if (a.signingInfo != null) archive = a.signingInfo.getApkContentsSigners();
                }
                PackageInfo mine = pm.getPackageInfo(ctx.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                if (mine.signingInfo != null) own = mine.signingInfo.getApkContentsSigners();
            }
            if (archive == null || archive.length == 0) {
                PackageInfo a = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNATURES);
                if (a != null) {
                    archivePackage = a.packageName;
                    archive = a.signatures;
                }
            }
            return signerMatches(ctx.getPackageName(), archivePackage, toBytes(archive), toBytes(own));
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** The decision behind sameSigner: a readable, different package or certificate set refuses; unreadable passes. */
    static boolean signerMatches(String ownPackage, String archivePackage, byte[][] archive, byte[][] own) {
        if (archivePackage != null && !ownPackage.equals(archivePackage)) return false;
        if (archive == null || archive.length == 0 || own == null || own.length == 0) return true;
        if (archive.length != own.length) return false;
        for (int i = 0; i < archive.length; i++) if (!Arrays.equals(archive[i], own[i])) return false;
        return true;
    }

    private static byte[][] toBytes(Signature[] sigs) {
        if (sigs == null) return null;
        byte[][] out = new byte[sigs.length][];
        for (int i = 0; i < sigs.length; i++) out[i] = sigs[i].toByteArray();
        return out;
    }

    static HttpURLConnection open(String url) throws IOException {
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

    static String fetchText(String url) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            copy(in, out, -1, null);
            if (out.size() > 1024 * 1024) throw new IOException("too large: " + url);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    static void fetchFile(String url, File target, Progress progress) throws IOException {
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

    static String sha256(File f) throws IOException {
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
