package io.github.vesaaa.cleanviddatv;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class PhotoSceneTest {

    @Test
    public void homesPicturesComeFirstAndEachFileOnce() {
        File home = new File("/w/builtin-photo-02.webp"), other = new File("/w/builtin-photo-01.webp"),
                dl = new File("/d/dl-aurora.webp");
        assertEquals(Arrays.asList(home, other, dl),
                PhotoScene.pictures(Collections.singletonList(home), Arrays.asList(other, home), Collections.singletonList(dl)));
    }

    @Test
    public void nothingAnywhereIsNoPictures() {
        assertEquals(Collections.emptyList(),
                PhotoScene.pictures(Collections.emptyList(), Collections.emptyList(), Collections.emptyList()));
    }
}
