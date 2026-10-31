package io.github.vesaaa.cleanviddatv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UpdatesTest {

    @Test
    public void betasComeBeforeTheirReleaseAndInNumberOrder() {
        assertTrue(Updates.compare("1.0.0-beta.1", "1.0.0-beta.2") < 0);
        assertTrue(Updates.compare("1.0.0-beta.2", "1.0.0-beta.10") < 0);
        assertTrue(Updates.compare("1.0.0-beta.2", "1.0.0") < 0);
        assertTrue(Updates.compare("1.0.0", "1.0.1-beta.1") < 0);
        assertTrue(Updates.compare("1.0.9", "1.0.10") < 0);
        assertEquals(0, Updates.compare("v1.2.0", "1.2.0"));
        assertEquals(0, Updates.compare("1.2", "1.2.0"));
    }

    @Test
    public void prereleaseIsTheDashSuffix() {
        assertTrue(Updates.isPrerelease("1.0.0-beta.1"));
        assertFalse(Updates.isPrerelease("1.0.0"));
    }

    @Test
    public void readsTheCdnManifest() {
        Updates.Release r = Updates.parseManifest("{\"version\":\"1.0.0\",\"tag\":\"v1.0.0\",\"notes\":\"壁纸\","
                + "\"apk\":\"https://download.aikit.stream/cleanviddatv/1.0.0/CleanViddaTV-1.0.0.apk\","
                + "\"github_apk\":\"https://github.com/vesaaa/cleanviddatv/releases/download/v1.0.0/CleanViddaTV-1.0.0.apk\","
                + "\"sha256\":\"AB" + "0".repeat(62) + "\",\"size\":1}");
        assertEquals("1.0.0", r.version);
        assertEquals("壁纸", r.notes);
        assertTrue(r.githubApk.startsWith("https://github.com/"));
        assertEquals("ab" + "0".repeat(62), r.sha256);
    }

    @Test
    public void refusesAManifestWithoutAnHttpsApk() {
        assertNull(Updates.parseManifest("{\"version\":\"1.0.0\",\"apk\":\"http://x/a.apk\"}"));
        assertNull(Updates.parseManifest("<html>blocked</html>"));
    }

    @Test
    public void githubListSkipsBetasUnlessAskedAndPicksTheNewest() {
        String json = "[" + release("v1.1.0-beta.1", true) + "," + release("v1.0.1", false) + ","
                + release("v1.0.0", false) + "]";
        assertEquals("1.0.1", Updates.parseGithub(json, false).version);
        Updates.Release beta = Updates.parseGithub(json, true);
        assertEquals("1.1.0-beta.1", beta.version);
        assertTrue(beta.sha256.startsWith("url:https://"));
    }

    @Test
    public void checksumIsTheFirstWordOfASha256sumLine() {
        String hex = "0123456789abcdef".repeat(4);
        assertEquals(hex, Updates.checksum(hex.toUpperCase() + "  CleanViddaTV-1.0.0.apk\n"));
        assertEquals("", Updates.checksum("not a sum"));
    }

    private static String release(String tag, boolean pre) {
        String v = tag.substring(1);
        return "{\"tag_name\":\"" + tag + "\",\"prerelease\":" + pre + ",\"draft\":false,\"body\":\"\",\"assets\":["
                + "{\"name\":\"CleanViddaTV-" + v + ".apk\",\"browser_download_url\":\"https://github.com/r/" + v + ".apk\"},"
                + "{\"name\":\"CleanViddaTV-" + v + ".apk.sha256\",\"browser_download_url\":\"https://github.com/r/" + v + ".sha256\"}]}";
    }

    @Test
    public void signerCheckRefusesOnlyWhatItCanRead() {
        String pkg = "io.github.vesaaa.cleanviddatv";
        byte[][] own = {{1, 2, 3}};
        assertTrue(Updater.signerMatches(pkg, pkg, new byte[][]{{1, 2, 3}}, own));
        assertFalse(Updater.signerMatches(pkg, pkg, new byte[][]{{9, 9}}, own));
        assertFalse(Updater.signerMatches(pkg, "other.app", new byte[][]{{1, 2, 3}}, own));
        // Some TVs read no certificates from a downloaded archive: the installer itself decides then.
        assertTrue(Updater.signerMatches(pkg, pkg, null, own));
        assertTrue(Updater.signerMatches(pkg, null, new byte[0][], own));
    }
}
