package io.github.vesaaa.cleanviddatv;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.SystemClock;

/**
 * config.xml focus_sound: a short tick when focus moves between tiles and a soft click on OK, as tvOS does. The two
 * sounds (res/raw, under 10 KB) stay loaded in a SoundPool while Home is up; release() frees them.
 */
final class FocusSound {

    /** A held direction key repeats every ~50 ms; ticking on each one would buzz. */
    private static final long MIN_GAP_MS = 45L;
    private static final float VOLUME = 0.35f;

    private final SoundPool pool;
    private final int tick, click;
    private long lastTick;

    FocusSound(Context ctx) {
        pool = new SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        tick = pool.load(ctx, R.raw.focus_tick, 1);
        click = pool.load(ctx, R.raw.select_click, 1);
    }

    void tick() {
        long now = SystemClock.uptimeMillis();
        if (now - lastTick < MIN_GAP_MS) return;
        lastTick = now;
        pool.play(tick, VOLUME, VOLUME, 1, 0, 1f);
    }

    void click() {
        pool.play(click, VOLUME, VOLUME, 1, 0, 1f);
    }

    void release() {
        pool.release();
    }
}
