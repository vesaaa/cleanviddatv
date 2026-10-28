package io.github.vesaaa.cleanviddatv;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The photo screensaver's scene (layout dream_photos, screensaver_style photos), after the tvOS aerials: the
 * pictures crossfade one after another, each slowly zooming and panning while it shows, with a small clock in the
 * top corner and the picture's name at the bottom. The pictures are Home's wallpapers plus the bundled photos and
 * every picture downloaded in the wallpaper gallery, so new ones come from the CDN or GitHub there.
 * Shared by ClockDream and ClockActivity, like ClockScene.
 */
final class PhotoScene implements Screensaver {

    /** How long each picture shows, and the crossfade into the next. */
    private static final long SLIDE_MS = 20_000L;
    private static final long FADE_MS = 2_500L;
    /** The slow zoom: from 1 to this, or back, over a slide and its fades. */
    private static final float ZOOM = 1.10f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final ImageView[] views = new ImageView[2];
    private final TextView caption;
    private final View clock;
    private final List<File> pictures;
    private final Map<String, String> names = new HashMap<>();
    private final int screenW, screenH;
    private int front;
    private int next;
    /** One number per decode request: a picture decoded for an older one is dropped. Main thread only. */
    private int generation;
    private boolean running;
    private final List<ObjectAnimator> fades = new ArrayList<>();

    private final Runnable advance = this::showNext;

    private PhotoScene(View root, List<File> pictures, Context ctx) {
        views[0] = root.findViewById(R.id.photoFront);
        views[1] = root.findViewById(R.id.photoBack);
        caption = root.findViewById(R.id.photoCaption);
        clock = root.findViewById(R.id.photoClock);
        // Above both pictures whichever came in last; no outline, so no shadow from the elevation.
        for (View overlay : new View[]{root.findViewById(R.id.photoScrim), clock, caption}) {
            overlay.setOutlineProvider(null);
            overlay.setZ(2f);
        }
        this.pictures = new ArrayList<>(pictures);
        Collections.shuffle(this.pictures, random);
        for (File dir : new LinkedHashSet<>(parents(pictures))) {
            for (Map.Entry<String, String> e : WallpaperCaptions.read(dir).entrySet()) {
                File f = new File(dir, e.getKey());
                names.put(f.getPath(), BuiltinWallpapers.label(Collections.singletonMap(f.getName(), e.getValue()), f));
            }
        }
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
    }

    /** The scene, or null when there is no picture to show (the clock is shown then). */
    static PhotoScene create(View root, Context ctx, Config cfg) {
        List<File> photos = pictures(cfg.wallpapers,
                BuiltinWallpapers.group(BuiltinWallpapers.all(ctx), BuiltinWallpapers.PHOTOS), OnlineWallpapers.downloaded(ctx));
        return photos.isEmpty() ? null : new PhotoScene(root, photos, ctx);
    }

    /** Home's pictures first, then the bundled photos and the downloaded ones, each file once. No Android types. */
    static List<File> pictures(List<File> wallpapers, List<File> builtinPhotos, List<File> downloaded) {
        LinkedHashSet<File> all = new LinkedHashSet<>(wallpapers);
        all.addAll(builtinPhotos);
        all.addAll(downloaded);
        return new ArrayList<>(all);
    }

    private static List<File> parents(List<File> files) {
        List<File> out = new ArrayList<>();
        for (File f : files) out.add(f.getParentFile());
        return out;
    }

    @Override
    public void start() {
        running = true;
        clock.setAlpha(0f);
        clock.animate().alpha(1f).setDuration(1200).start();
        showNext();
    }

    @Override
    public void stop() {
        running = false;
        generation++;
        handler.removeCallbacksAndMessages(null);
        for (ImageView v : views) v.animate().cancel();
        for (ObjectAnimator a : fades) a.cancel();
        fades.clear();
        caption.animate().cancel();
    }

    private void showNext() {
        if (!running) return;
        File file = pictures.get(next);
        next = (next + 1) % pictures.size();
        int request = ++generation;
        new Thread(() -> {
            Bitmap bmp = ClockScene.decode(file, screenW, screenH);
            handler.post(() -> {
                if (request != generation || !running) {
                    if (bmp != null) bmp.recycle();
                    return;
                }
                if (bmp == null) {
                    // An unreadable picture: try the next one shortly, without a black gap.
                    handler.postDelayed(advance, 500);
                    return;
                }
                show(bmp, names.get(file.getPath()));
                handler.postDelayed(advance, SLIDE_MS);
            });
        }, "photo-dream").start();
    }

    private void show(Bitmap bmp, String name) {
        ImageView incoming = views[1 - front], outgoing = views[front];
        front = 1 - front;
        Drawable old = incoming.getDrawable();
        incoming.setImageBitmap(bmp);
        if (old instanceof BitmapDrawable) {
            Bitmap previous = ((BitmapDrawable) old).getBitmap();
            if (previous != null && previous != bmp) previous.recycle();
        }
        // The new picture fades in over the old one; the shade, the clock and the name sit above both (constructor).
        incoming.setZ(1f);
        outgoing.setZ(0f);

        // Ken Burns: zoom in or out, drifting towards a random side, for the whole time the picture is up.
        boolean zoomIn = random.nextBoolean();
        float from = zoomIn ? 1f : ZOOM, to = zoomIn ? ZOOM : 1f;
        float driftX = (random.nextFloat() - 0.5f) * screenW * (ZOOM - 1f);
        float driftY = (random.nextFloat() - 0.5f) * screenH * (ZOOM - 1f);
        incoming.animate().cancel();
        incoming.setScaleX(from);
        incoming.setScaleY(from);
        incoming.setTranslationX(zoomIn ? 0f : driftX);
        incoming.setTranslationY(zoomIn ? 0f : driftY);
        incoming.setAlpha(0f);
        incoming.animate()
                .scaleX(to).scaleY(to)
                .translationX(zoomIn ? driftX : 0f).translationY(zoomIn ? driftY : 0f)
                .setDuration(SLIDE_MS + FADE_MS)
                .setInterpolator(new LinearInterpolator())
                .start();
        // The fade has its own, shorter animator: one ViewPropertyAnimator runs one duration for all it moves.
        ObjectAnimator fadeIn = ObjectAnimator.ofFloat(incoming, View.ALPHA, 0f, 1f).setDuration(FADE_MS);
        fadeIn.start();
        fades.add(fadeIn);
        ObjectAnimator fadeOut = ObjectAnimator.ofFloat(outgoing, View.ALPHA, outgoing.getAlpha(), 0f).setDuration(FADE_MS);
        fadeOut.start();
        fades.add(fadeOut);
        while (fades.size() > 4) fades.remove(0);

        caption.animate().cancel();
        caption.animate().alpha(0f).setDuration(400).withEndAction(() -> {
            caption.setText(name == null ? "" : name);
            if (name != null) caption.animate().alpha(1f).setDuration(1200).start();
        }).start();
        // Nudge the clock a little with each picture, so it never sits on the same pixels for long.
        clock.animate().translationX(-random.nextInt(Math.max(1, screenW / 60)))
                .translationY(random.nextInt(Math.max(1, screenH / 40))).setDuration(FADE_MS).start();
    }
}
