package io.github.vesaaa.cleanviddatv;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The wallpapers that ship in the APK (assets/wallpapers): six landscape photos and three fields of soft light.
 * They are copied once per install or update to app storage, so WallpaperPlayer reads them as files like a theme's.
 * With no theme picture, Home shows the photos, one an hour; the on-TV wallpaper picker offers every one of them.
 */
final class BuiltinWallpapers {
    private static final String ASSETS = "wallpapers";
    /** Picker choices for a whole group, stored like a file name under Config.WALLPAPER_CHOICE_KEY. */
    static final String PHOTOS = "builtin:photos", ART = "builtin:art";
    static final int ROTATION_MINUTES = 60;
    /** The photos are bright daylight scenes: a light shade keeps the clock and the tiles readable over them. */
    static final int DIM = 25;
    /** The cinematic shelf's scrim over a bundled photo, in percent: enough for the title, not a dark band. */
    static final int SCRIM = 70;

    private BuiltinWallpapers() {
    }

    static File dir(Context ctx) {
        return new File(ctx.getNoBackupFilesDir(), "builtin_wallpapers");
    }

    /** Set by Home: called on the main thread when a copy finished that the pictures Home has did not cover yet. */
    static volatile Runnable onCopied;
    private static final AtomicBoolean copying = new AtomicBoolean();

    /**
     * Every bundled picture there is on disk now, sorted by name. Never copies on the caller's thread: Config.load runs
     * on the main thread while Home starts, so a first install or an update copies in the background and Home gets the
     * pictures already on disk (none on a first install, the previous version's after an update) until onCopied.
     */
    static List<File> all(Context ctx) {
        File dir = dir(ctx);
        List<File> now = WallpaperRotation.images(dir);
        String stamp;
        try {
            stamp = String.valueOf(ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).lastUpdateTime);
        } catch (Exception e) {
            return now;
        }
        if (!stamp.equals(readMarker(dir)) && copying.compareAndSet(false, true)) {
            Context app = ctx.getApplicationContext();
            boolean wasEmpty = now.isEmpty();
            new Thread(() -> {
                try {
                    copy(app.getAssets(), dir);
                    Files.write(new File(dir, MARKER).toPath(), stamp.getBytes(StandardCharsets.UTF_8));
                    Runnable done = onCopied;
                    if (wasEmpty && done != null) new Handler(Looper.getMainLooper()).post(done);
                } catch (Exception ignored) {
                    // Retried at the next start; Home keeps the soft backdrop meanwhile.
                } finally {
                    copying.set(false);
                }
            }, "builtin-wallpapers").start();
        }
        return now;
    }

    private static final String MARKER = ".installed";

    private static String readMarker(File dir) {
        try {
            return new String(Files.readAllBytes(new File(dir, MARKER).toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static void copy(AssetManager assets, File dir) throws Exception {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("cannot create " + dir);
        String[] names = assets.list(ASSETS);
        if (names == null) return;
        byte[] buffer = new byte[64 * 1024];
        for (String name : names) {
            File target = new File(dir, name);
            File partial = new File(dir, name + ".part");
            try (InputStream in = assets.open(ASSETS + "/" + name); OutputStream out = new FileOutputStream(partial)) {
                for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
            }
            if (!partial.renameTo(target)) throw new java.io.IOException("cannot write " + target);
        }
    }

    static boolean isGroup(String choice) {
        return PHOTOS.equals(choice) || ART.equals(choice);
    }

    /** The pictures of one group (PHOTOS or ART). */
    static List<File> group(List<File> all, String group) {
        String prefix = ART.equals(group) ? "builtin-art-" : "builtin-photo-";
        List<File> out = new ArrayList<>();
        for (File f : all) if (f.getName().startsWith(prefix)) out.add(f);
        return out;
    }

    /** Whether every picture is a bundled one, so the bundled defaults (rotation, shade) apply. */
    static boolean only(List<File> files, Context ctx) {
        if (files.isEmpty()) return false;
        File dir = dir(ctx);
        for (File f : files) if (!dir.equals(f.getParentFile())) return false;
        return true;
    }

    /** A short name for the picker: a photo's place without its credit, the whole caption for the rest. */
    static String label(Map<String, String> captions, File file) {
        String caption = captions.get(file.getName());
        if (caption == null) return file.getName();
        int credit = caption.indexOf(" · ");
        return credit < 0 || !file.getName().startsWith("builtin-photo-") ? caption : caption.substring(0, credit);
    }
}
