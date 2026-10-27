package io.github.vesaaa.cleanviddatv;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Home's own backdrop when no wallpaper is set (config.xml soft_backdrop): the background colour with a few wide,
 * faint pools of blue, violet and teal light, like the tvOS home screen. Drawn from gradients, so it costs no
 * picture in memory; the root draws it once per frame like the plain colour it replaces.
 */
final class Backdrop extends Drawable {
    /** Each pool: centre x and y and radius as fractions of the width, then its colour (alpha included). */
    private static final float[][] POOLS = {
            {0.80f, 0.05f, 0.55f},
            {0.12f, 0.00f, 0.42f},
            {0.50f, 1.05f, 0.60f},
    };
    private static final int[] COLOURS = {0x5C2C4C8C, 0x4A4A2A70, 0x3D17505E};

    private final int base;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final RadialGradient[] shaders = new RadialGradient[POOLS.length];

    Backdrop(int baseColor) {
        base = baseColor | 0xFF000000;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        float w = bounds.width(), h = bounds.height();
        for (int i = 0; i < POOLS.length; i++) {
            float r = Math.max(1f, POOLS[i][2] * w);
            shaders[i] = new RadialGradient(bounds.left + POOLS[i][0] * w, bounds.top + POOLS[i][1] * h, r,
                    COLOURS[i], COLOURS[i] & 0x00FFFFFF, Shader.TileMode.CLAMP);
        }
    }

    @Override
    public void draw(Canvas canvas) {
        canvas.drawColor(base);
        if (shaders[0] == null) onBoundsChange(getBounds());
        for (RadialGradient shader : shaders) {
            paint.setShader(shader);
            canvas.drawRect(getBounds(), paint);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        // Opaque by design: it is the bottom layer of Home.
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.OPAQUE;
    }
}
