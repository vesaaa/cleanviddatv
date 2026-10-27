package io.github.vesaaa.cleanviddatv;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

import javax.crypto.Cipher;

/**
 * A minimal ADB client for the TV's own adbd (127.0.0.1:5555), so the launcher can run shell commands with the
 * shell user's rights: `pm enable` for the restore, which an ordinary app is not allowed to do.
 *
 * Wire protocol (system/core/adb/protocol.txt): 24-byte little-endian headers, CNXN to connect, AUTH when adbd
 * asks for a key (ro.adb.secure=1), OPEN "shell:..." for a stream, WRTE/OKAY for data, CLSE at the end. Hisense
 * MT9653 firmware runs with ro.adb.secure=0 and answers CNXN straight away; elsewhere the first connection shows
 * the TV's "Allow USB debugging?" prompt for this app's key.
 */
final class LocalAdb implements Closeable {

    static final int CNXN = 0x4e584e43;
    static final int AUTH = 0x48545541;
    static final int OPEN = 0x4e45504f;
    static final int OKAY = 0x59414b4f;
    static final int CLSE = 0x45534c43;
    static final int WRTE = 0x45545257;

    static final int VERSION = 0x01000000;
    static final int MAX_DATA = 4096;
    static final int AUTH_TOKEN = 1;
    static final int AUTH_SIGNATURE = 2;
    static final int AUTH_RSAPUBLICKEY = 3;

    /** DER prefix of a SHA-1 DigestInfo: adbd signs its 20-byte token as if it were a SHA-1 digest. */
    private static final byte[] SHA1_DIGEST_INFO = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private int nextLocalId = 1;

    private LocalAdb(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
    }

    /** One message as it arrives. */
    static final class Message {
        final int command;
        final int arg0;
        final int arg1;
        final byte[] data;

        Message(int command, int arg0, int arg1, byte[] data) {
            this.command = command;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.data = data;
        }
    }

    /**
     * Connects and authenticates. {@code key} signs adbd's token; if adbd does not know it yet, the public key is
     * offered and the TV asks the person on screen, so this waits up to {@code timeoutMs} for that answer.
     */
    static LocalAdb connect(String host, int port, KeyPair key, int timeoutMs) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), timeoutMs);
        socket.setSoTimeout(timeoutMs);
        LocalAdb adb = new LocalAdb(socket);
        try {
            adb.send(CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.UTF_8));
            boolean signed = false;
            while (true) {
                Message m = adb.read();
                if (m.command == CNXN) return adb;
                if (m.command != AUTH || m.arg0 != AUTH_TOKEN) throw new IOException("unexpected adb reply " + m.command);
                if (key == null) throw new IOException("adbd wants a key");
                if (!signed) {
                    adb.send(AUTH, AUTH_SIGNATURE, 0, sign(key, m.data));
                    signed = true;
                } else {
                    // The signature was refused: offer the public key, and adbd shows the prompt on the TV.
                    adb.send(AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyPayload((RSAPublicKey) key.getPublic()));
                }
            }
        } catch (IOException | RuntimeException e) {
            adb.close();
            throw e;
        }
    }

    /** Runs one shell command and returns everything it printed (stdout and stderr, as adb's shell: merges them). */
    String shell(String command) throws IOException {
        int local = nextLocalId++;
        send(OPEN, local, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int remote = 0;
        while (true) {
            Message m = read();
            if (m.arg1 != local) continue;   // another stream's message (none are open, but be strict)
            switch (m.command) {
                case OKAY:
                    remote = m.arg0;
                    break;
                case WRTE:
                    remote = m.arg0;
                    output.write(m.data);
                    send(OKAY, local, remote, new byte[0]);
                    break;
                case CLSE:
                    if (remote != 0) send(CLSE, local, remote, new byte[0]);
                    return output.toString("UTF-8");
                default:
                    throw new IOException("unexpected adb message " + m.command);
            }
        }
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private void send(int command, int arg0, int arg1, byte[] data) throws IOException {
        out.write(header(command, arg0, arg1, data));
        out.write(data);
        out.flush();
    }

    private Message read() throws IOException {
        byte[] head = new byte[24];
        in.readFully(head);
        ByteBuffer b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        int command = b.getInt();
        int arg0 = b.getInt();
        int arg1 = b.getInt();
        int length = b.getInt();
        b.getInt();   // checksum: not checked, as adb itself stopped checking it from version 0x01000001
        int magic = b.getInt();
        if (magic != ~command) throw new IOException("bad adb header");
        if (length < 0 || length > 1024 * 1024) throw new IOException("bad adb length " + length);
        byte[] data = new byte[length];
        in.readFully(data);
        return new Message(command, arg0, arg1, data);
    }

    static byte[] header(int command, int arg0, int arg1, byte[] data) {
        return ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(command).putInt(arg0).putInt(arg1).putInt(data.length)
                .putInt(checksum(data)).putInt(~command).array();
    }

    /** Version 0x01000000 adbd still checks this: the sum of the payload bytes. */
    static int checksum(byte[] data) {
        int sum = 0;
        for (byte d : data) sum += d & 0xff;
        return sum;
    }

    /** PKCS#1 v1.5 signature of the token, treated as a SHA-1 digest (what adbd verifies). */
    static byte[] sign(KeyPair key, byte[] token) throws IOException {
        try {
            byte[] digestInfo = new byte[SHA1_DIGEST_INFO.length + token.length];
            System.arraycopy(SHA1_DIGEST_INFO, 0, digestInfo, 0, SHA1_DIGEST_INFO.length);
            System.arraycopy(token, 0, digestInfo, SHA1_DIGEST_INFO.length, token.length);
            Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            rsa.init(Cipher.ENCRYPT_MODE, key.getPrivate());
            return rsa.doFinal(digestInfo);
        } catch (GeneralSecurityException e) {
            throw new IOException("cannot sign the adb token", e);
        }
    }

    /** The public key as adbd stores it: base64 of Android's RSAPublicKey struct, a name, and a NUL. */
    static byte[] publicKeyPayload(RSAPublicKey key) {
        String text = Base64.getEncoder().encodeToString(publicKeyStruct(key)) + " cleanviddatv@tv\0";
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * struct RSAPublicKey { uint32 len; uint32 n0inv; uint32 n[len]; uint32 rr[len]; int32 exponent; },
     * little-endian words, for a 2048-bit key: len = 64, n0inv = -1/n[0] mod 2^32, rr = (2^(32*len))^2 mod n.
     */
    static byte[] publicKeyStruct(RSAPublicKey key) {
        int words = 64;
        BigInteger n = key.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(32 * words * 2).mod(n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 4 * words * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words);
        b.putInt(n0inv.intValue());
        putWords(b, n, words);
        putWords(b, rr, words);
        b.putInt(key.getPublicExponent().intValue());
        return b.array();
    }

    private static void putWords(ByteBuffer b, BigInteger value, int words) {
        BigInteger mask = BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE);
        for (int i = 0; i < words; i++) b.putInt(value.shiftRight(32 * i).and(mask).intValue());
    }

    /** Reads exactly one message from a stream; for tests. */
    static Message readMessage(InputStream stream) throws IOException {
        DataInputStream data = new DataInputStream(stream);
        byte[] head = new byte[24];
        data.readFully(head);
        ByteBuffer b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        int command = b.getInt();
        int arg0 = b.getInt();
        int arg1 = b.getInt();
        byte[] payload = new byte[b.getInt()];
        data.readFully(payload);
        return new Message(command, arg0, arg1, payload);
    }
}
