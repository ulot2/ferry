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
        byte[] nonce = new byte[12];
        RNG.nextBytes(nonce);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(code), "AES"), new GCMParameterSpec(128, nonce));
        c.updateAAD(AAD);
        byte[] sealed = c.doFinal(text.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[12 + sealed.length];
        System.arraycopy(nonce, 0, out, 0, 12);
        System.arraycopy(sealed, 0, out, 12, sealed.length);
        return PREFIX + Base64.getEncoder().encodeToString(out);
    }

    /** Returns null when the message was not sealed with this code: a stranger, or an old unencrypted message. */
    static String open(String code, String message) {
        if (message == null || !message.startsWith(PREFIX)) return null;
        try {
            byte[] all = Base64.getDecoder().decode(message.substring(PREFIX.length()).trim());
            if (all.length < 12 + 16) return null;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(code), "AES"), new GCMParameterSpec(128, all, 0, 12));
            c.updateAAD(AAD);
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | GeneralSecurityException e) {
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
