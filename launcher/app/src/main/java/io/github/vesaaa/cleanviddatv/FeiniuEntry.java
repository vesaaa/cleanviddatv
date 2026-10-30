package io.github.vesaaa.cleanviddatv;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One film, series or episode from 飞牛影视, and how the top shelf shows the server's lists as cards: what is being
 * watched first, then what was added lately. No Android types, so the rules are tested on the JVM.
 */
final class FeiniuEntry {

    /** Being watched first, at most this many, so the newest additions still get a place. */
    static final int MAX_PLAYING = 4;
    static final String SECTION_PLAYING = ShelfItem.SECTION_RECENT, SECTION_ADDED = ShelfItem.SECTION_FAVORITE;
    /** Less than this left and it counts as watched: it is not offered to go on with. */
    static final long DONE_SECONDS = 60;

    final String guid, type, title, tvTitle, poster, parentGuid, releaseDate, vote;
    final int season, episode, posterWidth, posterHeight;
    /** Seconds watched and the length, for the continue-watching list. */
    final long position, duration;

    FeiniuEntry(String guid, String type, String title, String tvTitle, int season, int episode, long position,
                long duration, String poster, int posterWidth, int posterHeight, String parentGuid,
                String releaseDate, String vote) {
        this.guid = guid == null ? "" : guid;
        this.type = type == null ? "" : type;
        this.title = title == null ? "" : title;
        this.tvTitle = tvTitle == null ? "" : tvTitle;
        this.season = season;
        this.episode = episode;
        this.position = position;
        this.duration = duration;
        this.poster = poster == null ? "" : poster;
        this.posterWidth = posterWidth;
        this.posterHeight = posterHeight;
        this.parentGuid = parentGuid == null ? "" : parentGuid;
        this.releaseDate = releaseDate == null ? "" : releaseDate;
        this.vote = vote == null ? "" : vote;
    }

    boolean isEpisode() {
        return "Episode".equalsIgnoreCase(type);
    }

    /** The poster is landscape (an episode still, a video frame) and can fill a card as it is. */
    boolean widePoster() {
        return posterWidth > 0 && posterHeight > 0 && posterWidth >= posterHeight;
    }

    /** The series for an episode, the title otherwise; a file name loses its extension. */
    String showTitle() {
        String t = isEpisode() && !tvTitle.trim().isEmpty() ? tvTitle.trim() : title.trim();
        return t.replaceFirst("(?i)\\.(mkv|mp4|avi|ts|m2ts|mov|wmv|rmvb|flv|iso|webm)$", "");
    }

    /** The key two entries of the same series share, so a series shows once. */
    String showKey() {
        return isEpisode() ? "tv:" + showTitle() : "id:" + guid;
    }

    /** 0 to 1000 watched, or -1 without a length. */
    int progress() {
        if (duration <= 0) return -1;
        return (int) Math.max(0, Math.min(1000, position * 1000 / duration));
    }

    boolean unfinished() {
        return duration > 0 && position > 0 && duration - position > DONE_SECONDS;
    }

    /** 第2季 第5集 · 剩 12 分钟, or just the time left for a film. */
    String playingSubtitle() {
        StringBuilder sb = new StringBuilder();
        if (isEpisode() && episode > 0) {
            if (season > 1) sb.append("第").append(season).append("季 ");
            sb.append("第").append(episode).append("集");
        }
        long left = Math.max(0, duration - position);
        if (duration > 0) {
            if (sb.length() > 0) sb.append(" · ");
            long minutes = (left + 59) / 60;
            sb.append(minutes <= 1 ? "即将看完" : "剩 " + minutes + " 分钟");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 2026 · 7.5 分 for something new in the library. */
    String addedSubtitle() {
        StringBuilder sb = new StringBuilder();
        if (releaseDate.length() >= 4 && releaseDate.substring(0, 4).matches("[0-9]{4}")) {
            sb.append(releaseDate, 0, 4);
        }
        double score = 0;
        try {
            score = Double.parseDouble(vote);
        } catch (NumberFormatException ignored) {
            // no score
        }
        if (score > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(String.format(Locale.ROOT, "%.1f 分", score));
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** A stable row id for the card from the entry's guid. */
    long rowId() {
        long h = 1125899906842597L;
        for (int i = 0; i < guid.length(); i++) h = 31 * h + guid.charAt(i);
        return h & Long.MAX_VALUE;
    }

    /**
     * The cards in order: unfinished ones being watched (one per series, at most MAX_PLAYING), then the newest
     * additions that are not already there, up to ShelfItem.MAX in all.
     */
    static List<Pick> cards(List<FeiniuEntry> playing, List<FeiniuEntry> added) {
        List<Pick> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (FeiniuEntry e : playing) {
            if (out.size() >= MAX_PLAYING) break;
            if (e.guid.isEmpty() || !e.unfinished() || !seen.add(e.showKey())) continue;
            out.add(new Pick(e, true));
        }
        for (FeiniuEntry e : added) {
            if (out.size() >= ShelfItem.MAX) break;
            if (e.guid.isEmpty()) continue;
            String key = "TV".equalsIgnoreCase(e.type) ? "tv:" + e.showTitle() : "id:" + e.guid;
            if (!seen.add(key)) continue;
            out.add(new Pick(e, false));
        }
        return out;
    }

    /** One card: the entry, and whether it is being watched (else newly added). */
    static final class Pick {
        final FeiniuEntry entry;
        final boolean playing;

        Pick(FeiniuEntry entry, boolean playing) {
            this.entry = entry;
            this.playing = playing;
        }
    }
}
