package app.ferry;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/** Shared bits: saved state, pairing, history, and talking to ntfy.sh. */
final class Ferry {
    static final String SERVER = "https://ntfy.sh/";
    static final String STATUS_CONNECTED = "connected", STATUS_CONNECTING = "connecting", STATUS_OFFLINE = "offline";
    static final String TO_PHONE = "Laptop → Phone", TO_LAPTOP = "Phone → Laptop";
    private static final int HISTORY_SIZE = 10, HISTORY_MAX_CHARS = 100_000;

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("ferry", Context.MODE_PRIVATE);
    }

    /** The pairing code, or "" when not paired. Versions before encryption saved a "topic" instead; those count as not paired. */
    static String code(Context c) {
        return prefs(c).getString("code", "");
    }

    static boolean paired(Context c) {
        return !code(c).isEmpty();
    }

    /** Reads a pairing link from the desktop app: ferry://pair?code=...&name=... Returns false if it is not one. */
    static boolean pair(Context c, Uri uri) {
        if (uri == null || !"ferry".equals(uri.getScheme()) || !"pair".equals(uri.getHost())) return false;
        String code = uri.getQueryParameter("code");
        if (!Crypto.validCode(code)) return false;
        pair(c, code, uri.getQueryParameter("name"));
        return true;
    }

    static void pair(Context c, String code, String laptopName) {
        prefs(c).edit().clear()
                .putString("code", Crypto.normalize(code))
                .putString("peer", laptopName == null ? "" : laptopName)
                .apply();
        c.stopService(new Intent(c, SyncService.class));   // restart on the new topic
        SyncService.start(c);
        // Tell the laptop who just paired. Failure is fine: the first real send proves the link too.
        new Thread(() -> {
            try {
                send(c, Build.MODEL, "phone,pair");
            } catch (IOException ignored) {
            }
        }).start();
    }

    static void unpair(Context c) {
        c.stopService(new Intent(c, SyncService.class));
        prefs(c).edit().clear().apply();
    }

    static void status(Context c, String status) {
        prefs(c).edit().putString("status", status).apply();
    }

    /** The text that last crossed either way. Auto-send skips a copy equal to it, so nothing bounces back. */
    static String lastText(Context c) {
        return prefs(c).getString("last_text", "");
    }

    /** Adds a crossing to the top of the history (newest first, 10 kept). */
    static synchronized void crossed(Context c, String direction, String text, long at) {
        JSONArray old = history(c), now = new JSONArray();
        try {
            if (text.length() <= HISTORY_MAX_CHARS) now.put(new JSONObject().put("dir", direction).put("text", text).put("at", at));
            for (int i = 0; i < old.length() && now.length() < HISTORY_SIZE; i++) now.put(old.get(i));
        } catch (JSONException e) {
            throw new IllegalStateException(e);   // only strings and numbers go in
        }
        prefs(c).edit().putString("history", now.toString()).putString("last_text", text).apply();
    }

    static JSONArray history(Context c) {
        try {
            return new JSONArray(prefs(c).getString("history", "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    static void clearHistory(Context c) {
        prefs(c).edit().remove("history").apply();
    }

    /** Short single-line preview of a crossing. */
    static String preview(String text) {
        String p = text.trim().replaceAll("\\s+", " ");
        return p.length() > 160 ? p.substring(0, 160) + "…" : p;
    }

    /** True in Ferry Auto, the edition with automatic sending (installed from a computer). */
    static boolean autoEdition(Context c) {
        return c.getResources().getBoolean(R.bool.has_auto_send);
    }

    static boolean autoSendOn(Context c) {
        if (!autoEdition(c)) return false;
        // By name: AutoSendService only exists in the auto edition's sources.
        String service = new ComponentName(c.getPackageName(), "app.ferry.AutoSendService").flattenToString();
        String on = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return on != null && on.contains(service);
    }

    /** Encrypts and sends text to the laptop. Blocks, so call it off the main thread. */
    static void send(Context c, String text, String tags) throws IOException {
        String code = code(c);
        if (code.isEmpty()) throw new IOException("Not paired");
        String body;
        try {
            body = Crypto.seal(code, text);
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
        // ntfy.sh turns bodies over 4 KB into a text file by itself; the laptop reads both.
        HttpURLConnection con = (HttpURLConnection) new URL(SERVER + Crypto.topic(code)).openConnection();
        try {
            con.setRequestMethod("POST");
            con.setDoOutput(true);
            con.setConnectTimeout(10_000);
            con.setReadTimeout(15_000);
            con.setRequestProperty("Tags", tags);   // "phone" lets this phone skip its own message when it comes back
            try (OutputStream out = con.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int status = con.getResponseCode();
            if (status != 200) throw new IOException("ntfy.sh answered " + status);
        } finally {
            con.disconnect();
        }
    }

    static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        for (int n; (n = in.read(chunk)) != -1; ) buf.write(chunk, 0, n);
        return buf.toString("UTF-8");
    }

    private Ferry() {}
}
