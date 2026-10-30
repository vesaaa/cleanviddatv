package io.github.vesaaa.cleanviddatv;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;

import java.io.InputStream;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The apps that publish a top shelf (docs/top-shelf.md) and reading them: the items, their images and the intent a
 * card opens. Everything here that touches a provider runs off the main thread, and any failure is an empty shelf.
 */
final class ShelfSource {

    static final String ACTION = "io.github.vesaaa.cleanviddatv.action.SHELF";
    static final String TITLE_META = "io.github.vesaaa.cleanviddatv.shelf.title";
    /** Largest image side the cards decode to: a logo is drawn at most ~64 dp, a wide picture at the card's size. */
    private static final int IMAGE_MAX_PX = 512;
    private static final long IMAGE_MAX_BYTES = 1024 * 1024;

    private ShelfSource() { }

    /**
     * Package to its shelf provider, for every enabled, exported provider that declares the action; and the 飞牛影视
     * app, which publishes none, gets the launcher's own (FeiniuShelfProvider) once an account is signed in.
     */
    static Map<String, ProviderInfo> providers(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Map<String, ProviderInfo> out = new HashMap<>();
        List<ResolveInfo> found;
        try {
            found = pm.queryIntentContentProviders(new Intent(ACTION), PackageManager.GET_META_DATA);
        } catch (RuntimeException e) {
            return out;
        }
        for (ResolveInfo r : found) {
            ProviderInfo p = r.providerInfo;
            if (p == null || !p.exported || !p.enabled || p.authority == null) continue;
            // One authority is enough; a provider may list several separated by ';'.
            if (!out.containsKey(p.packageName)) out.put(p.packageName, p);
        }
        if (Feiniu.signedIn(ctx)) {
            String feiniu = FeiniuShelfProvider.appPackage(ctx);
            ProviderInfo own = feiniu == null || out.containsKey(feiniu) ? null
                    : pm.resolveContentProvider(FeiniuShelfProvider.authority(ctx), 0);
            if (own != null) out.put(feiniu, own);
        }
        return out;
    }

    static String authority(ProviderInfo p) {
        int semi = p.authority.indexOf(';');
        return semi < 0 ? p.authority : p.authority.substring(0, semi);
    }

    /** The provider's own title for its shelf (meta-data), or null. */
    static String title(PackageManager pm, ProviderInfo p) {
        Bundle meta = p.metaData;
        if (meta == null || !meta.containsKey(TITLE_META)) return null;
        Object value = meta.get(TITLE_META);
        if (value instanceof Integer) {
            try {
                return pm.getResourcesForApplication(p.applicationInfo).getString((Integer) value);
            } catch (Exception e) {
                return null;
            }
        }
        return value == null ? null : String.valueOf(value);
    }

    static Uri itemsUri(String authority) {
        return Uri.parse("content://" + authority + "/items");
    }

    /** The usable rows, at most ShelfItem.MAX, in the provider's order. Blocking; empty on any failure. */
    static List<ShelfItem> query(ContentResolver cr, String authority) {
        List<ShelfItem> items = new ArrayList<>();
        try (Cursor c = cr.query(itemsUri(authority), null, null, null, null)) {
            if (c == null) return items;
            while (c.moveToNext() && items.size() < ShelfItem.MAX) {
                String image = string(c, "image_uri");
                String preview = string(c, "preview_image_uri");
                ShelfItem item = new ShelfItem(longValue(c, "_id", items.size()), string(c, "title"),
                        string(c, "subtitle"), ShelfItem.ownImage(image, authority) ? image : null,
                        "wide".equals(string(c, "image_aspect")), (int) longValue(c, "progress", -1),
                        longValue(c, "start_ms", 0), longValue(c, "end_ms", 0), string(c, "badge"),
                        string(c, "section"), string(c, "intent_uri"),
                        ShelfItem.ownImage(preview, authority) ? preview : null);
                String backdrop = string(c, "backdrop_uri"), logo = string(c, "logo_uri");
                item.backdropUri = ShelfItem.ownImage(backdrop, authority) ? backdrop : null;
                item.logoUri = ShelfItem.ownImage(logo, authority) ? logo : null;
                String overview = string(c, "overview");
                item.overview = overview == null || overview.trim().isEmpty() ? null : overview.trim();
                if (item.usable()) items.add(item);
            }
        } catch (RuntimeException e) {
            // A provider that throws, is gone, or refuses us: no shelf.
            items.clear();
        }
        return items;
    }

    private static String string(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        return i < 0 || c.isNull(i) ? null : c.getString(i);
    }

    private static long longValue(Cursor c, String column, long fallback) {
        int i = c.getColumnIndex(column);
        if (i < 0 || c.isNull(i)) return fallback;
        try {
            return c.getLong(i);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** One card image, decoded small. Blocking; null on any failure or an image over 1 MB. */
    static Bitmap image(ContentResolver cr, String uri) {
        return image(cr, uri, IMAGE_MAX_PX);
    }

    /** One image, decoded to about maxPx on its longer side. Blocking; null on any failure or an image over 1 MB. */
    static Bitmap image(ContentResolver cr, String uri, int maxPx) {
        if (uri == null) return null;
        try {
            Uri u = Uri.parse(uri);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = cr.openInputStream(u)) {
                if (in == null) return null;
                BitmapFactory.decodeStream(new LimitedStream(in, IMAGE_MAX_BYTES), null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            try (InputStream in = cr.openInputStream(u)) {
                if (in == null) return null;
                return BitmapFactory.decodeStream(new LimitedStream(in, IMAGE_MAX_BYTES), null, options);
            }
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    /**
     * The intent a card opens, held to the app that published it: its package is forced to that app's, and nothing that
     * would hand the app our own files (URI grants, clip data, a selector) survives. Null when it cannot be parsed or
     * names another app's component.
     */
    static Intent intent(String intentUri, String pkg) {
        Intent intent;
        try {
            intent = Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME);
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
        ComponentName component = intent.getComponent();
        if (component != null && !pkg.equals(component.getPackageName())) return null;
        intent.setSelector(null);
        intent.setClipData(null);
        intent.setPackage(pkg);
        intent.setFlags((intent.getFlags() & ~(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)) | Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    /** Stops reading after a limit, so a provider cannot make the launcher decode an endless stream. */
    private static final class LimitedStream extends java.io.FilterInputStream {
        private long left;

        LimitedStream(InputStream in, long limit) {
            super(in);
            left = limit;
        }

        @Override
        public int read() throws java.io.IOException {
            if (left <= 0) return -1;
            int b = super.read();
            if (b >= 0) left--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws java.io.IOException {
            if (left <= 0) return -1;
            int n = super.read(b, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }
    }
}
