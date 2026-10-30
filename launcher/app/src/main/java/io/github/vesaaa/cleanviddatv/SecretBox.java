package io.github.vesaaa.cleanviddatv;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Small secrets kept in preferences (the 飞牛影视 password), sealed with an AES key that lives in the Android
 * keystore and never leaves it, so a copy of the preferences file is not enough to read them.
 */
final class SecretBox {
    private static final String STORE = "AndroidKeyStore", ALIAS = "cleanviddatv-secrets";

    private SecretBox() { }

    /** Base64 of iv + ciphertext, or "" when the keystore cannot be used (the secret is then not kept). */
    static String seal(String plain) {
        if (plain == null || plain.isEmpty()) return "";
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV();
            byte[] body = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[1 + iv.length + body.length];
            out[0] = (byte) iv.length;
            System.arraycopy(iv, 0, out, 1, iv.length);
            System.arraycopy(body, 0, out, 1 + iv.length, body.length);
            return Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    /** The secret, or null when there is none or it cannot be opened (a new key after a reset). */
    static String open(String sealed) {
        if (sealed == null || sealed.isEmpty()) return null;
        try {
            byte[] in = Base64.decode(sealed, Base64.NO_WRAP);
            int ivLength = in[0];
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, in, 1, ivLength));
            byte[] plain = c.doFinal(in, 1 + ivLength, in.length - 1 - ivLength);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance(STORE);
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE);
        g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return g.generateKey();
    }
}
