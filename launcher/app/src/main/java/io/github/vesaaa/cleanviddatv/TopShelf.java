package io.github.vesaaa.cleanviddatv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The top shelf's card row (config.xml top_shelf, docs/top-shelf.md): when a dock app that publishes a shelf has
 * focus, its cards (VsTV: channels, what is on and how far in) take the hero's place above the dock. Up from the dock
 * walks into the row, OK opens a card, Down or Back returns to the dock, Up goes on to the top bar. An app with no shelf, or an empty one, keeps
 * the plain hero.
 */
final class TopShelf {

    interface Host {
        /** Focus back on the dock tile the shelf belongs to. */
        void returnToDock();

        /** Up from a card: the top bar's nearest button. */
        void focusTopBar(View from);
    }

    private static final int CARD_W_DP = 224, CARD_H_DP = 126;
    /** Cached rows are reused for this long before the app is asked again on the next focus. */
    private static final long FRESH_MS = 30_000L;

    private final Activity activity;
    private final View hero;
    private final Host host;
    private final float density;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "top-shelf"));
    private final LinearLayout panel;
    private final TextView heading;
    private final HorizontalScrollView scroller;
    private final LinearLayout row;
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());

    private Map<String, ProviderInfo> providers;
    private final Map<String, List<ShelfItem>> cache = new HashMap<>();
    private final Map<String, Long> cachedAt = new HashMap<>();
    private final Map<String, Bitmap> images = new HashMap<>();
    /** The app the row is for (shown or loading), and whether its cards are on screen. */
    private String pkg;
    private boolean shown;
    private float bottomY;
    private int focusIndex;
    private ContentObserver observer;
    private final Runnable refresh = () -> load(true);

    TopShelf(Activity activity, FrameLayout root, View hero, Host host) {
        this.activity = activity;
        this.hero = hero;
        this.host = host;
        density = activity.getResources().getDisplayMetrics().density;

        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setAlpha(0f);
        panel.setVisibility(View.GONE);
        panel.setClipChildren(false);
        panel.setClipToPadding(false);

        heading = new TextView(activity);
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        heading.setTextColor(0xCCFFFFFF);
        heading.setSingleLine(true);
        font(heading, R.string.font_medium);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.setMarginStart(dp(68));
        hp.bottomMargin = dp(6);
        panel.addView(heading, hp);

        scroller = new HorizontalScrollView(activity);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipChildren(false);
        scroller.setClipToPadding(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroller.setSmoothScrollingEnabled(true);
        row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        // Room for the focused card's zoom on every side, and the first card on the hero's left edge.
        row.setPadding(dp(68), dp(12), dp(68), dp(16));
        scroller.addView(row);
        panel.addView(scroller, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        root.addView(panel, root.indexOfChild(hero) + 1, lp);
    }

    /** Apps installed or removed, or Home back in front: look for shelf providers again and ask the apps afresh. */
    void invalidate() {
        providers = null;
        cachedAt.clear();
    }

    /**
     * The dock app at this package has focus, its row's top at dockTopInRoot: show its cards, or leave the hero when
     * it publishes none. Returns whether the app has a shelf provider at all.
     */
    boolean show(String packageName, float dockTopInRoot) {
        bottomY = dockTopInRoot - dp(18);
        if (providers == null) providers = ShelfSource.providers(activity.getPackageManager());
        ProviderInfo provider = packageName == null ? null : providers.get(packageName);
        if (provider == null) {
            hide();
            return false;
        }
        if (packageName.equals(pkg)) {
            if (shown) place();
            Long at = cachedAt.get(packageName);
            if (at == null || System.currentTimeMillis() - at > FRESH_MS) load(false);
            return true;
        }
        hide();
        pkg = packageName;
        focusIndex = 0;
        heading.setText(headingFor(provider));
        List<ShelfItem> cached = cache.get(packageName);
        if (cached != null && !cached.isEmpty()) bind(cached);
        Long at = cachedAt.get(packageName);
        if (at == null || System.currentTimeMillis() - at > FRESH_MS) load(false);
        return true;
    }

    /** The cards go and the hero comes back. */
    void hide() {
        handler.removeCallbacks(refresh);
        unwatch();
        pkg = null;
        if (!shown) return;
        shown = false;
        panel.animate().cancel();
        panel.setAlpha(0f);
        panel.setVisibility(View.GONE);
        hero.setVisibility(View.VISIBLE);
    }

    /** Up from the dock tile of this app: focus its row when it shows cards. */
    boolean focus(String packageName) {
        if (!shown || packageName == null || !packageName.equals(pkg) || row.getChildCount() == 0) return false;
        View card = cardAt(Math.min(focusIndex, cardCount() - 1));
        return card != null && card.requestFocus();
    }

    boolean hasFocus() {
        return shown && panel.hasFocus();
    }

    void release() {
        hide();
        worker.shutdownNow();
        for (Bitmap b : images.values()) if (b != null) b.recycle();
        images.clear();
    }

    // ---- loading ---------------------------------------------------------------------------------------------

    private void load(boolean force) {
        String forPkg = pkg;
        ProviderInfo provider = forPkg == null || providers == null ? null : providers.get(forPkg);
        if (provider == null) return;
        String authority = ShelfSource.authority(provider);
        ContentResolver cr = activity.getContentResolver();
        worker.execute(() -> {
            List<ShelfItem> items = ShelfSource.query(cr, authority);
            Map<String, Bitmap> loaded = new HashMap<>();
            for (ShelfItem item : items) {
                if (item.imageUri == null || images.containsKey(item.imageUri) || loaded.containsKey(item.imageUri)) continue;
                loaded.put(item.imageUri, ShelfSource.image(cr, item.imageUri));
            }
            handler.post(() -> {
                if (activity.isDestroyed()) return;
                images.putAll(loaded);
                cache.put(forPkg, items);
                cachedAt.put(forPkg, System.currentTimeMillis());
                if (!forPkg.equals(pkg)) return;
                if (items.isEmpty()) {
                    unbind();
                } else if (force || !shown || changed(items)) {
                    bind(items);
                }
                watch(authority);
                schedule(items);
            });
        });
    }

    private boolean changed(List<ShelfItem> items) {
        List<ShelfItem> current = shownItems();
        if (current.size() != items.size()) return true;
        for (int i = 0; i < items.size(); i++) {
            ShelfItem a = current.get(i), b = items.get(i);
            if (a.id != b.id || !TextUtils.equals(a.title, b.title) || !TextUtils.equals(a.subtitle, b.subtitle)
                    || a.startMs != b.startMs || !TextUtils.equals(a.imageUri, b.imageUri)) return true;
        }
        return false;
    }

    /** Ask again once the first running programme ends, since an app that is not running sends no change. */
    private void schedule(List<ShelfItem> items) {
        handler.removeCallbacks(refresh);
        long now = System.currentTimeMillis();
        long at = ShelfItem.nextRefresh(items, now);
        if (at > 0) handler.postDelayed(refresh, at - now);
    }

    private void watch(String authority) {
        unwatch();
        observer = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                load(true);
            }
        };
        try {
            activity.getContentResolver().registerContentObserver(ShelfSource.itemsUri(authority), true, observer);
        } catch (RuntimeException e) {
            observer = null;
        }
    }

    private void unwatch() {
        if (observer == null) return;
        try {
            activity.getContentResolver().unregisterContentObserver(observer);
        } catch (RuntimeException ignored) {
            // already gone
        }
        observer = null;
    }

    // ---- the row ---------------------------------------------------------------------------------------------

    private void bind(List<ShelfItem> items) {
        boolean hadFocus = panel.hasFocus();
        row.removeAllViews();
        row.setTag(items);
        long now = System.currentTimeMillis();
        for (int i = 0; i < items.size(); i++) {
            View card = card(items.get(i), now);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(CARD_W_DP), dp(CARD_H_DP + 46));
            if (i > 0) lp.setMarginStart(dp(ShelfItem.sectionBreak(items, i) ? 44 : 22));
            final int index = i;
            card.setOnFocusChangeListener((v, focused) -> focusCard(v, focused, index));
            card.setOnKeyListener(this::onCardKey);
            card.setOnClickListener(v -> open(items.get(index)));
            row.addView(card, lp);
        }
        if (focusIndex >= items.size()) focusIndex = 0;
        if (hadFocus) {
            View card = cardAt(focusIndex);
            if (card != null) card.requestFocus();
        }
        if (!shown) {
            shown = true;
            place();
            hero.setVisibility(View.INVISIBLE);
            panel.setVisibility(View.VISIBLE);
            panel.setTranslationX(dp(12));
            panel.animate().alpha(1f).translationX(0f).setDuration(240)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    /** The app has nothing to show now: back to the hero, keeping focus on the dock if it was in the row. */
    private void unbind() {
        boolean hadFocus = panel.hasFocus();
        row.removeAllViews();
        row.setTag(null);
        if (shown) {
            shown = false;
            panel.animate().cancel();
            panel.setAlpha(0f);
            panel.setVisibility(View.GONE);
            hero.setVisibility(View.VISIBLE);
        }
        if (hadFocus) host.returnToDock();
    }

    /** Bottom of the row a little above the dock, where the hero's bottom sits. */
    private void place() {
        panel.measure(View.MeasureSpec.makeMeasureSpec(((View) panel.getParent()).getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        panel.setY(bottomY - panel.getMeasuredHeight());
    }

    private int cardCount() {
        return row.getChildCount();
    }

    private View cardAt(int i) {
        return i >= 0 && i < row.getChildCount() ? row.getChildAt(i) : null;
    }

    private View card(ShelfItem item, long now) {
        FrameLayout cell = new FrameLayout(activity);
        cell.setClipChildren(false);
        cell.setFocusable(true);
        cell.setClickable(true);

        FrameLayout face = new FrameLayout(activity);
        face.setId(R.id.shelfFace);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(0x3DFFFFFF);
        face.setBackground(bg);
        face.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(14));
            }
        });
        face.setClipToOutline(true);
        // The focus ring is the face's foreground, so it is drawn over a picture that fills the card.
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(14));
        ring.setColor(0);
        face.setForeground(ring);
        cell.addView(face, new FrameLayout.LayoutParams(dp(CARD_W_DP), dp(CARD_H_DP)));

        Bitmap image = item.imageUri == null ? null : images.get(item.imageUri);
        if (image != null && item.wide) {
            ImageView picture = new ImageView(activity);
            picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
            picture.setImageBitmap(image);
            face.addView(picture, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        } else if (image != null) {
            ImageView logo = new ImageView(activity);
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            logo.setImageBitmap(image);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(96), dp(64), Gravity.CENTER);
            lp.bottomMargin = dp(6);
            face.addView(logo, lp);
        } else {
            // No image: the title's first character, large, as a placeholder logo.
            TextView initial = new TextView(activity);
            initial.setText(item.title.trim().substring(0, item.title.trim().offsetByCodePoints(0, 1)));
            initial.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
            initial.setTextColor(0xE6FFFFFF);
            font(initial, R.string.font_light);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
            face.addView(initial, lp);
        }

        String badge = item.shortBadge();
        if (badge != null) {
            TextView pill = new TextView(activity);
            pill.setText(badge);
            pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            pill.setTextColor(0xFFFFFFFF);
            pill.setPadding(dp(7), dp(2), dp(7), dp(2));
            GradientDrawable pb = new GradientDrawable();
            pb.setCornerRadius(dp(8));
            pb.setColor(0xCCE5484D);
            pill.setBackground(pb);
            font(pill, R.string.font_medium);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
            lp.topMargin = dp(8);
            lp.setMarginEnd(dp(8));
            face.addView(pill, lp);
        }

        int progress = item.progressAt(now);
        if (progress >= 0) {
            FrameLayout track = new FrameLayout(activity);
            track.setBackground(new ColorDrawable(0x40FFFFFF));
            View fill = new View(activity);
            fill.setBackground(new ColorDrawable(0xF2FFFFFF));
            track.addView(fill, new FrameLayout.LayoutParams(Math.round(dp(CARD_W_DP) * progress / 1000f),
                    ViewGroup.LayoutParams.MATCH_PARENT));
            face.addView(track, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3),
                    Gravity.BOTTOM));
        }

        LinearLayout text = new LinearLayout(activity);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(activity);
        title.setText(item.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTextColor(0xF2FFFFFF);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        font(title, R.string.font_medium);
        text.addView(title);
        String line = subtitle(item);
        if (line != null) {
            TextView sub = new TextView(activity);
            sub.setText(line);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            sub.setTextColor(0xA6FFFFFF);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            font(sub, R.string.font_regular);
            text.addView(sub);
        }
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(dp(CARD_W_DP), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        tp.topMargin = dp(CARD_H_DP + 8);
        cell.addView(text, tp);
        return cell;
    }

    /** "新闻联播 · 19:00–19:30", the programme alone, or only its times. */
    private String subtitle(ShelfItem item) {
        String times = item.hasTimes()
                ? clock.format(new Date(item.startMs)) + "–" + clock.format(new Date(item.endMs)) : null;
        String sub = item.subtitle == null || item.subtitle.trim().isEmpty() ? null : item.subtitle.trim();
        if (sub != null && times != null) return sub + " · " + times;
        return sub != null ? sub : times;
    }

    private void focusCard(View cell, boolean focused, int index) {
        View face = cell.findViewById(R.id.shelfFace);
        GradientDrawable bg = (GradientDrawable) face.getBackground();
        GradientDrawable ring = (GradientDrawable) face.getForeground();
        if (focused) {
            focusIndex = index;
            ring.setStroke(dp(2), 0xE6FFFFFF);
            bg.setColor(0x52FFFFFF);
            // Keep the focused card and its neighbour's edge in view.
            int left = cell.getLeft() - dp(68), right = cell.getRight() + dp(68) - scroller.getWidth();
            if (left < scroller.getScrollX()) scroller.smoothScrollTo(Math.max(0, left), 0);
            else if (right > scroller.getScrollX()) scroller.smoothScrollTo(right, 0);
        } else {
            ring.setStroke(0, 0);
            bg.setColor(0x3DFFFFFF);
        }
        face.animate().scaleX(focused ? 1.08f : 1f).scaleY(focused ? 1.08f : 1f)
                .translationZ(focused ? dp(14) : 0f).setDuration(focused ? 200 : 150)
                .setInterpolator(new DecelerateInterpolator(1.5f)).start();
        View text = ((ViewGroup) cell).getChildAt(1);
        text.animate().translationY(focused ? dp(6) : 0f).setDuration(200).start();
    }

    private boolean onCardKey(View v, int keyCode, KeyEvent event) {
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (!down && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) host.returnToDock();
                if (down && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) host.returnToDock();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) host.focusTopBar(v);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return down && row.indexOfChild(v) == 0;            // the row's start: stay
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return down && row.indexOfChild(v) == cardCount() - 1;   // the row's end: stay
            default:
                return false;
        }
    }

    private void open(ShelfItem item) {
        Intent intent = pkg == null ? null : ShelfSource.intent(item.intentUri, pkg);
        try {
            if (intent == null) throw new ActivityNotFoundException();
            activity.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(activity, activity.getString(R.string.cant_open, item.title), Toast.LENGTH_SHORT).show();
        }
    }

    private String headingFor(ProviderInfo provider) {
        CharSequence app = provider.applicationInfo.loadLabel(activity.getPackageManager());
        String title = ShelfSource.title(activity.getPackageManager(), provider);
        return title == null || title.trim().isEmpty() ? app.toString() : app + " · " + title.trim();
    }

    private void font(TextView view, int res) {
        try {
            view.setTypeface(UiFont.get(activity, res));
        } catch (Exception ignored) {
            // the system font is fine
        }
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    /** The items the row shows (its tag), for changed(). */
    @SuppressWarnings("unchecked")
    private List<ShelfItem> shownItems() {
        Object tag = row.getTag();
        return tag == null ? new ArrayList<>() : (List<ShelfItem>) tag;
    }
}
