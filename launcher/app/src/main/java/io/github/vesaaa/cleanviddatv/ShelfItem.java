package io.github.vesaaa.cleanviddatv;

import java.util.List;

/**
 * One card of an app's top shelf (docs/top-shelf.md): a row of the provider's content://&lt;authority&gt;/items.
 * No Android types, so the rules below are tested on the JVM.
 */
final class ShelfItem {

    /** The most rows a shelf shows; the rest are dropped. */
    static final int MAX = 12;
    /** After the earliest programme ends, wait this long before asking the app again. */
    static final long REFRESH_SLACK_MS = 5_000L;
    static final String SECTION_RECENT = "recent", SECTION_FAVORITE = "favorite";

    final long id;
    final String title, subtitle, imageUri, badge, section, intentUri;
    final boolean wide;
    /** 0 to 1000, or -1 when the app has no programme guide for it. */
    final int progress;
    final long startMs, endMs;

    ShelfItem(long id, String title, String subtitle, String imageUri, boolean wide, int progress, long startMs,
              long endMs, String badge, String section, String intentUri) {
        this.id = id;
        this.title = title;
        this.subtitle = subtitle;
        this.imageUri = imageUri;
        this.wide = wide;
        this.progress = progress;
        this.startMs = startMs;
        this.endMs = endMs;
        this.badge = badge;
        this.section = section;
        this.intentUri = intentUri;
    }

    /** A row the shelf can show: it has a title and something to open. */
    boolean usable() {
        return title != null && !title.trim().isEmpty() && intentUri != null && !intentUri.trim().isEmpty();
    }

    boolean hasTimes() {
        return startMs > 0 && endMs > startMs;
    }

    /** The progress at this moment, 0 to 1000: worked out from the programme's times when it has them. */
    int progressAt(long now) {
        if (hasTimes()) {
            if (now <= startMs) return 0;
            if (now >= endMs) return 1000;
            return (int) ((now - startMs) * 1000 / (endMs - startMs));
        }
        return progress < 0 ? -1 : Math.min(1000, progress);
    }

    /** A badge of at most 4 characters, or null. */
    String shortBadge() {
        if (badge == null || badge.trim().isEmpty()) return null;
        String b = badge.trim();
        return b.length() > 4 ? b.substring(0, 4) : b;
    }

    /** Only the provider's own images (content://authority/image/...): nothing from the network or another app. */
    static boolean ownImage(String uri, String authority) {
        return uri != null && authority != null && uri.startsWith("content://" + authority + "/image/")
                && !uri.contains("..");
    }

    /**
     * When to query the app again without being told: 5 s after the first programme that ends after now, or 0 when
     * no card has a programme still running.
     */
    static long nextRefresh(List<ShelfItem> items, long now) {
        long earliest = 0;
        for (ShelfItem item : items) {
            if (!item.hasTimes() || item.endMs <= now) continue;
            if (earliest == 0 || item.endMs < earliest) earliest = item.endMs;
        }
        return earliest == 0 ? 0 : earliest + REFRESH_SLACK_MS;
    }

    /** Whether a gap goes before card i: where the recent ones end and the favourites start. */
    static boolean sectionBreak(List<ShelfItem> items, int i) {
        if (i <= 0 || i >= items.size()) return false;
        String before = items.get(i - 1).section, here = items.get(i).section;
        return before != null && here != null && !before.equals(here);
    }
}
