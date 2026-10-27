package io.github.vesaaa.cleanviddatv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Wallpapers to download: the repository's wallpapers/ folder (index.json, files/, thumbs/; tools/wallpapers.py
 * builds it), read from the CDN first, then from GitHub. Downloads land in
 * app storage with a captions.txt, so the wallpaper picker and WallpaperPlayer treat them like the bundled ones.
 */
final class OnlineWallpapers {
    /** Where the folder is published, tried in this order. */
    static final String[] BASES = {
            "https://download.aikit.stream/cleanviddatv/wallpapers/",
            "https://raw.githubusercontent.com/vesaaa/cleanviddatv/main/wallpapers/",
    };
    /** Picker choice for the downloaded pictures as a rotating group, stored like a file name. */
    static final String GROUP = "downloaded:all";
    static final String PREFIX = "dl-";

    /** One picture in the index. Paths are relative to the base the index came from. */
    static final class Item {
        final String id, title, credit, license, file, thumb, sha256;

        Item(String id, String title, String credit, String license, String file, String thumb, String sha256) {
            this.id = id;
            this.title = title;
            this.credit = credit;
            this.license = license;
            this.file = file;
            this.thumb = thumb;
            this.sha256 = sha256;
        }

        String fileName() {
            return PREFIX + id + ".webp";
        }

        String caption() {
            return title + " · " + credit + "，" + license;
        }
    }

    /** The index and the base that answered, which the thumbnails and the downloads try first. */
    static final class Index {
        final String base;
        final List<Item> items;

        Index(String base, List<Item> items) {
            this.base = base;
            this.items = items;
        }
    }

    private OnlineWallpapers() {
    }

    /** Parses index.json; entries with a missing field or a path outside the folder are left out. */
    static List<Item> parse(String json) {
        List<Item> out = new ArrayList<>();
        try {
            JSONArray list = new JSONObject(json).getJSONArray("wallpapers");
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.getJSONObject(i);
                String id = o.optString("id", ""), file = o.optString("file", ""), thumb = o.optString("thumb", "");
                if (!id.matches("[a-z0-9][a-z0-9-]{0,63}") || !safePath(file) || !safePath(thumb)) continue;
                out.add(new Item(id, o.optString("title", id), o.optString("credit", ""), o.optString("license", ""),
                        file, thumb, o.optString("sha256", "").toLowerCase(Locale.ROOT)));
            }
        } catch (JSONException e) {
            return Collections.emptyList();
        }
        return out;
    }

    private static boolean safePath(String path) {
        return path.matches("[a-z]+/[a-z0-9][a-z0-9-]*\\.(webp|jpg|png)");
    }

    /** The index from the first base that answers. Blocking. */
    static Index fetchIndex() throws IOException {
        IOException last = null;
        for (String base : BASES) {
            try {
                List<Item> items = parse(Updater.fetchText(base + "index.json"));
                if (!items.isEmpty()) return new Index(base, items);
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("empty index");
    }

    /** The bases in the order to try for a file: the one the index came from first. */
    private static List<String> order(String first) {
        List<String> out = new ArrayList<>();
        out.add(first);
        for (String b : BASES) if (!b.equals(first)) out.add(b);
        return out;
    }

    /** A thumbnail, about 15 KB. Blocking; null when no base serves it. */
    static Bitmap thumbnail(Index index, Item item) {
        for (String base : order(index.base)) {
            try {
                HttpURLConnection c = Updater.open(base + item.thumb);
                try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[16 * 1024];
                    for (int n; (n = in.read(buffer)) > 0; ) {
                        out.write(buffer, 0, n);
                        if (out.size() > 512 * 1024) throw new IOException("thumbnail too large");
                    }
                    Bitmap b = BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size());
                    if (b != null) return b;
                } finally {
                    c.disconnect();
                }
            } catch (IOException ignored) {
                // the next base
            }
        }
        return null;
    }

    static File dir(Context ctx) {
        return new File(ctx.getFilesDir(), "wallpapers_downloaded");
    }

    /** Downloaded pictures, sorted by name. */
    static List<File> downloaded(Context ctx) {
        return WallpaperRotation.images(dir(ctx));
    }

    /** Downloads one picture, checks its SHA-256 and records its caption. Blocking. */
    static File download(Context ctx, Index index, Item item, Updater.Progress progress) throws IOException {
        File dir = dir(ctx);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File target = new File(dir, item.fileName());
        File partial = new File(dir, item.fileName() + ".part");
        IOException last = null;
        for (String base : order(index.base)) {
            try {
                Updater.fetchFile(base + item.file, partial, progress);
                if (!item.sha256.isEmpty() && !item.sha256.equals(Updater.sha256(partial))) {
                    throw new IOException(ctx.getString(R.string.update_err_checksum));
                }
                if (!partial.renameTo(target)) throw new IOException("cannot write " + target);
                writeCaption(dir, item.fileName(), item.caption());
                return target;
            } catch (IOException e) {
                last = e;
                partial.delete();
            }
        }
        throw last;
    }

    /** Deletes a downloaded picture and its caption. */
    static void delete(Context ctx, File file) {
        if (!dir(ctx).equals(file.getParentFile())) return;
        file.delete();
        try {
            writeCaption(dir(ctx), file.getName(), null);
        } catch (IOException ignored) {
            // a stale caption line is harmless
        }
    }

    /** captions.txt in WallpaperCaptions' format: "name<TAB>caption" per line; null removes the name. */
    private static synchronized void writeCaption(File dir, String name, String caption) throws IOException {
        File file = new File(dir, "captions.txt");
        Map<String, String> lines = new LinkedHashMap<>(WallpaperCaptions.read(dir));
        if (caption == null) lines.remove(name);
        else lines.put(name, caption.replace('\t', ' ').replace('\n', ' '));
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> e : lines.entrySet()) out.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        Files.write(file.toPath(), out.toString().getBytes(StandardCharsets.UTF_8));
    }
}
