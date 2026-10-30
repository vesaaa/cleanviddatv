package io.github.vesaaa.cleanviddatv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.widget.TextView;

/**
 * A settings row: its label (the TextView's own text) on the left and, on the right, either the current value
 * with an arrow (for a row that opens a list or a page) or a switch (for one that turns something on and off).
 * The value and the switch follow the row's focus colours, so they stay readable on the white focused card.
 */
public final class SettingsItem extends TextView {

    private CharSequence value;
    private boolean arrow;
    /** null: no switch; otherwise the switch's state. */
    private Boolean toggle;
    private int basePaddingEnd = -1;
    private final TextPaint valuePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shape = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;

    public SettingsItem(Context context) {
        this(context, null);
    }

    public SettingsItem(Context context, AttributeSet attrs) {
        this(context, attrs, android.R.attr.textViewStyle);
    }

    /** A row made in code, styled like the layout's (R.style.SettingsRow); its layout params are the caller's. */
    static SettingsItem row(Context context) {
        return new SettingsItem(context, null, 0, R.style.SettingsRow);
    }

    public SettingsItem(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public SettingsItem(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        density = getResources().getDisplayMetrics().density;
        valuePaint.setTextSize(getTextSize() * 0.92f);
        valuePaint.setTypeface(getTypeface());
    }

    /** A value on the right, with an arrow when the row opens something. */
    public void setValue(CharSequence value, boolean arrow) {
        this.value = value;
        this.arrow = arrow;
        this.toggle = null;
        updatePadding();
    }

    /** An arrow only: the row opens a page or a confirmation. */
    public void setArrow() {
        setValue(null, true);
    }

    /** A switch on the right, on or off. */
    public void setToggle(boolean on) {
        this.value = null;
        this.arrow = false;
        this.toggle = on;
        updatePadding();
    }

    CharSequence value() {
        return value;
    }

    private void updatePadding() {
        if (basePaddingEnd < 0) basePaddingEnd = getPaddingEnd();
        float extra = 0;
        if (toggle != null) extra = dp(44) + dp(16);
        else {
            if (arrow) extra += dp(18);
            if (!TextUtils.isEmpty(value)) extra += Math.min(valuePaint.measureText(value.toString()), dp(360)) + dp(16);
        }
        setPaddingRelative(getPaddingStart(), getPaddingTop(), basePaddingEnd + Math.round(extra), getPaddingBottom());
        invalidate();
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int end = basePaddingEnd < 0 ? getPaddingEnd() : basePaddingEnd;
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        int text = getCurrentTextColor();
        float centreY = getHeight() / 2f;
        if (toggle != null) {
            float w = dp(44), h = dp(24);
            float right = rtl ? end + w : getWidth() - end;
            rect.set(right - w, centreY - h / 2, right, centreY + h / 2);
            boolean focused = isFocused();
            shape.setColor(toggle ? 0xFF3E9E62 : (focused ? 0x33000000 : 0x33FFFFFF));
            canvas.drawRoundRect(rect, h / 2, h / 2, shape);
            float knob = h / 2 - dp(3);
            float cx = toggle ? rect.right - h / 2 : rect.left + h / 2;
            shape.setColor(0xFFFFFFFF);
            canvas.drawCircle(cx, centreY, knob, shape);
            return;
        }
        float x = rtl ? end : getWidth() - end;
        valuePaint.setColor(text);
        if (arrow) {
            // A thin chevron, pointing the way the page opens.
            shape.setColor(text);
            shape.setStrokeWidth(dp(2));
            shape.setStyle(Paint.Style.STROKE);
            shape.setStrokeCap(Paint.Cap.ROUND);
            float s = dp(5);
            float cx = rtl ? x + dp(6) : x - dp(6);
            float dir = rtl ? -1 : 1;
            canvas.drawLine(cx - dir * s / 2, centreY - s, cx + dir * s / 2, centreY, shape);
            canvas.drawLine(cx + dir * s / 2, centreY, cx - dir * s / 2, centreY + s, shape);
            shape.setStyle(Paint.Style.FILL);
            x = rtl ? x + dp(18) : x - dp(18);
        }
        if (!TextUtils.isEmpty(value)) {
            valuePaint.setAlpha(isFocused() ? 200 : 150);
            CharSequence shown = TextUtils.ellipsize(value, valuePaint, dp(360), TextUtils.TruncateAt.END);
            float w = valuePaint.measureText(shown.toString());
            float baseline = centreY - (valuePaint.descent() + valuePaint.ascent()) / 2;
            canvas.drawText(shown.toString(), rtl ? x : x - w, baseline, valuePaint);
        }
    }

    private float dp(float v) {
        return v * density;
    }
}
