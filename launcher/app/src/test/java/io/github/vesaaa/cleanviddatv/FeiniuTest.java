package io.github.vesaaa.cleanviddatv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class FeiniuTest {

    private static FeiniuEntry episode(String guid, String show, int season, int ep, long pos, long dur) {
        return new FeiniuEntry(guid, "Episode", "第" + ep + "集标题", show, season, ep, pos, dur, "/a/b.webp", 640,
                360, "p-" + show, "", "");
    }

    private static FeiniuEntry movie(String guid, String title, long pos, long dur, int w, int h) {
        return new FeiniuEntry(guid, "Movie", title, "", 0, 0, pos, dur, "/m/" + guid + ".webp", w, h, "",
                "2026-06-19", "7.5");
    }

    @Test
    public void signatureMatchesTheServersScheme() {
        // Worked out with Python's hashlib the way the server checks it.
        assertEquals("nonce=123456&timestamp=1700000000000&sign=4fdbb09b3afdb58c6aca0a8a81d207a2",
                Feiniu.authx(Feiniu.DEFAULT_KEY, Feiniu.DEFAULT_SECRET, "/v/api/v1/play/list", null, "123456",
                        1700000000000L));
        assertEquals("nonce=654321&timestamp=1700000000001&sign=8570920e39b044472d2a9b91fc346316",
                Feiniu.authx(Feiniu.DEFAULT_KEY, Feiniu.DEFAULT_SECRET, "/v/api/v1/login", "{\"username\":\"a\"}",
                        "654321", 1700000000001L));
    }

    @Test
    public void typedAddressesBecomeABase() {
        assertEquals("http://192.168.1.9:5666", Feiniu.normalise("192.168.1.9"));
        assertEquals("http://192.168.1.9:5666", Feiniu.normalise(" http://192.168.1.9:5666/v "));
        assertEquals("http://nas.local:8080", Feiniu.normalise("nas.local:8080/v/"));
        assertEquals("https://nas.example.com:5667", Feiniu.normalise("HTTPS://nas.example.com:5667"));
        assertNull(Feiniu.normalise(""));
        assertNull(Feiniu.normalise("nas local"));
        assertNull(Feiniu.normalise("http://"));
        assertEquals("192.168.1.9:5666", Feiniu.display("http://192.168.1.9:5666"));
    }

    @Test
    public void neighboursCoverTheSubnetNearestFirst() {
        List<String> n = FeiniuScan.neighbours(new byte[]{(byte) 192, (byte) 168, 1, 10});
        assertEquals(253, n.size());
        assertEquals("192.168.1.9", n.get(0));
        assertEquals("192.168.1.11", n.get(1));
        assertFalse(n.contains("192.168.1.10"));
        assertFalse(n.contains("192.168.1.0"));
        assertFalse(n.contains("192.168.1.255"));
        assertTrue(n.contains("192.168.1.254"));
    }

    @Test
    public void episodeSubtitleSaysWhereAndHowLong() {
        assertEquals("第5集 · 剩 12 分钟", episode("e", "藏锋", 1, 5, 2000, 2700).playingSubtitle());
        assertEquals("第2季 第1集 · 即将看完", episode("e", "藏锋", 2, 1, 2650, 2700).playingSubtitle());
        assertEquals("剩 90 分钟", movie("m", "抓特务", 600, 6000, 2000, 3000).playingSubtitle());
        assertEquals("2026 · 7.5 分", movie("m", "抓特务", 0, 0, 2000, 3000).addedSubtitle());
    }

    @Test
    public void titlesNameTheSeriesAndDropFileExtensions() {
        assertEquals("藏锋", episode("e", "藏锋", 1, 1, 10, 100).showTitle());
        assertEquals("2026.2160p.WEB-DL", movie("v", "2026.2160p.WEB-DL.mkv", 0, 0, 1, 1).showTitle());
        assertEquals(500, episode("e", "x", 1, 1, 50, 100).progress());
        assertEquals(-1, movie("m", "x", 0, 0, 1, 1).progress());
    }

    @Test
    public void cardsShowEachSeriesOnceAndSkipWhatIsWatched() {
        List<FeiniuEntry> playing = Arrays.asList(
                episode("e1", "藏锋", 1, 3, 800, 2700),
                episode("e2", "藏锋", 1, 2, 800, 2700),        // same series: dropped
                episode("e3", "兰香如故", 1, 1, 2766, 2796),   // 30 s left: watched
                movie("m1", "抓特务", 351, 7037, 3840, 1608));
        List<FeiniuEntry> added = Arrays.asList(
                new FeiniuEntry("t1", "TV", "藏锋", "", 1, 0, 0, 0, "/p.webp", 1200, 1680, "", "2026-08-17", "8"),
                movie("m1", "抓特务", 0, 0, 2000, 3000),        // already on the shelf
                movie("m2", "新电影", 0, 0, 2000, 3000));
        List<FeiniuEntry.Pick> cards = FeiniuEntry.cards(playing, added);
        List<String> ids = new ArrayList<>();
        for (FeiniuEntry.Pick p : cards) ids.add(p.entry.guid + (p.playing ? "+" : ""));
        assertEquals(Arrays.asList("e1+", "m1+", "m2"), ids);
    }

    @Test
    public void cardsStopAtTheShelfLimits() {
        List<FeiniuEntry> playing = new ArrayList<>(), added = new ArrayList<>();
        for (int i = 0; i < 20; i++) playing.add(episode("e" + i, "剧" + i, 1, 1, 10, 1000));
        for (int i = 0; i < 20; i++) added.add(movie("m" + i, "片" + i, 0, 0, 2, 3));
        List<FeiniuEntry.Pick> cards = FeiniuEntry.cards(playing, added);
        assertEquals(ShelfItem.MAX, cards.size());
        int watching = 0;
        for (FeiniuEntry.Pick p : cards) if (p.playing) watching++;
        assertEquals(FeiniuEntry.MAX_PLAYING, watching);
    }

    @Test
    public void rowIdsAreStable() {
        assertEquals(movie("abc", "x", 0, 0, 1, 1).rowId(), movie("abc", "y", 0, 0, 1, 1).rowId());
        assertTrue(movie("abc", "x", 0, 0, 1, 1).rowId() >= 0);
    }
}
