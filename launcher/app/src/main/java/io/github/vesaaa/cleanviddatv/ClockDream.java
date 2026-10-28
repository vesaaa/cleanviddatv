package io.github.vesaaa.cleanviddatv;

import android.service.dreams.DreamService;

/**
 * Screensaver: the one screensaver_style picks (Screensaver.create), the photos (PhotoScene) or the thin clock
 * and date over a slow ambient glow (ClockScene). No network: the pictures are files already on the TV.
 */
public class ClockDream extends DreamService {

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // the date in Chinese, like Home's, on any system language
    }

    private Screensaver scene;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        setScreenBright(false);
        // A configuration change between attach and now puts the system's language back as the default locale,
        // which the clock's date (TextClock) follows; set ours again before it is inflated.
        LauncherLocale.wrap(getBaseContext());
        scene = Screensaver.create(this, layout -> {
            setContentView(layout);
            return getWindow().getDecorView();
        });
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        scene.start();
    }

    @Override
    public void onDreamingStopped() {
        scene.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (scene != null) scene.stop();
        super.onDetachedFromWindow();
    }
}
