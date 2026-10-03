package app.ferry;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Shared bits: saved state, pairing, and talking to ntfy.sh. */
final class Ferry {
    static final String SERVER = "https://ntfy.sh/";
    static final String STATUS_CONNECTED = "connected", STATUS_CONNECTING = "connecting", STATUS_OFFLINE = "offline";

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("ferry", Context.MODE_PRIVATE);
    }

    static String topic(Context c) {
        return prefs(c).getString("topic", "");
    }

    /** ntfy.sh topic rules. Also keeps the topic safe to put in a URL. */
    static boolean validTopic(String t) {
        return t != null && t.matches("[A-Za-z0-9_-]{1,64}");
    }

    /** Reads a pairing code from the desktop app: ferry://pair?topic=...&name=... Returns false if it is not one. */
    static boolean pair(Context c, Uri uri) {
        if (uri == null || !"ferry".equals(uri.getScheme()) || !"pair".equals(uri.getHost())) return false;
        String topic = uri.getQueryParameter("topic");
        if (!validTopic(topic)) return false;
        pair(c, topic, uri.getQueryParameter("name"));
        return true;
    }

    static void pair(Context c, String topic, String laptopName) {
        prefs(c).edit()
                .putString("topic", topic)
                .putString("peer", laptopName == null ? "" : laptopName)
                .remove("cross_time").remove("cross_dir").remove("cross_text")
                .apply();
        c.stopService(new Intent(c, SyncService.class));   // restart on the new topic
        SyncService.start(c);
        // Tell the laptop who just paired. Failure is fine: the first real send proves the link too.
        new Thread(() -> {
            try {
                send(topic, Build.MODEL, "phone,pair");
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

    /** Records the last crossing so the main screen can show it. Keeps only a short preview. */
    static void crossed(Context c, String direction, String text) {
        String preview = text.trim().replaceAll("\\s+", " ");
        if (preview.length() > 160) preview = preview.substring(0, 160) + "…";
        prefs(c).edit()
                .putString("cross_dir", direction)
                .putString("cross_text", preview)
                .putLong("cross_time", System.currentTimeMillis())
                .apply();
    }

    /** Sends text to the laptop. Blocks, so call it off the main thread. */
    static void send(String topic, String text, String tags) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(SERVER + topic).openConnection();
        try {
            con.setRequestMethod("POST");
            con.setDoOutput(true);
            con.setConnectTimeout(10_000);
            con.setReadTimeout(15_000);
            con.setRequestProperty("Tags", tags);   // "phone" lets this phone skip its own message when it comes back
            con.setRequestProperty("Title", "From phone");
            try (OutputStream out = con.getOutputStream()) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
            int code = con.getResponseCode();
            if (code != 200) throw new IOException("ntfy.sh answered " + code);
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
