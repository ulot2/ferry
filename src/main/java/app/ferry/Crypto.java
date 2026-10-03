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
        return PREFIX + Base64.getEncoder().encodeToString(sealRaw(code, text.getBytes(StandardCharsets.UTF_8), AAD));
    }

    /** Returns null when the message was not sealed with this code: a stranger, or an old unencrypted message. */
    static String open(String code, String message) {
        if (message == null || !message.startsWith(PREFIX)) return null;
        try {
            byte[] plain = openRaw(code, Base64.getDecoder().decode(message.substring(PREFIX.length()).trim()), AAD);
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
