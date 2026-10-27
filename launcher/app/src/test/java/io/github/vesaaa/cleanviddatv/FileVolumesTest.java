package io.github.vesaaa.cleanviddatv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FileVolumesTest {

    @Test
    public void insideMeansTheFolderOrBelowIt() {
        assertTrue(FileVolumes.isInside("/storage/emulated/0", "/storage/emulated/0"));
        assertTrue(FileVolumes.isInside("/storage/emulated/0/ROMs/a.zip", "/storage/emulated/0"));
        assertTrue(FileVolumes.isInside("/storage/emulated/0/ROMs", "/storage/emulated/0/"));
    }

    @Test
    public void aSiblingWithTheSamePrefixIsNotInside() {
        assertFalse(FileVolumes.isInside("/storage/emulated/01/x", "/storage/emulated/0"));
        assertFalse(FileVolumes.isInside("/storage", "/storage/emulated/0"));
        assertFalse(FileVolumes.isInside(null, "/storage"));
    }

    @Test
    public void namesThatLeaveTheFolderAreRefused() {
        assertNull(FileVolumes.safeName(".."));
        assertNull(FileVolumes.safeName("."));
        assertNull(FileVolumes.safeName("a/b"));
        assertNull(FileVolumes.safeName("   "));
        assertNull(FileVolumes.safeName(null));
        assertEquals("存档.sav", FileVolumes.safeName(" 存档.sav "));
    }

    @Test
    public void aTakenNameIsNumberedBeforeItsExtension() {
        assertEquals("game (1).sav", FileVolumes.numbered("game.sav", 1, false));
        assertEquals("ROMs (2)", FileVolumes.numbered("ROMs", 2, true));
        assertEquals("v1.2 (1)", FileVolumes.numbered("v1.2", 1, true));
        assertEquals(".hidden (1)", FileVolumes.numbered(".hidden", 1, false));
    }
}
