package io.github.vesaaa.cleanviddatv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Launcher settings > Wallpaper: thumbnails of every choice, with the focused one shown full screen behind the grid,
 * so a wallpaper can be judged without leaving settings. Groups to rotate, the bundled pictures, the downloaded ones
 * (hold OK to delete), the online ones from OnlineWallpapers (OK downloads and applies), then none, the theme's
 * default and the credits. A choice is saved like the old list picker's: Config.WALLPAPER_CHOICE_KEY plus a new
 * settings revision, which makes Home rebuild when it comes back.
 */
public class WallpaperGalleryActivity extends Activity {
    private static final int COLUMNS = 5;
    private static final int HEADER = 0, TILE = 1;
    /** A tile that is not a picture choice: the theme's default, and the credits. */
    private static final String THEME_DEFAULT = "\u0000theme", CREDITS = "\u0000credits";

    private final List<Row> rows = new ArrayList<>();
    private final Grid adapter = new Grid();
    private final ExecutorService loader = Executors.newFixedThreadPool(2);
    private SharedPreferences choices;
    private ImageView preview;
    private TextView caption;
    private RecyclerView grid;
    private Map<String, String> builtinCaptions, downloadedCaptions;
    /** null while the online index loads; an empty index after it failed. */
    private OnlineWallpapers.Index online;
    private boolean onlineFailed;
    /** The tile whose picture the preview shows, so a slow decode for a tile left behind is dropped. */
    private Row previewed;

    private static final class Row {
        final int type;
        final String label;
        /** The choice saved for this tile: a file name, a group, "none", or one of the special values. */
        final String value;
        final File file;
        final OnlineWallpapers.Item item;
        Bitmap thumb;
        boolean thumbLoading;
        String status;

        Row(int type, String label, String value, File file, OnlineWallpapers.Item item) {
            this.type = type;
            this.label = label;
            this.value = value;
            this.file = file;
            this.item = item;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wallpaper_gallery);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        preview = findViewById(R.id.galleryPreview);
        caption = findViewById(R.id.galleryCaption);
        grid = findViewById(R.id.galleryGrid);
        GridLayoutManager layout = new GridLayoutManager(this, COLUMNS);
        layout.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return rows.get(position).type == HEADER ? COLUMNS : 1;
            }
        });
        grid.setLayoutManager(layout);
        // No cross-fade on a changed tile: it lays out a second copy of the tile, and with a download's progress
        // changing it every moment the focused tile jumped between its place and another one.
        ((SimpleItemAnimator) grid.getItemAnimator()).setSupportsChangeAnimations(false);
        grid.setAdapter(adapter);
        build();
        grid.post(this::focusCurrent);
        loadOnline();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        loader.shutdownNow();
    }

    /** The rows from what is on disk and the online index as far as it is known. */
    private void build() {
        // The thumbnails already decoded carry over, so the tiles do not go blank while the grid is rebuilt.
        Map<String, Bitmap> thumbs = new java.util.HashMap<>();
        for (Row r : rows) if (r.thumb != null && key(r) != null) thumbs.put(key(r) + "\u0000" + r.file, r.thumb);
        rows.clear();
        List<File> builtins = BuiltinWallpapers.all(this);
        List<File> downloaded = OnlineWallpapers.downloaded(this);
        builtinCaptions = WallpaperCaptions.read(BuiltinWallpapers.dir(this));
        downloadedCaptions = WallpaperCaptions.read(OnlineWallpapers.dir(this));
        rows.add(header(getString(R.string.wallpaper_section_rotate)));
        if (!builtins.isEmpty()) {
            rows.add(tile(getString(R.string.wallpaper_builtin_photos), BuiltinWallpapers.PHOTOS,
                    first(BuiltinWallpapers.group(builtins, BuiltinWallpapers.PHOTOS))));
            rows.add(tile(getString(R.string.wallpaper_builtin_art), BuiltinWallpapers.ART,
                    first(BuiltinWallpapers.group(builtins, BuiltinWallpapers.ART))));
        }
        if (!downloaded.isEmpty()) {
            rows.add(tile(getString(R.string.wallpaper_downloaded_rotate), OnlineWallpapers.GROUP, downloaded.get(0)));
        }
        if (!builtins.isEmpty()) {
            rows.add(header(getString(R.string.wallpaper_section_builtin)));
            for (File f : builtins) rows.add(tile(BuiltinWallpapers.label(builtinCaptions, f), f.getName(), f));
        }
        if (!downloaded.isEmpty()) {
            rows.add(header(getString(R.string.wallpaper_section_downloaded)));
            for (File f : downloaded) rows.add(tile(BuiltinWallpapers.label(downloadedCaptions, f), f.getName(), f));
        }
        List<Row> offered = new ArrayList<>();
        if (online != null) {
            for (OnlineWallpapers.Item item : online.items) {
                if (new File(OnlineWallpapers.dir(this), item.fileName()).isFile()) continue;
                Row r = new Row(TILE, item.title, null, null, item);
                offered.add(r);
            }
        }
        rows.add(header(getString(onlineFailed ? R.string.wallpaper_section_online_failed
                : online == null ? R.string.wallpaper_section_online_loading
                : offered.isEmpty() ? R.string.wallpaper_section_online_done : R.string.wallpaper_section_online)));
        rows.addAll(offered);
        rows.add(header(getString(R.string.wallpaper_section_more)));
        rows.add(tile(getString(R.string.wallpaper_none), WallpaperRotation.CHOICE_NONE, null));
        rows.add(tile(getString(R.string.wallpaper_theme_default), THEME_DEFAULT, null));
        rows.add(tile(getString(R.string.wallpaper_credits), CREDITS, null));
        for (Row r : rows) if (key(r) != null) r.thumb = thumbs.get(key(r) + "\u0000" + r.file);
        adapter.notifyDataSetChanged();
    }

    private static File first(List<File> files) {
        return files.isEmpty() ? null : files.get(0);
    }

    private static Row header(String text) {
        return new Row(HEADER, text, null, null, null);
    }

    private static Row tile(String label, String value, File file) {
        return new Row(TILE, label, value, file, null);
    }

    private void loadOnline() {
        loader.execute(() -> {
            OnlineWallpapers.Index index;
            try {
                index = OnlineWallpapers.fetchIndex();
            } catch (java.io.IOException e) {
                index = null;
            }
            OnlineWallpapers.Index found = index;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                online = found != null ? found : new OnlineWallpapers.Index("", new ArrayList<>());
                onlineFailed = found == null;
                rebuildKeepingFocus();
            });
        });
    }

    /** Rebuilds the rows and puts focus back on the same choice, or the same place. */
    private void rebuildKeepingFocus() {
        View focused = grid.getFocusedChild();
        int position = focused == null ? -1 : grid.getChildAdapterPosition(focused);
        String value = position >= 0 && position < rows.size() ? key(rows.get(position)) : null;
        build();
        int target = -1;
        for (int i = 0; value != null && i < rows.size(); i++) if (value.equals(key(rows.get(i)))) target = i;
        if (target < 0) target = Math.max(0, Math.min(position, rows.size() - 1));
        focusAt(target);
    }

    private static String key(Row r) {
        return r.value != null ? r.value : r.item != null ? "online:" + r.item.id : null;
    }

    private void focusCurrent() {
        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        String want = current == null ? THEME_DEFAULT : current;
        int target = -1;
        for (int i = 0; i < rows.size(); i++) if (want.equals(rows.get(i).value)) target = i;
        if (target < 0) for (int i = 0; i < rows.size(); i++) if (rows.get(i).type == TILE) {
            target = i;
            break;
        }
        focusAt(target);
    }

    /** Focuses the tile at position, or the next tile when it is a section header. */
    private void focusAt(int position) {
        int target = position;
        while (target >= 0 && target < rows.size() && rows.get(target).type != TILE) target++;
        if (target < 0 || target >= rows.size()) return;
        int at = target;
        grid.scrollToPosition(at);
        grid.post(() -> {
            RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(at);
            View tile = vh == null ? null : vh.itemView.findViewById(R.id.wallTile);
            if (tile != null) tile.requestFocus();
        });
    }

    private void onFocused(Row r) {
        caption.setText(captionOf(r));
        previewed = r;
        if (r.file == null) {
            preview.setImageBitmap(r.item != null ? r.thumb : null);
            return;
        }
        File file = r.file;
        loader.execute(() -> {
            Bitmap b = decode(file, 2);
            runOnUiThread(() -> {
                if (previewed == r && !isDestroyed()) preview.setImageBitmap(b);
            });
        });
    }

    private String captionOf(Row r) {
        if (r.item != null) return r.item.caption();
        if (r.file != null && r.value != null && !r.value.startsWith("builtin:") && !r.value.equals(OnlineWallpapers.GROUP)) {
            String c = builtinCaptions.get(r.file.getName());
            if (c == null) c = downloadedCaptions.get(r.file.getName());
            if (c != null) return c;
        }
        return r.label;
    }

    private void onClick(Row r) {
        if (r.item != null) {
            download(r);
            return;
        }
        if (CREDITS.equals(r.value)) {
            showCredits();
            return;
        }
        SharedPreferences.Editor e = choices.edit();
        if (THEME_DEFAULT.equals(r.value)) e.remove(Config.WALLPAPER_CHOICE_KEY);
        else e.putString(Config.WALLPAPER_CHOICE_KEY, r.value);
        e.putLong(Config.SETTINGS_REVISION_KEY, choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
        setResult(RESULT_OK);
        Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
        adapter.notifyDataSetChanged();
    }

    private void download(Row r) {
        if (r.status != null) return;   // running
        r.status = getString(R.string.wallpaper_downloading, 0);
        adapter.notifyItemChanged(rows.indexOf(r));
        OnlineWallpapers.Index index = online;
        loader.execute(() -> {
            String error = null;
            File file = null;
            try {
                file = OnlineWallpapers.download(this, index, r.item, percent -> runOnUiThread(() -> {
                    String status = getString(R.string.wallpaper_downloading, percent);
                    if (status.equals(r.status)) return;
                    r.status = status;
                    showStatus(r);
                }));
            } catch (java.io.IOException e) {
                error = String.valueOf(e.getMessage());
            }
            File done = file;
            String failure = error;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (failure != null) {
                    r.status = null;
                    adapter.notifyItemChanged(rows.indexOf(r));
                    Toast.makeText(this, getString(R.string.wallpaper_download_failed, failure), Toast.LENGTH_LONG).show();
                    return;
                }
                build();
                for (int i = 0; i < rows.size(); i++) {
                    Row row = rows.get(i);
                    if (row.file != null && row.file.equals(done) && done.getName().equals(row.value)) {
                        onClick(row);
                        focusAt(i);
                        break;
                    }
                }
            });
        });
    }

    /** The progress on a tile: only its text, without binding the tile again. */
    private void showStatus(Row r) {
        int i = rows.indexOf(r);
        RecyclerView.ViewHolder vh = i < 0 ? null : grid.findViewHolderForAdapterPosition(i);
        if (vh == null) return;   // off screen: bound with r.status when it comes back
        TextView status = vh.itemView.findViewById(R.id.wallStatus);
        status.setText(r.status);
        status.setVisibility(View.VISIBLE);
    }

    private void confirmDelete(Row r) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.wallpaper_delete_title)
                .setMessage(captionOf(r))
                .setPositiveButton(R.string.wallpaper_delete, (d, w) -> {
                    String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
                    OnlineWallpapers.delete(this, r.file);
                    if (r.value.equals(current) || (OnlineWallpapers.GROUP.equals(current)
                            && OnlineWallpapers.downloaded(this).isEmpty())) {
                        choices.edit().remove(Config.WALLPAPER_CHOICE_KEY).putLong(Config.SETTINGS_REVISION_KEY,
                                choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
                        setResult(RESULT_OK);
                    }
                    rebuildKeepingFocus();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showCredits() {
        StringBuilder text = new StringBuilder();
        for (String c : builtinCaptions.values()) if (!c.startsWith("抽象")) text.append("• ").append(c).append('\n');
        for (String c : downloadedCaptions.values()) text.append("• ").append(c).append('\n');
        text.append('\n').append(getString(R.string.wallpaper_credits_note))
                .append('\n').append(getString(R.string.wallpaper_online_credits));
        new AlertDialog.Builder(this)
                .setTitle(R.string.wallpaper_credits)
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** A picture file scaled down by sample (powers of two), in 16-bit colour; null when it cannot be read. */
    private static Bitmap decode(File file, int sample) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(file.getPath(), o);
    }

    private final class Grid extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public int getItemViewType(int position) {
            return rows.get(position).type;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == HEADER) {
                return new RecyclerView.ViewHolder(inflater.inflate(R.layout.item_wallpaper_header, parent, false)) { };
            }
            View v = inflater.inflate(R.layout.item_wallpaper, parent, false);
            View tile = v.findViewById(R.id.wallTile);
            // 16:9 from the column width the grid gives the cell, less the cell's padding.
            int cell = (parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight()) / COLUMNS;
            int pad = v.getPaddingLeft() + v.getPaddingRight();
            tile.getLayoutParams().height = Math.max(1, (cell - pad) * 9 / 16);
            float radius = 12 * parent.getResources().getDisplayMetrics().density;
            tile.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            tile.setClipToOutline(true);
            return new RecyclerView.ViewHolder(v) { };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Row r = rows.get(position);
            View v = holder.itemView;
            if (r.type == HEADER) {
                ((TextView) v).setText(r.label);
                return;
            }
            View tile = v.findViewById(R.id.wallTile);
            ImageView thumb = v.findViewById(R.id.wallThumb);
            ((TextView) v.findViewById(R.id.wallLabel)).setText(r.label);
            TextView status = v.findViewById(R.id.wallStatus);
            status.setText(r.status);
            status.setVisibility(r.status == null ? View.GONE : View.VISIBLE);
            String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
            boolean inUse = r.value != null && (r.value.equals(current) || (current == null && THEME_DEFAULT.equals(r.value)));
            v.findViewById(R.id.wallBadge).setVisibility(inUse ? View.VISIBLE : View.GONE);
            thumb.setImageBitmap(r.thumb);
            if (r.thumb == null && !r.thumbLoading && (r.file != null || r.item != null)) loadThumb(r);
            tile.setOnClickListener(x -> onClick(r));
            tile.setOnLongClickListener(r.file != null && r.value != null && r.value.startsWith(OnlineWallpapers.PREFIX)
                    ? x -> {
                        confirmDelete(r);
                        return true;
                    } : null);
            tile.setOnFocusChangeListener((x, focused) -> {
                x.animate().scaleX(focused ? 1.06f : 1f).scaleY(focused ? 1.06f : 1f).setDuration(150).start();
                if (focused) onFocused(r);
            });
        }

        private void loadThumb(Row r) {
            r.thumbLoading = true;
            loader.execute(() -> {
                Bitmap b = r.file != null ? decode(r.file, 4) : online == null ? null : OnlineWallpapers.thumbnail(online, r.item);
                if (b == null) return;
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    r.thumb = b;
                    int i = rows.indexOf(r);
                    if (i >= 0) notifyItemChanged(i);
                    if (previewed == r && r.item != null) preview.setImageBitmap(b);
                });
            });
        }
    }
}
