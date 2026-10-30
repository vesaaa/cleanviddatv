package io.github.vesaaa.cleanviddatv;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Base64;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * The top shelf (docs/top-shelf.md) of the 飞牛影视 TV app, written by the launcher itself since that app publishes
 * none: the signed-in account's continue-watching list and the newest additions, read from the NAS. Not exported:
 * ShelfSource hands it to TopShelf as the 飞牛影视 app's provider, so its cards open that app.
 */
public final class FeiniuShelfProvider extends ContentProvider {

    private static final String[] COLUMNS = {"_id", "title", "subtitle", "image_uri", "image_aspect", "progress",
            "badge", "section", "intent_uri"};
    /** The server's lists are read again after this long; TopShelf adds its own 30 s cache on top. */
    private static final long FRESH_MS = 60_000L;
    /** Wide enough to fill the screen behind the row (TopShelf's backdrop); the cards decode it smaller. */
    private static final int IMAGE_WIDTH = 1280, IMAGE_FILES = 80, FETCH_THREADS = 6;

    private final Object lock = new Object();
    private List<Object[]> rows;
    private long rowsAt;
    private String rowsFor;
    /** guid to a wide backdrop path ("" when the item has none), so a portrait poster is swapped only once. */
    private final Map<String, String> backdrops = new HashMap<>();

    static String authority(Context ctx) {
        return ctx.getPackageName() + ".feiniu";
    }

    /**
     * The 飞牛影视 TV app on this TV: an app with a TV or phone launcher entry whose name has 飞牛 in it, the first by
     * package name. Null when none is installed.
     */
    static String appPackage(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        List<String> found = new ArrayList<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            List<ResolveInfo> list;
            try {
                list = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0);
            } catch (RuntimeException e) {
                continue;
            }
            for (ResolveInfo r : list) {
                String pkg = r.activityInfo.packageName;
                if (pkg.equals(ctx.getPackageName()) || found.contains(pkg)) continue;
                CharSequence label = r.loadLabel(pm);
                if (label != null && label.toString().contains("飞牛")) found.add(pkg);
            }
        }
        if (found.isEmpty()) return null;
        java.util.Collections.sort(found);
        return found.get(0);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(COLUMNS);
        Context ctx = getContext();
        if (ctx == null || !"/items".equals(uri.getPath())) return cursor;
        for (Object[] row : rows(ctx)) cursor.addRow(row);
        return cursor;
    }

    private List<Object[]> rows(Context ctx) {
        String account = Feiniu.base(ctx) + "|" + Feiniu.user(ctx);
        synchronized (lock) {
            if (rows != null && account.equals(rowsFor) && System.currentTimeMillis() - rowsAt < FRESH_MS) return rows;
        }
        List<Object[]> fresh = load(ctx);
        synchronized (lock) {
            // A failed read keeps what was shown last rather than emptying the shelf.
            if (fresh != null || !account.equals(rowsFor)) {
                rows = fresh == null ? new ArrayList<>() : fresh;
                rowsFor = account;
            }
            rowsAt = System.currentTimeMillis();
            return rows;
        }
    }

    /** The cards from the server, or null when it cannot be reached. */
    private List<Object[]> load(Context ctx) {
        Feiniu.Client client = Feiniu.client(ctx);
        String app = appPackage(ctx);
        if (client == null || app == null) return new ArrayList<>();
        Intent launch = ctx.getPackageManager().getLeanbackLaunchIntentForPackage(app);
        if (launch == null) launch = ctx.getPackageManager().getLaunchIntentForPackage(app);
        if (launch == null) return new ArrayList<>();
        String intentUri = launch.toUri(Intent.URI_INTENT_SCHEME);
        List<FeiniuEntry> playing, added;
        try {
            try {
                playing = client.playing();
            } catch (Feiniu.ApiException e) {
                if (!e.authFailed() || !Feiniu.renew(ctx, client)) throw e;
                playing = client.playing();
            }
            added = client.recent(ShelfItem.MAX);
        } catch (IOException e) {
            return null;
        }
        // The backdrops and the pictures are fetched side by side: one after another, a first shelf of twelve cards
        // took ten seconds and more on the TV.
        List<FeiniuEntry.Pick> picks = FeiniuEntry.cards(playing, added);
        String[] images = new String[picks.size()];
        ExecutorService pool = Executors.newFixedThreadPool(FETCH_THREADS, r -> new Thread(r, "feiniu-shelf"));
        try {
            List<Future<?>> jobs = new ArrayList<>();
            for (int i = 0; i < picks.size(); i++) {
                final int index = i;
                FeiniuEntry e = picks.get(i).entry;
                jobs.add(pool.submit(() -> {
                    String image = e.poster.isEmpty() ? null : e.poster;
                    if (!e.widePoster()) {
                        String wide = backdrop(client, e.isEpisode() ? null : e.guid);
                        if (wide != null) image = wide;
                    }
                    images[index] = image;
                    if (image != null) cached(ctx, image);
                    return null;
                }));
            }
            for (Future<?> job : jobs) {
                try {
                    job.get(20, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // that card shows without its picture, or fetches it when TopShelf opens it
                }
            }
        } finally {
            pool.shutdownNow();
        }
        List<Object[]> out = new ArrayList<>();
        for (int i = 0; i < picks.size(); i++) {
            FeiniuEntry.Pick pick = picks.get(i);
            FeiniuEntry e = pick.entry;
            out.add(new Object[]{e.rowId(), e.showTitle(), pick.playing ? e.playingSubtitle() : e.addedSubtitle(),
                    images[i] == null ? null : imageUri(ctx, images[i]), "wide", pick.playing ? e.progress() : -1,
                    pick.playing ? null : "新增", pick.playing ? FeiniuEntry.SECTION_PLAYING : FeiniuEntry.SECTION_ADDED,
                    intentUri});
        }
        return out;
    }

    private String backdrop(Feiniu.Client client, String guid) {
        if (guid == null || guid.isEmpty()) return null;
        synchronized (lock) {
            if (backdrops.containsKey(guid)) {
                String b = backdrops.get(guid);
                return b == null || b.isEmpty() ? null : b;
            }
        }
        String b;
        try {
            b = client.backdrop(guid);
        } catch (IOException e) {
            return null;     // try again next time
        }
        synchronized (lock) {
            backdrops.put(guid, b == null ? "" : b);
        }
        return b;
    }

    private static String imageUri(Context ctx, String serverPath) {
        String id = Base64.encodeToString(serverPath.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        return "content://" + authority(ctx) + "/image/" + id;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        Context ctx = getContext();
        List<String> segments = uri.getPathSegments();
        if (ctx == null || !"r".equals(mode) || segments.size() != 2 || !"image".equals(segments.get(0))) {
            throw new FileNotFoundException(uri.toString());
        }
        String serverPath;
        try {
            serverPath = new String(Base64.decode(segments.get(1), Base64.URL_SAFE), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new FileNotFoundException(uri.toString());
        }
        if (!serverPath.startsWith("/") || serverPath.contains("..") || serverPath.contains("?")) {
            throw new FileNotFoundException(uri.toString());
        }
        return ParcelFileDescriptor.open(cached(ctx, serverPath), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** The picture's file in the cache, fetched from the server first when it is not there yet. */
    private static File cached(Context ctx, String serverPath) throws FileNotFoundException {
        File dir = new File(ctx.getCacheDir(), "feiniu");
        File file = new File(dir, Feiniu.md5(serverPath + "@" + IMAGE_WIDTH) + ".img");
        if (!file.isFile()) fetch(ctx, serverPath, dir, file);
        return file;
    }

    private static void fetch(Context ctx, String serverPath, File dir, File file) throws FileNotFoundException {
        Feiniu.Client client = Feiniu.client(ctx);
        if (client == null) throw new FileNotFoundException("signed out");
        byte[] bytes;
        try {
            try {
                bytes = client.image(serverPath, IMAGE_WIDTH);
            } catch (Feiniu.ApiException e) {
                if (!e.authFailed() || !Feiniu.renew(ctx, client)) throw e;
                bytes = client.image(serverPath, IMAGE_WIDTH);
            }
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
        if (!dir.isDirectory() && !dir.mkdirs()) throw new FileNotFoundException("no cache");
        trim(dir);
        File part = new File(dir, file.getName() + "." + Thread.currentThread().getId() + ".part");
        try (FileOutputStream out = new FileOutputStream(part)) {
            out.write(bytes);
        } catch (IOException e) {
            part.delete();
            throw new FileNotFoundException(e.getMessage());
        }
        if (!part.renameTo(file)) {
            part.delete();
            throw new FileNotFoundException("cache");
        }
    }

    /** Keeps the picture cache small: the oldest files go once there are too many. */
    private static void trim(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length < IMAGE_FILES) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i <= files.length - IMAGE_FILES; i++) files[i].delete();
    }

    /** Signed out or another account: the shelf and its pictures are dropped. */
    static void forget(Context ctx) {
        File[] files = new File(ctx.getCacheDir(), "feiniu").listFiles();
        if (files != null) for (File f : files) f.delete();
        ctx.getContentResolver().notifyChange(ShelfSource.itemsUri(authority(ctx)), null);
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
