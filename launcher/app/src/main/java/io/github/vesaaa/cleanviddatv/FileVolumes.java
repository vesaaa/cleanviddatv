package io.github.vesaaa.cleanviddatv;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** The TV's mounted storage volumes (internal and USB) and the path rules FilePickerActivity and StorageProvider share. */
final class FileVolumes {

    static final class Volume {
        final File dir;
        final String title;

        Volume(File dir, String title) {
            this.dir = dir;
            this.title = title;
        }
    }

    /** Folders first, then by name, ignoring case. */
    static final Comparator<File> ORDER = (a, b) -> {
        boolean da = a.isDirectory(), db = b.isDirectory();
        if (da != db) return da ? -1 : 1;
        return a.getName().compareToIgnoreCase(b.getName());
    };

    private FileVolumes() {
    }

    /** Mounted volumes, internal storage first; USB drives under the name Android gives them. */
    static List<Volume> list(Context ctx) {
        List<Volume> out = new ArrayList<>();
        StorageManager sm = ctx.getSystemService(StorageManager.class);
        if (sm != null) {
            for (StorageVolume v : sm.getStorageVolumes()) {
                String state = v.getState();
                if (!Environment.MEDIA_MOUNTED.equals(state) && !Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) continue;
                File dir = directory(v);
                if (dir == null || !dir.isDirectory()) continue;
                String title = v.isPrimary() ? ctx.getString(R.string.files_internal) : v.getDescription(ctx);
                if (v.isPrimary()) out.add(0, new Volume(dir, title));
                else out.add(new Volume(dir, title));
            }
        }
        if (out.isEmpty()) {
            out.add(new Volume(Environment.getExternalStorageDirectory(), ctx.getString(R.string.files_internal)));
        }
        return out;
    }

    private static File directory(StorageVolume v) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return v.getDirectory();
        try {
            // Android 8 to 10 keep the path behind a hidden getter.
            return (File) StorageVolume.class.getMethod("getPathFile").invoke(v);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /** The file for a document id (an absolute path), or null when it lies outside every volume. */
    static File resolve(Context ctx, String id) {
        if (id == null || !id.startsWith("/")) return null;
        try {
            String path = new File(id).getCanonicalPath();
            for (Volume v : list(ctx)) {
                if (isInside(path, v.dir.getCanonicalPath())) return new File(path);
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    static boolean isVolumeRoot(Context ctx, File f) {
        try {
            String path = f.getCanonicalPath();
            for (Volume v : list(ctx)) if (path.equals(v.dir.getCanonicalPath())) return true;
        } catch (IOException e) {
            return true;   // unsure: treat it as a root, which is never deleted or renamed
        }
        return false;
    }

    /** Whether path is parent or lies under it; both are normalised absolute paths. */
    static boolean isInside(String path, String parent) {
        if (path == null || parent == null) return false;
        String p = parent.endsWith("/") && parent.length() > 1 ? parent.substring(0, parent.length() - 1) : parent;
        return path.equals(p) || path.startsWith(p.equals("/") ? "/" : p + "/");
    }

    /** A file name an app asked for, or null when it could reach outside its folder or is empty. */
    static String safeName(String name) {
        if (name == null) return null;
        String n = name.trim();
        if (n.isEmpty() || n.equals(".") || n.equals("..") || n.indexOf('/') >= 0 || n.indexOf('\0') >= 0) return null;
        return n;
    }

    /** "name (n).ext" for a file, "name (n)" for a folder, when the name is taken. */
    static String numbered(String name, int n, boolean folder) {
        int dot = folder ? -1 : name.lastIndexOf('.');
        if (dot <= 0) return name + " (" + n + ")";
        return name.substring(0, dot) + " (" + n + ")" + name.substring(dot);
    }

    /** Deletes a file, or a folder with everything in it; a symbolic link goes, never what it points to. */
    static boolean deleteTree(File f) {
        if (f.isDirectory() && !Files.isSymbolicLink(f.toPath())) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) if (!deleteTree(c)) return false;
        }
        return f.delete();
    }

    /** Whether a file name matches one of the MIME types an app asked for: any type, a family like image, or one type. */
    static boolean matches(String fileName, List<String> mimeTypes) {
        if (mimeTypes.isEmpty()) return true;
        String type = StorageProvider.mimeTypeOf(fileName).toLowerCase(Locale.ROOT);
        for (String want : mimeTypes) {
            String w = want == null ? "" : want.trim().toLowerCase(Locale.ROOT);
            if (w.isEmpty() || w.equals("*/*") || w.equals("*")) return true;
            if (w.endsWith("/*") && type.startsWith(w.substring(0, w.length() - 1))) return true;
            if (w.equals(type)) return true;
        }
        return false;
    }
}
