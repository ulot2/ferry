package app.ferry;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * End-to-end encryption. The pairing code is one 26-letter secret shared only by the two devices
 * (in the QR code). Both the ntfy.sh topic and the AES key come from it, so ntfy.sh never sees the
 * key and cannot read the text. The desktop app (desktop/Crypto.cs) must stay identical.
 */
final class Crypto {
    private static final String ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789";   // no 0/o, 1/l look-alikes
    private static final String PREFIX = "ferry1:";
    private static final byte[] AAD = "ferry1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] AAD_IMAGE = "ferry1-image".getBytes(StandardCharsets.UTF_8);   // an image can never be opened as text, or the reverse
    private static final byte[] AAD_FILE = "ferry1-file".getBytes(StandardCharsets.UTF_8);
    // Newer kinds get their own AAD, so an older Ferry cannot open them and ignores them instead of pasting them.
    private static final byte[] AAD_PART = "ferry1-part".getBytes(StandardCharsets.UTF_8);
    private static final byte[] AAD_INCOMING = "ferry1-incoming".getBytes(StandardCharsets.UTF_8);
    private static final byte[] AAD_LAN = "ferry1-lan".getBytes(StandardCharsets.UTF_8);
    private static final byte[] AAD_HELLO = "ferry1-hello".getBytes(StandardCharsets.UTF_8);
    private static final byte[] AAD_NOTIFICATION = "ferry1-notification".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RNG = new SecureRandom();

    /** Lowercases and drops spaces and dashes, so a typed code works however it was grouped. */
    static String normalize(String code) {
        return code == null ? "" : code.toLowerCase(Locale.ROOT).replaceAll("[\\s-]", "");
    }

    static boolean validCode(String code) {
        String c = normalize(code);
        if (c.length() != 26) return false;
        for (char ch : c.toCharArray()) if (ALPHABET.indexOf(ch) < 0) return false;
        return true;
    }

    static String topic(String code) {
        StringBuilder hex = new StringBuilder("ferry-");
        byte[] h = sha256("ferry-topic:" + normalize(code));
        for (int i = 0; i < 12; i++) hex.append(String.format(Locale.ROOT, "%02x", h[i]));
        return hex.toString();
    }

    private static byte[] key(String code) {
        return sha256("ferry-key:" + normalize(code));
    }

    /** Encrypts text for the other device: "ferry1:" + Base64(nonce | ciphertext | tag). */
    static String seal(String code, String text) throws GeneralSecurityException {
        return seal(code, text, AAD);
    }

    /** Returns null when the message was not sealed with this code: a stranger, or an old unencrypted message. */
    static String open(String code, String message) {
        return open(code, message, AAD);
    }

    /** The notice sent before a file: "size\nname". */
    static String sealIncoming(String code, long size, String name) throws GeneralSecurityException {
        return seal(code, size + "\n" + name, AAD_INCOMING);
    }

    static String openIncoming(String code, String message) {
        return open(code, message, AAD_INCOMING);
    }

    /** A message that sets up a direct Wi-Fi transfer (JSON). */
    static String sealLan(String code, String json) throws GeneralSecurityException {
        return seal(code, json, AAD_LAN);
    }

    static String openLan(String code, String message) {
        return open(code, message, AAD_LAN);
    }

    /** A phone notification for the laptop (JSON: app, title, text). Its own AAD, so it can never be opened as a copy. */
    static String sealNotification(String code, String json) throws GeneralSecurityException {
        return seal(code, json, AAD_NOTIFICATION);
    }

    /** The first thing on a direct connection: the transfer id, sealed, so only our other device gets in. */
    static byte[] sealHello(String code, byte[] id) throws GeneralSecurityException {
        return sealRaw(code, id, AAD_HELLO);
    }

    static boolean openHello(String code, byte[] sealed, byte[] id) {
        return java.util.Arrays.equals(openRaw(code, sealed, AAD_HELLO), id);
    }

    /**
     * One frame of a direct transfer. The AAD holds the transfer id, the frame number and the last-frame flag,
     * so a frame cannot be moved, repeated, or cut off at the end without the other side seeing it.
     */
    static byte[] sealFrame(String code, byte[] id, long index, boolean last, byte[] plain) throws GeneralSecurityException {
        return sealRaw(code, plain, frameAad(id, index, last));
    }

    static byte[] openFrame(String code, byte[] id, long index, boolean last, byte[] sealed) {
        return openRaw(code, sealed, frameAad(id, index, last));
    }

    /** "ferry1-lan" | 16-byte id | 8-byte big-endian frame number | 1 if last, else 0 */
    private static byte[] frameAad(byte[] id, long index, boolean last) {
        byte[] aad = java.util.Arrays.copyOf(AAD_LAN, AAD_LAN.length + 16 + 8 + 1);
        System.arraycopy(id, 0, aad, AAD_LAN.length, 16);
        for (int i = 0; i < 8; i++) aad[AAD_LAN.length + 16 + i] = (byte) (index >> (56 - 8 * i));
        aad[aad.length - 1] = (byte) (last ? 1 : 0);
        return aad;
    }

    private static String seal(String code, String text, byte[] aad) throws GeneralSecurityException {
        return PREFIX + Base64.getEncoder().encodeToString(sealRaw(code, text.getBytes(StandardCharsets.UTF_8), aad));
    }

    private static String open(String code, String message, byte[] aad) {
        if (message == null || !message.startsWith(PREFIX)) return null;
        try {
            byte[] plain = openRaw(code, Base64.getDecoder().decode(message.substring(PREFIX.length()).trim()), aad);
            return plain == null ? null : new String(plain, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Encrypts an image file (PNG or JPEG bytes) as raw nonce | ciphertext | tag, sent as a file. */
    static byte[] sealImage(String code, byte[] image) throws GeneralSecurityException {
        return sealRaw(code, image, AAD_IMAGE);
    }

    /** Returns null unless the bytes are an image sealed with this code. */
    static byte[] openImage(String code, byte[] sealed) {
        return openRaw(code, sealed, AAD_IMAGE);
    }

    /** Encrypts a file: 2-byte big-endian name length | UTF-8 name | contents. The name is sealed too, so ntfy.sh never sees it. */
    static byte[] sealFile(String code, String name, byte[] contents) throws GeneralSecurityException {
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        byte[] plain = new byte[2 + n.length + contents.length];
        plain[0] = (byte) (n.length >> 8);
        plain[1] = (byte) n.length;
        System.arraycopy(n, 0, plain, 2, n.length);
        System.arraycopy(contents, 0, plain, 2 + n.length, contents.length);
        return sealRaw(code, plain, AAD_FILE);
    }

    static final class SealedFile {
        final String name;
        final byte[] contents;

        SealedFile(String name, byte[] contents) {
            this.name = name;
            this.contents = contents;
        }
    }

    /** Returns null unless the bytes are a file sealed with this code. */
    static SealedFile openFile(String code, byte[] sealed) {
        byte[] plain = openRaw(code, sealed, AAD_FILE);
        if (plain == null || plain.length < 2) return null;
        int n = (plain[0] & 0xFF) << 8 | (plain[1] & 0xFF);
        if (plain.length < 2 + n) return null;
        return new SealedFile(new String(plain, 2, n, StandardCharsets.UTF_8), java.util.Arrays.copyOfRange(plain, 2 + n, plain.length));
    }

    /**
     * One part of a file too large for one ntfy.sh attachment:
     * 8-byte transfer id | 2-byte part index | 2-byte part count | 2-byte name length | UTF-8 name | contents. All big-endian.
     */
    static byte[] sealPart(String code, byte[] id, int index, int count, String name, byte[] contents) throws GeneralSecurityException {
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        byte[] plain = new byte[14 + n.length + contents.length];
        System.arraycopy(id, 0, plain, 0, 8);
        plain[8] = (byte) (index >> 8);
        plain[9] = (byte) index;
        plain[10] = (byte) (count >> 8);
        plain[11] = (byte) count;
        plain[12] = (byte) (n.length >> 8);
        plain[13] = (byte) n.length;
        System.arraycopy(n, 0, plain, 14, n.length);
        System.arraycopy(contents, 0, plain, 14 + n.length, contents.length);
        return sealRaw(code, plain, AAD_PART);
    }

    static final class Part {
        final String id, name;
        final int index, count;
        final byte[] contents;

        Part(String id, int index, int count, String name, byte[] contents) {
            this.id = id;
            this.index = index;
            this.count = count;
            this.name = name;
            this.contents = contents;
        }
    }

    /** Returns null unless the bytes are a file part sealed with this code. */
    static Part openPart(String code, byte[] sealed) {
        byte[] plain = openRaw(code, sealed, AAD_PART);
        if (plain == null || plain.length < 14) return null;
        int n = (plain[12] & 0xFF) << 8 | (plain[13] & 0xFF);
        if (plain.length < 14 + n) return null;
        return new Part(Base64.getEncoder().encodeToString(java.util.Arrays.copyOf(plain, 8)),
                (plain[8] & 0xFF) << 8 | (plain[9] & 0xFF), (plain[10] & 0xFF) << 8 | (plain[11] & 0xFF),
                new String(plain, 14, n, StandardCharsets.UTF_8), java.util.Arrays.copyOfRange(plain, 14 + n, plain.length));
    }

    static byte[] newId() {
        return newId(8);
    }

    static byte[] newId(int bytes) {
        byte[] id = new byte[bytes];
        RNG.nextBytes(id);
        return id;
    }

    private static byte[] sealRaw(String code, byte[] plain, byte[] aad) throws GeneralSecurityException {
        byte[] nonce = new byte[12];
        RNG.nextBytes(nonce);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(code), "AES"), new GCMParameterSpec(128, nonce));
        c.updateAAD(aad);
        byte[] sealed = c.doFinal(plain);
        byte[] out = new byte[12 + sealed.length];
        System.arraycopy(nonce, 0, out, 0, 12);
        System.arraycopy(sealed, 0, out, 12, sealed.length);
        return out;
    }

    private static byte[] openRaw(String code, byte[] all, byte[] aad) {
        if (all == null || all.length < 12 + 16) return null;
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(code), "AES"), new GCMParameterSpec(128, all, 0, 12));
            c.updateAAD(aad);
            return c.doFinal(all, 12, all.length - 12);
        } catch (GeneralSecurityException e) {
            return null;
        }
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);   // every Android device has SHA-256
        }
    }

    private Crypto() {}
}
