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
            "badge", "section", "intent_uri", "backdrop_uri", "logo_uri", "overview"};
    /** The server's lists are read again after this long; TopShelf adds its own 30 s cache on top. */
    private static final long FRESH_MS = 60_000L;
    /** Widths the server scales the pictures to: a card, the full-screen backdrop, a title logo. */
    private static final int CARD_WIDTH = 640, BACKDROP_WIDTH = 1920, LOGO_WIDTH = 480;
    private static final int IMAGE_FILES = 60, FETCH_THREADS = 6;
    private static final Feiniu.Art NO_ART = new Feiniu.Art(null, null, null, null);

    private final Object lock = new Object();
    private List<Object[]> rows;
    private long rowsAt;
    private String rowsFor;
    /** guid to what the server scraped for it (NO_ART when it has nothing), so each item is asked once. */
    private final Map<String, Feiniu.Art> arts = new HashMap<>();

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
        // The artwork and the pictures are fetched side by side: one after another, a first shelf took ten
        // seconds and more on the TV.
        List<FeiniuEntry.Pick> picks = FeiniuEntry.cards(playing, added);
        Object[][] out = new Object[picks.size()][];
        ExecutorService pool = Executors.newFixedThreadPool(FETCH_THREADS, r -> new Thread(r, "feiniu-shelf"));
        try {
            List<Future<?>> jobs = new ArrayList<>();
            for (int i = 0; i < picks.size(); i++) {
                final int index = i;
                FeiniuEntry.Pick pick = picks.get(i);
                jobs.add(pool.submit(() -> {
                    out[index] = row(ctx, client, pick, intentUri);
                    return null;
                }));
            }
            for (Future<?> job : jobs) {
                try {
                    job.get(20, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // that card is left out this time
                }
            }
        } finally {
            pool.shutdownNow();
        }
        List<Object[]> cards = new ArrayList<>();
        for (Object[] row : out) if (row != null) cards.add(row);
        return cards;
    }

    /**
     * One card. The card shows an episode's still (or a film's or series' backdrop); behind the row goes the scraped
     * backdrop of the film or of the episode's series, never a still blown up, with its logo and synopsis. A plain
     * video file has none of these and gets the glow instead. The pictures are fetched here, so the shelf opens at once.
     */
    private Object[] row(Context ctx, Feiniu.Client client, FeiniuEntry.Pick pick, String intentUri) {
        FeiniuEntry e = pick.entry;
        Feiniu.Art art = showArt(client, e);
        String card = e.isEpisode() && e.widePoster() ? e.poster : art.backdrop != null ? art.backdrop
                : e.poster.isEmpty() ? null : e.poster;
        String cardUri = picture(ctx, card, CARD_WIDTH);
        String backdropUri = picture(ctx, art.backdrop, BACKDROP_WIDTH);
        String logoUri = picture(ctx, art.logo, LOGO_WIDTH);
        return new Object[]{e.rowId(), e.showTitle(), pick.playing ? e.playingSubtitle() : e.addedSubtitle(),
                cardUri, "wide", pick.playing ? e.progress() : -1, pick.playing ? null : "新增",
                pick.playing ? FeiniuEntry.SECTION_PLAYING : FeiniuEntry.SECTION_ADDED, intentUri, backdropUri, logoUri,
                art.overview};
    }

    /** The art of a film or series as it is; an episode's is its series' (episode, then season, then series). */
    private Feiniu.Art showArt(Feiniu.Client client, FeiniuEntry e) {
        if ("Video".equalsIgnoreCase(e.type)) return NO_ART;
        if (!e.isEpisode()) return art(client, e.guid);
        Feiniu.Art season = art(client, e.parentGuid);
        return season.parent == null ? NO_ART : art(client, season.parent);
    }

    private Feiniu.Art art(Feiniu.Client client, String guid) {
        if (guid == null || guid.isEmpty()) return NO_ART;
        synchronized (lock) {
            Feiniu.Art known = arts.get(guid);
            if (known != null) return known;
        }
        Feiniu.Art art;
        try {
            art = client.art(guid);
        } catch (IOException e) {
            return NO_ART;     // asked again next time
        }
        synchronized (lock) {
            arts.put(guid, art);
        }
        return art;
    }

    /** The picture's content URI, fetched into the cache first; null without one or when it cannot be fetched. */
    private static String picture(Context ctx, String serverPath, int width) {
        if (serverPath == null) return null;
        try {
            cached(ctx, serverPath, width);
        } catch (FileNotFoundException e) {
            return null;
        }
        String id = Base64.encodeToString(serverPath.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        return "content://" + authority(ctx) + "/image/" + width + "/" + id;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        Context ctx = getContext();
        List<String> segments = uri.getPathSegments();
        if (ctx == null || !"r".equals(mode) || segments.size() != 3 || !"image".equals(segments.get(0))) {
            throw new FileNotFoundException(uri.toString());
        }
        int width;
        String serverPath;
        try {
            width = Integer.parseInt(segments.get(1));
            serverPath = new String(Base64.decode(segments.get(2), Base64.URL_SAFE), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new FileNotFoundException(uri.toString());
        }
        if (width != CARD_WIDTH && width != BACKDROP_WIDTH && width != LOGO_WIDTH || !serverPath.startsWith("/")
                || serverPath.contains("..") || serverPath.contains("?")) {
            throw new FileNotFoundException(uri.toString());
        }
        return ParcelFileDescriptor.open(cached(ctx, serverPath, width), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** The picture's file in the cache, fetched from the server first when it is not there yet. */
    private static File cached(Context ctx, String serverPath, int width) throws FileNotFoundException {
        File dir = new File(ctx.getCacheDir(), "feiniu");
        File file = new File(dir, Feiniu.md5(serverPath + "@" + width) + ".img");
        if (!file.isFile()) fetch(ctx, serverPath, width, dir, file);
        return file;
    }

    private static void fetch(Context ctx, String serverPath, int width, File dir, File file)
            throws FileNotFoundException {
        Feiniu.Client client = Feiniu.client(ctx);
        if (client == null) throw new FileNotFoundException("signed out");
        byte[] bytes;
        try {
            try {
                bytes = client.image(serverPath, width);
            } catch (Feiniu.ApiException e) {
                if (!e.authFailed() || !Feiniu.renew(ctx, client)) throw e;
                bytes = client.image(serverPath, width);
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
