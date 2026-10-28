package io.github.vesaaa.cleanviddatv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class ShelfItemTest {

    private static ShelfItem item(String title, long start, long end, int progress, String section) {
        return new ShelfItem(1, title, null, null, false, progress, start, end, null, section, "intent:#Intent;end");
    }

    @Test
    public void progressComesFromTheProgrammeTimesWhenThereAreSome() {
        ShelfItem it = item("CCTV-1", 1_000, 2_000, -1, null);
        assertEquals(0, it.progressAt(500));
        assertEquals(250, it.progressAt(1_250));
        assertEquals(1000, it.progressAt(5_000));
    }

    @Test
    public void withoutTimesTheAppsProgressOrNone() {
        assertEquals(-1, item("a", 0, 0, -1, null).progressAt(1));
        assertEquals(400, item("a", 0, 0, 400, null).progressAt(1));
        assertEquals(1000, item("a", 0, 0, 5000, null).progressAt(1));
        assertEquals(-1, item("a", 2_000, 1_000, -1, null).progressAt(1_500));   // end before start: no times
    }

    @Test
    public void refreshFollowsTheFirstProgrammeStillRunning() {
        List<ShelfItem> items = Arrays.asList(item("a", 100, 900, -1, null), item("b", 100, 3_000, -1, null),
                item("c", 100, 2_000, -1, null), item("d", 0, 0, 10, null));
        assertEquals(2_000 + ShelfItem.REFRESH_SLACK_MS, ShelfItem.nextRefresh(items, 1_000));
        assertEquals(0, ShelfItem.nextRefresh(items, 4_000));
        assertEquals(0, ShelfItem.nextRefresh(Collections.emptyList(), 0));
    }

    @Test
    public void onlyTheProvidersOwnImages() {
        assertTrue(ShelfItem.ownImage("content://com.vesaa.mytv.shelf/image/7", "com.vesaa.mytv.shelf"));
        assertFalse(ShelfItem.ownImage("https://example.com/logo.png", "com.vesaa.mytv.shelf"));
        assertFalse(ShelfItem.ownImage("content://other.app/image/7", "com.vesaa.mytv.shelf"));
        assertFalse(ShelfItem.ownImage("content://com.vesaa.mytv.shelf/items", "com.vesaa.mytv.shelf"));
        assertFalse(ShelfItem.ownImage("content://com.vesaa.mytv.shelf/image/../x", "com.vesaa.mytv.shelf"));
        assertFalse(ShelfItem.ownImage(null, "com.vesaa.mytv.shelf"));
    }

    @Test
    public void aCardNeedsATitleAndSomethingToOpen() {
        assertTrue(item("CCTV-1", 0, 0, -1, null).usable());
        assertFalse(item(" ", 0, 0, -1, null).usable());
        assertFalse(new ShelfItem(1, "a", null, null, false, -1, 0, 0, null, null, null).usable());
    }

    @Test
    public void aGapWhereTheSectionChanges() {
        List<ShelfItem> items = Arrays.asList(item("a", 0, 0, -1, "recent"), item("b", 0, 0, -1, "recent"),
                item("c", 0, 0, -1, "favorite"), item("d", 0, 0, -1, null));
        assertFalse(ShelfItem.sectionBreak(items, 0));
        assertFalse(ShelfItem.sectionBreak(items, 1));
        assertTrue(ShelfItem.sectionBreak(items, 2));
        assertFalse(ShelfItem.sectionBreak(items, 3));
    }

    @Test
    public void badgesAreShort() {
        assertEquals("直播", new ShelfItem(1, "a", null, null, false, -1, 0, 0, " 直播 ", null, "x").shortBadge());
        assertEquals("abcd", new ShelfItem(1, "a", null, null, false, -1, 0, 0, "abcdef", null, "x").shortBadge());
        assertNull(new ShelfItem(1, "a", null, null, false, -1, 0, 0, "", null, "x").shortBadge());
    }
}
