package io.github.vesaaa.cleanviddatv;

import android.content.Context;
import android.view.View;

import java.util.function.IntFunction;

/** One of the screensaver scenes ClockDream and ClockActivity show: ClockScene or PhotoScene. */
interface Screensaver {
    void start();

    void stop();

    /**
     * The scene screensaver_style asks for, in the layout setContent shows (it returns the window's root view): the
     * photos, or the clock when that is the style, the configuration cannot be read or there is no picture.
     */
    static Screensaver create(Context ctx, IntFunction<View> setContent) {
        Config cfg = null;
        try {
            cfg = Config.load(ctx);
        } catch (RuntimeException ignored) {
            // the clock needs no configuration
        }
        if (cfg != null && Config.SCREENSAVER_PHOTOS.equals(cfg.screensaverStyle)) {
            PhotoScene photos = PhotoScene.create(setContent.apply(R.layout.dream_photos), ctx, cfg);
            if (photos != null) return photos;
        }
        ClockScene clock = new ClockScene(setContent.apply(R.layout.dream_clock));
        clock.useHomeWallpaper(ctx);
        return clock;
    }
}
