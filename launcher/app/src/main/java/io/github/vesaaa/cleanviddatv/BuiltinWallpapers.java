package io.github.vesaaa.cleanviddatv;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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

    /** Every bundled picture, sorted by name; empty when they could not be copied. */
    static List<File> all(Context ctx) {
        File dir = dir(ctx);
        try {
            String stamp = String.valueOf(ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).lastUpdateTime);
            File marker = new File(dir, ".installed");
            if (!marker.isFile() || !stamp.equals(new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8))) {
                copy(ctx.getAssets(), dir);
                Files.write(marker.toPath(), stamp.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            return Collections.emptyList();
        }
        return WallpaperRotation.images(dir);
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
