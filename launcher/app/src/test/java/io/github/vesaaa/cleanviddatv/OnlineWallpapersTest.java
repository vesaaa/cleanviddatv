package io.github.vesaaa.cleanviddatv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class OnlineWallpapersTest {

    private static String entry(String id, String file, String thumb) {
        return "{\"id\":\"" + id + "\",\"title\":\"湖\",\"credit\":\"A\",\"license\":\"CC0 1.0\",\"file\":\"" + file
                + "\",\"thumb\":\"" + thumb + "\",\"sha256\":\"AB\"}";
    }

    @Test
    public void readsTheIndex() {
        List<OnlineWallpapers.Item> items = OnlineWallpapers.parse("{\"version\":1,\"wallpapers\":["
                + entry("nc-lake", "files/nc-lake.webp", "thumbs/nc-lake.webp") + "]}");
        assertEquals(1, items.size());
        assertEquals("dl-nc-lake.webp", items.get(0).fileName());
        assertEquals("ab", items.get(0).sha256);
        assertEquals("湖 · A，CC0 1.0", items.get(0).caption());
    }

    @Test
    public void dropsPathsThatLeaveTheFolder() {
        List<OnlineWallpapers.Item> items = OnlineWallpapers.parse("{\"wallpapers\":["
                + entry("a", "../secret.webp", "thumbs/a.webp") + ","
                + entry("b", "files/b.webp", "https://evil/b.webp") + ","
                + entry("../c", "files/c.webp", "thumbs/c.webp") + "]}");
        assertTrue(items.isEmpty());
    }

    @Test
    public void aBrokenIndexIsEmpty() {
        assertTrue(OnlineWallpapers.parse("<html>").isEmpty());
    }
}
