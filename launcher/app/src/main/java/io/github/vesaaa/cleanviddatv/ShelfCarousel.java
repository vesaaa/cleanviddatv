package io.github.vesaaa.cleanviddatv;

import android.app.Activity;
import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * The top shelf as a tvOS carousel (Launcher settings → 展示样式 → 大图轮播), for a shelf whose cards carry artwork
 * (docs/top-shelf.md backdrop_uri): no row of cards. With the dock tile focused, the whole backdrop shows one title
 * at a time, its logo, episode or year and synopsis above the dock, and moves on by itself. Up goes full screen: the
 * dock and the grid step behind it, Left and Right change the title, OK opens the app, Down or Back come back.
 */
final class ShelfCarousel {

    interface Host {
        /** Open the app the shelf belongs to, at this card. */
        void open(ShelfItem item);

        /** Focus back on the dock tile. */
        void returnToDock();
    }

    private static final long ADVANCE_MS = 8_000L;
    private static final int BACKDROP_PX = 1920;

    private final Activity activity;
    private final Host host;
    private final Function<String, Bitmap> logos;
    private final float density;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "shelf-carousel"));

    /** Over the wallpaper and under the grid while it previews; over everything (translationZ) when full screen. */
    private final FrameLayout stage;
    private final ImageView[] layers = new ImageView[2];
    private int front;
    private final LinearLayout info, dots;
    private final ImageView logo;
    private final TextView title, subtitle, overview, hint;
    /** Backdrops decoded for the titles around the current one; the rest are let go. */
    private final Map<String, Bitmap> backdrops = new HashMap<>();

    private List<ShelfItem> items;
    private int index;
    private boolean shown, fullScreen;
    private float dockTop;
    private final Runnable advance = () -> step(1);

    ShelfCarousel(Activity activity, FrameLayout root, int index, Host host, Function<String, Bitmap> logos) {
        this.activity = activity;
        this.host = host;
        this.logos = logos;
        density = activity.getResources().getDisplayMetrics().density;

        stage = new FrameLayout(activity);
        stage.setAlpha(0f);
        stage.setVisibility(View.GONE);
        stage.setFocusable(false);     // only while full screen
        stage.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        stage.setOnKeyListener(this::onKey);
        for (int i = 0; i < 2; i++) {
            ImageView layer = new ImageView(activity);
            layer.setScaleType(ImageView.ScaleType.CENTER_CROP);
            layer.setAlpha(0f);
            layers[i] = layer;
            stage.addView(layer, match());
        }
        // Dark at the bottom and on the left, where the text and the dock are; the picture stays clear top right.
        View down = new View(activity);
        down.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x8C000000, 0x1A000000, 0x99000000, 0xF2000000}));
        stage.addView(down, match());
        View side = new View(activity);
        side.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xB3000000, 0x4D000000, 0x00000000}));
        stage.addView(side, match());

        info = new LinearLayout(activity);
        info.setOrientation(LinearLayout.VERTICAL);
        logo = new ImageView(activity);
        logo.setAdjustViewBounds(true);
        logo.setMaxHeight(dp(110));
        logo.setMaxWidth(dp(460));
        logo.setScaleType(ImageView.ScaleType.FIT_START);
        info.addView(logo, wrap());
        title = text(44, 0xFFFFFFFF, R.string.font_medium);
        title.setSingleLine(true);
        info.addView(title, wrap());
        subtitle = text(18, 0xE6FFFFFF, R.string.font_medium);
        subtitle.setSingleLine(true);
        LinearLayout.LayoutParams sp = wrap();
        sp.topMargin = dp(14);
        info.addView(subtitle, sp);
        overview = text(16, 0xCCFFFFFF, R.string.font_regular);
        overview.setMaxLines(3);
        overview.setEllipsize(TextUtils.TruncateAt.END);
        overview.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(dp(820), ViewGroup.LayoutParams.WRAP_CONTENT);
        op.topMargin = dp(8);
        info.addView(overview, op);
        dots = new LinearLayout(activity);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams dl = wrap();
        dl.topMargin = dp(18);
        info.addView(dots, dl);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        ip.setMarginStart(dp(68));
        stage.addView(info, ip);

        hint = text(15, 0x99FFFFFF, R.string.font_regular);
        hint.setAlpha(0f);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        hp.setMarginEnd(dp(68));
        hp.bottomMargin = dp(52);
        stage.addView(hint, hp);

        root.addView(stage, index, match());
    }

    /**
     * The titles a carousel shows: those with artwork to fill the screen (a plain video file has none and is left
     * out). Empty when the shelf has none, which then stays a row of cards.
     */
    static List<ShelfItem> titles(List<ShelfItem> items) {
        List<ShelfItem> out = new java.util.ArrayList<>();
        for (ShelfItem item : items) if (item.backdropUri != null) out.add(item);
        return out;
    }

    boolean shown() {
        return shown;
    }

    boolean hasFocus() {
        return fullScreen && stage.hasFocus();
    }

    /** Show these titles over the backdrop, the text ending a little above the dock's top; appName opens them. */
    void bind(List<ShelfItem> list, float dockTopInRoot, String appName) {
        dockTop = dockTopInRoot;
        hint.setText(activity.getString(R.string.carousel_hint, appName));
        boolean same = items != null && items.size() == list.size();
        if (same) {
            for (int i = 0; i < list.size(); i++) {
                same &= items.get(i).id == list.get(i).id
                        && TextUtils.equals(items.get(i).backdropUri, list.get(i).backdropUri);
            }
        }
        items = list;
        if (index >= list.size()) index = 0;
        buildDots();
        if (!shown) {
            shown = true;
            stage.setVisibility(View.VISIBLE);
            stage.animate().cancel();
            stage.animate().alpha(1f).setDuration(420).start();
            present(false);
        } else if (!same) {
            present(false);
        }
        schedule();
    }

    /** The dock moved (another tile) or the layout changed: keep the text above it. */
    void place(float dockTopInRoot) {
        dockTop = dockTopInRoot;
        if (!fullScreen) placeInfo(false);
    }

    void hide() {
        handler.removeCallbacks(advance);
        if (fullScreen) leaveFullScreen(false);
        if (!shown) return;
        shown = false;
        stage.animate().cancel();
        stage.animate().alpha(0f).setDuration(260).withEndAction(() -> {
            if (shown) return;
            stage.setVisibility(View.GONE);
            for (ImageView layer : layers) {
                layer.setImageDrawable(null);
                layer.setAlpha(0f);
            }
            recycleAll();
        }).start();
    }

    void release() {
        handler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        for (ImageView layer : layers) layer.setImageDrawable(null);
        recycleAll();
    }

    /** Up from the dock: the carousel takes the whole screen and the focus. */
    boolean enterFullScreen() {
        if (!shown || items == null || items.isEmpty()) return false;
        fullScreen = true;
        stage.setFocusable(true);
        stage.setTranslationZ(dp(48));
        placeInfo(true);
        hint.animate().alpha(1f).setDuration(320).start();
        stage.requestFocus();
        schedule();
        return true;
    }

    private void leaveFullScreen(boolean toDock) {
        fullScreen = false;
        stage.setFocusable(false);
        stage.setTranslationZ(0f);
        hint.animate().cancel();
        hint.setAlpha(0f);
        placeInfo(true);
        if (toDock) host.returnToDock();
        schedule();
    }

    private boolean onKey(View v, int keyCode, KeyEvent event) {
        if (!fullScreen) return false;
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean rtl = v.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) step(rtl ? 1 : -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) step(rtl ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (!down && items != null && index < items.size()) host.open(items.get(index));
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) leaveFullScreen(true);
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (!down) leaveFullScreen(true);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                return true;
            default:
                return false;
        }
    }

    // ---- showing a title -------------------------------------------------------------------------------------

    private void step(int by) {
        if (items == null || items.size() < 2) return;
        index = (index + by + items.size()) % items.size();
        present(true);
        schedule();
    }

    /** Moves on by itself every few seconds, from the last move or key press. */
    private void schedule() {
        handler.removeCallbacks(advance);
        if (shown && items != null && items.size() > 1) handler.postDelayed(advance, ADVANCE_MS);
    }

    private void present(boolean animate) {
        ShelfItem item = items.get(index);
        Bitmap logoBitmap = item.logoUri == null ? null : logos.apply(item.logoUri);
        Runnable fill = () -> {
            logo.setImageBitmap(logoBitmap);
            logo.setVisibility(logoBitmap == null ? View.GONE : View.VISIBLE);
            title.setText(item.title);
            title.setVisibility(logoBitmap == null ? View.VISIBLE : View.GONE);
            subtitle.setText(item.subtitle);
            subtitle.setVisibility(item.subtitle == null ? View.GONE : View.VISIBLE);
            overview.setText(item.overview);
            overview.setVisibility(item.overview == null ? View.GONE : View.VISIBLE);
            markDot();
            placeInfo(false);
        };
        if (animate) {
            info.animate().cancel();
            info.animate().alpha(0f).setDuration(160).withEndAction(() -> {
                fill.run();
                info.animate().alpha(1f).setDuration(320).start();
            }).start();
        } else {
            fill.run();
            info.setAlpha(1f);
        }
        showBackdrop(item.backdropUri);
        // The next title's backdrop is decoded ahead, so it is there when the carousel moves on.
        if (items.size() > 1) load(items.get((index + 1) % items.size()).backdropUri, null);
    }

    private void showBackdrop(String uri) {
        Bitmap ready = backdrops.get(uri);
        if (ready != null) {
            crossFade(ready);
            keepAround();
            return;
        }
        load(uri, () -> {
            if (items != null && index < items.size() && uri.equals(items.get(index).backdropUri)) {
                crossFade(backdrops.get(uri));
            }
            keepAround();
        });
    }

    private void load(String uri, Runnable then) {
        if (uri == null || backdrops.containsKey(uri)) {
            if (then != null) then.run();
            return;
        }
        ContentResolver cr = activity.getContentResolver();
        worker.execute(() -> {
            Bitmap b = ShelfSource.image(cr, uri, BACKDROP_PX, true);
            handler.post(() -> {
                if (activity.isDestroyed()) {
                    if (b != null) b.recycle();
                    return;
                }
                if (b != null && !backdrops.containsKey(uri)) backdrops.put(uri, b);
                if (then != null) then.run();
            });
        });
    }

    private void crossFade(Bitmap bitmap) {
        if (bitmap == null) return;
        int next = 1 - front;
        ImageView in = layers[next], out = layers[front];
        if (out.getDrawable() != null && out.getAlpha() > 0f && bitmapOf(out) == bitmap) return;
        in.setImageBitmap(bitmap);
        in.animate().cancel();
        out.animate().cancel();
        // The incoming picture goes over the outgoing one, still under the shades and the text.
        stage.removeView(in);
        stage.addView(in, 1);
        in.setAlpha(0f);
        in.setScaleX(1.04f);
        in.setScaleY(1.04f);
        in.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(700)
                .setInterpolator(new DecelerateInterpolator()).withEndAction(() -> out.setAlpha(0f)).start();
        front = next;
    }

    private static Bitmap bitmapOf(ImageView v) {
        return v.getDrawable() instanceof android.graphics.drawable.BitmapDrawable
                ? ((android.graphics.drawable.BitmapDrawable) v.getDrawable()).getBitmap() : null;
    }

    /** Only the current, the previous and the next title's backdrops stay decoded (each a few MB). */
    private void keepAround() {
        if (items == null || items.isEmpty()) return;
        java.util.Set<String> keep = new java.util.HashSet<>();
        int n = items.size();
        for (int d = -1; d <= 1; d++) keep.add(items.get((index + d + n) % n).backdropUri);
        java.util.Iterator<Map.Entry<String, Bitmap>> it = backdrops.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Bitmap> e = it.next();
            if (keep.contains(e.getKey())) continue;
            Bitmap b = e.getValue();
            if (b != bitmapOf(layers[0]) && b != bitmapOf(layers[1])) {
                b.recycle();
                it.remove();
            }
        }
    }

    private void recycleAll() {
        for (Bitmap b : backdrops.values()) if (b != null) b.recycle();
        backdrops.clear();
    }

    // ---- layout ----------------------------------------------------------------------------------------------

    /** Above the dock while previewing; low on the screen when the carousel has it to itself. */
    private void placeInfo(boolean animate) {
        info.measure(View.MeasureSpec.makeMeasureSpec(stage.getWidth() > 0 ? stage.getWidth() : dp(1920),
                View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        float bottom = fullScreen ? (stage.getHeight() > 0 ? stage.getHeight() : dp(540)) - dp(96) : dockTop - dp(12);
        float y = bottom - info.getMeasuredHeight();
        if (animate) info.animate().y(y).setDuration(360).setInterpolator(new DecelerateInterpolator()).start();
        else info.setY(y);
    }

    private void buildDots() {
        dots.removeAllViews();
        int n = items == null ? 0 : items.size();
        dots.setVisibility(n > 1 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < n; i++) {
            View dot = new View(activity);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(3));
            dot.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(6), dp(6));
            if (i > 0) lp.setMarginStart(dp(8));
            dots.addView(dot, lp);
        }
        markDot();
    }

    private void markDot() {
        for (int i = 0; i < dots.getChildCount(); i++) {
            View dot = dots.getChildAt(i);
            ((GradientDrawable) dot.getBackground()).setColor(i == index ? 0xFFFFFFFF : 0x66FFFFFF);
            ViewGroup.LayoutParams lp = dot.getLayoutParams();
            lp.width = dp(i == index ? 22 : 6);
            dot.setLayoutParams(lp);
        }
    }

    private TextView text(float sp, int color, int font) {
        TextView t = new TextView(activity);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setShadowLayer(dp(6), 0, dp(1), 0x66000000);
        try {
            t.setTypeface(UiFont.get(activity, font));
        } catch (Exception ignored) {
            // the system font is fine
        }
        return t;
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
