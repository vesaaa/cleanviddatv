package io.github.vesaaa.cleanviddatv;

import android.content.Context;
import android.text.format.DateFormat;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextClock;

/**
 * config.xml clock_24h: the launcher's clocks in 24-hour time whatever the TV's own 12/24-hour setting. The TextClocks
 * in the layouts carry both formats (the AM/PM one an empty 24-hour format); forcing 24 hours gives each its 24-hour
 * format for 12 hours too.
 */
final class Clock24 {

    private Clock24() { }

    /** Every TextClock under view shows its 24-hour format when force is set; nothing changes otherwise. */
    static void apply(View view, boolean force) {
        if (!force || view == null) return;
        if (view instanceof TextClock) {
            TextClock clock = (TextClock) view;
            clock.setFormat12Hour(clock.getFormat24Hour());
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) apply(group.getChildAt(i), true);
        }
    }

    /** Whether times written out in code use 24 hours. */
    static boolean is24(Context ctx, boolean force) {
        return force || DateFormat.is24HourFormat(ctx);
    }
}
