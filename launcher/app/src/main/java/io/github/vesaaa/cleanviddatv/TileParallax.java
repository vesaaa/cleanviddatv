package io.github.vesaaa.cleanviddatv;

import android.animation.ValueAnimator;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/**
 * tvOS parallax on the focused tile (config.xml parallax_tiles). A TV remote has no touch surface to tilt the tile
 * with, so the tilt comes from the move instead: the tile arrives tipped towards the key that brought focus to it
 * and springs flat, then sways slowly while it keeps focus. The picture inside moves against the tilt (clipped by
 * the card's outline), and the listener moves the highlight's sheen with it, so the tile reads as layered glass.
 * The banner is zoomed 1.035 on focus, which leaves about 3dp of picture past each edge to move into.
 */
final class TileParallax {

    interface Listener {
        /** The tilt as -1..1 on each axis (right, down), for the highlight's sheen. */
        void onTilt(float x, float y);
    }

    /** The last direction key pressed on any tile: where the next focused tile arrives from. */
    private static int lastKey;

    /** Degrees the tile arrives tipped by, and the sway's degrees either side of flat. */
    private static final float ARRIVE_DEG = 5f;
    private static final float SWAY_DEG_Y = 1.6f, SWAY_DEG_X = 1.0f;
    /** How far the picture moves against the tilt, in dp, at a full tilt. */
    private static final float SHIFT_DP = 3f;
    /** One sway cycle; the vertical one runs at half the rate, so the path is a slow figure of eight. */
    private static final long SWAY_MS = 7000L;
    private static final long SWAY_DELAY_MS = 700L;

    private final View card;
    /** The view that holds focus: the card itself, or the cell around it (the top shelf's cards). */
    private final View focusOwner;
    private final View[] layers;
    private final Listener listener;
    private final float shiftPx;
    private final SpringAnimation springY, springX;
    private ValueAnimator sway;
    private final Runnable startSway = this::startSway;

    /** layers: the views inside the card that move against the tilt (the banner and the icon). */
    TileParallax(View card, Listener listener, View... layers) {
        this(card, card, listener, layers);
    }

    TileParallax(View card, View focusOwner, Listener listener, View... layers) {
        this.card = card;
        this.focusOwner = focusOwner;
        this.layers = layers;
        this.listener = listener;
        float density = card.getResources().getDisplayMetrics().density;
        shiftPx = SHIFT_DP * density;
        // The default camera exaggerates the perspective on a tile this wide; further away it tips like a card.
        card.setCameraDistance(12000f * density);
        springY = spring(DynamicAnimation.ROTATION_Y);
        springX = spring(DynamicAnimation.ROTATION_X);
    }

    private SpringAnimation spring(DynamicAnimation.ViewProperty property) {
        SpringAnimation s = new SpringAnimation(card, property, 0f);
        s.getSpring().setStiffness(SpringForce.STIFFNESS_VERY_LOW * 3).setDampingRatio(0.45f);
        s.addUpdateListener((anim, value, velocity) -> follow());
        return s;
    }

    /** Called with every direction key on a tile, before focus moves. */
    static void keyPressed(int keyCode) {
        lastKey = keyCode;
    }

    void focus() {
        float dx = 0f, dy = 0f;
        if (lastKey == KeyEvent.KEYCODE_DPAD_RIGHT) dx = 1f;
        else if (lastKey == KeyEvent.KEYCODE_DPAD_LEFT) dx = -1f;
        else if (lastKey == KeyEvent.KEYCODE_DPAD_DOWN) dy = 1f;
        else if (lastKey == KeyEvent.KEYCODE_DPAD_UP) dy = -1f;
        lastKey = 0;
        stopSway();
        // Tipped back on the side it came from, as if pushed there by the move.
        card.setRotationY(-dx * ARRIVE_DEG);
        card.setRotationX(dy * ARRIVE_DEG);
        follow();
        springY.animateToFinalPosition(0f);
        springX.animateToFinalPosition(0f);
        card.postDelayed(startSway, SWAY_DELAY_MS);
    }

    void blur() {
        stopSway();
        springY.animateToFinalPosition(0f);
        springX.animateToFinalPosition(0f);
    }

    /** Flat at once: a rebind of a tile that is not focused. */
    void reset() {
        stopSway();
        springY.cancel();
        springX.cancel();
        card.setRotationX(0f);
        card.setRotationY(0f);
        for (View layer : layers) {
            layer.setTranslationX(0f);
            layer.setTranslationY(0f);
        }
    }

    private void startSway() {
        if (!focusOwner.isFocused() || !card.isAttachedToWindow()) return;
        float fromY = card.getRotationY(), fromX = card.getRotationX();
        sway = ValueAnimator.ofFloat(0f, (float) (2 * Math.PI));
        sway.setDuration(SWAY_MS);
        sway.setRepeatCount(ValueAnimator.INFINITE);
        sway.setInterpolator(new LinearInterpolator());
        sway.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            // The first cycle eases in from wherever the spring left the tile.
            float in = Math.min(1f, a.getCurrentPlayTime() / (float) SWAY_MS);
            card.setRotationY(fromY * (1f - in) + SWAY_DEG_Y * (float) Math.sin(t) * in);
            card.setRotationX(fromX * (1f - in) + SWAY_DEG_X * (float) Math.sin(2 * t) * in);
            follow();
        });
        sway.start();
    }

    private void stopSway() {
        card.removeCallbacks(startSway);
        if (sway != null) {
            sway.cancel();
            sway = null;
        }
    }

    /** The picture and the sheen follow the tile's tilt. */
    private void follow() {
        float x = -card.getRotationY() / ARRIVE_DEG, y = card.getRotationX() / ARRIVE_DEG;
        for (View layer : layers) {
            layer.setTranslationX(-x * shiftPx);
            layer.setTranslationY(-y * shiftPx);
        }
        if (listener != null && focusOwner.isFocused()) listener.onTilt(x, y);
    }
}
