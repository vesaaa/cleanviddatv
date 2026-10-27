package io.github.vesaaa.cleanviddatv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;

import javax.crypto.Cipher;

public class ViddaToolsTest {

    private static final String HOME = "com.jamdeo.tv.vod/com.ju.main.launcher.LauncherActivity";

    @Test
    public void restoreEnablesTheStockHomeFirstAndSetsItAsHome() {
        String s = ViddaTools.restoreScript("com.hisense.hitv.das,com.keylab.speech.view.vidaa,com.jamdeo.tv.vod", HOME, "");
        assertTrue(s.startsWith("pm enable --user 0 com.jamdeo.tv.vod "));
        assertEquals("the stock home is enabled once", s.indexOf("pm enable --user 0 com.jamdeo.tv.vod "),
                s.lastIndexOf("pm enable --user 0 com.jamdeo.tv.vod "));
        assertTrue(s.contains("pm enable --user 0 com.keylab.speech.view.vidaa "));
        assertTrue(s.contains("cmd package set-home-activity --user 0 " + HOME));
        assertTrue(s.contains("settings delete secure enabled_accessibility_services"));
        assertTrue(s.endsWith("echo " + ViddaTools.RESTORED_MARK));
    }

    @Test
    public void restorePutsTheOldAccessibilityServicesBack() {
        String s = ViddaTools.restoreScript("a.b", HOME, "com.hisense.vidaaassistant/.AppAccessibilityService");
        assertTrue(s.contains("settings put secure enabled_accessibility_services 'com.hisense.vidaaassistant/.AppAccessibilityService'"));
    }

    @Test
    public void restoreSkipsAnythingThatIsNotAPackageName() {
        String s = ViddaTools.restoreScript("com.ok.pkg,bad;rm -rf /,$(reboot),noDots", "evil; reboot", "x'; reboot; '");
        assertTrue(s.contains("pm enable --user 0 com.ok.pkg "));
        assertFalse(s.contains("rm -rf"));
        assertFalse(s.contains("reboot"));
        assertFalse(s.contains("noDots"));
        assertFalse(s.contains("set-home-activity"));
    }

    @Test
    public void headerIsLittleEndianWithChecksumAndMagic() {
        byte[] data = "host::\0".getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.wrap(LocalAdb.header(LocalAdb.CNXN, LocalAdb.VERSION, LocalAdb.MAX_DATA, data))
                .order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(LocalAdb.CNXN, b.getInt());
        assertEquals(LocalAdb.VERSION, b.getInt());
        assertEquals(LocalAdb.MAX_DATA, b.getInt());
        assertEquals(data.length, b.getInt());
        assertEquals(LocalAdb.checksum(data), b.getInt());
        assertEquals(~LocalAdb.CNXN, b.getInt());
    }

    @Test
    public void messageRoundTrip() throws Exception {
        byte[] payload = "shell:echo hi\0".getBytes(StandardCharsets.UTF_8);
        byte[] header = LocalAdb.header(LocalAdb.OPEN, 1, 0, payload);
        byte[] wire = new byte[header.length + payload.length];
        System.arraycopy(header, 0, wire, 0, header.length);
        System.arraycopy(payload, 0, wire, header.length, payload.length);
        LocalAdb.Message m = LocalAdb.readMessage(new ByteArrayInputStream(wire));
        assertEquals(LocalAdb.OPEN, m.command);
        assertEquals(1, m.arg0);
        assertEquals("shell:echo hi\0", new String(m.data, StandardCharsets.UTF_8));
    }

    @Test
    public void publicKeyStructMatchesAdbLayout() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        RSAPublicKey key = (RSAPublicKey) g.generateKeyPair().getPublic();
        ByteBuffer b = ByteBuffer.wrap(LocalAdb.publicKeyStruct(key)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(4 + 4 + 256 + 256 + 4, b.capacity());
        assertEquals(64, b.getInt());
        int n0inv = b.getInt();
        long n0 = b.getInt(8) & 0xffffffffL;
        assertEquals("n0inv * n[0] == -1 mod 2^32", 0xffffffffL, (n0inv * n0) & 0xffffffffL);
        byte[] n = new byte[256];
        b.position(8);
        b.get(n);
        assertEquals(key.getModulus(), littleEndian(n));
        b.position(8 + 256 + 256);
        assertEquals(65537, b.getInt());
    }

    @Test
    public void signatureVerifiesAsSha1DigestInfo() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair key = g.generateKeyPair();
        byte[] token = new byte[20];
        for (int i = 0; i < token.length; i++) token[i] = (byte) i;
        byte[] signature = LocalAdb.sign(key, token);
        Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsa.init(Cipher.DECRYPT_MODE, key.getPublic());
        byte[] plain = rsa.doFinal(signature);
        assertEquals(35, plain.length);
        for (int i = 0; i < 20; i++) assertEquals(token[i], plain[15 + i]);
    }

    private static BigInteger littleEndian(byte[] bytes) {
        byte[] big = new byte[bytes.length + 1];
        for (int i = 0; i < bytes.length; i++) big[bytes.length - i] = bytes[i];
        return new BigInteger(big);
    }
}
