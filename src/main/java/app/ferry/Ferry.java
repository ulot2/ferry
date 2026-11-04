package app.ferry;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import android.webkit.MimeTypeMap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Locale;
import java.util.function.IntConsumer;

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
                send(c, deviceName(c), "phone,pair");
            } catch (IOException ignored) {
            }
        }).start();
    }

    /** The phone's name from Settings > About phone ("Redmi Note 14"), not the model code ("24117RN76G"). */
    static String deviceName(Context c) {
        String name = Settings.Global.getString(c.getContentResolver(), Settings.Global.DEVICE_NAME);
        return name == null || name.trim().isEmpty() ? Build.MODEL : name.trim();
    }

    /** You tapped Unpair: the laptop is told, and makes a new pairing code. If the phone is offline, the laptop is not told. */
    static void unpair(Context c) {
        String code = code(c);
        if (!code.isEmpty()) new Thread(() -> {
            try {
                send(code, "unpair", "phone,unpair");
            } catch (IOException ignored) {
            }
        }).start();
        forget(c);
    }

    /** Unpairs without telling the laptop: for when the laptop itself unpaired. */
    static void forget(Context c) {
        c.stopService(new Intent(c, SyncService.class));
        prefs(c).edit().clear().apply();
    }

    /** "system" (follow Android), "light" or "dark". Kept in its own file, so pairing and Unpair (which clear prefs) keep it. */
    static String theme(Context c) {
        return c.getSharedPreferences("look", Context.MODE_PRIVATE).getString("theme", "system");
    }

    static void setTheme(Context c, String theme) {
        c.getSharedPreferences("look", Context.MODE_PRIVATE).edit().putString("theme", theme).apply();
    }

    /**
     * Each screen calls this from attachBaseContext. Light or dark overrides only the night bit of the
     * configuration, so values-night and the status-bar icons follow the choice. Null means: follow Android.
     */
    static Configuration themeOverride(Context c) {
        String theme = theme(c);
        if (theme.equals("system")) return null;
        Configuration o = new Configuration();   // all fields unset, so nothing else is overridden
        o.uiMode = theme.equals("dark") ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
        return o;
    }

    static void status(Context c, String status) {
        prefs(c).edit().putString("status", status).apply();
    }

    /** The text that last crossed either way. Auto-send skips a copy equal to it, so nothing bounces back. */
    static String lastText(Context c) {
        return prefs(c).getString("last_text", "");
    }

    /** Images both ways (on unless turned off). */
    static boolean imagesOn(Context c) {
        return prefs(c).getBoolean("images", true);
    }

    /** Send new screenshots by themselves (off unless turned on; needs the Photos permission). */
    static boolean screenshotsOn(Context c) {
        return imagesOn(c) && prefs(c).getBoolean("screenshots", false);
    }

    /** Adds a crossing to the top of the history (newest first, 10 kept). */
    static void crossed(Context c, String direction, String text, long at) {
        crossed(c, direction, text, at, null);
    }

    /** For images, text is a short label ("Image", "Screenshot") and image is the kept file path, or "" when none was kept. */
    static void crossed(Context c, String direction, String text, long at, String image) {
        crossed(c, direction, text, at, "image", image);
    }

    /** For files, text is the file name and file is the saved copy in Downloads/Ferry (a content:// link), or "" for a sent file. */
    static void crossedFile(Context c, String direction, String name, long at, String file) {
        crossed(c, direction, name, at, "file", file);
    }

    private static synchronized void crossed(Context c, String direction, String text, long at, String key, String value) {
        JSONArray old = history(c), now = new JSONArray();
        try {
            if (text.length() <= HISTORY_MAX_CHARS) {
                JSONObject item = new JSONObject().put("dir", direction).put("text", text).put("at", at);
                if (value != null) item.put(key, value);
                now.put(item);
            }
            for (int i = 0; i < old.length() && now.length() < HISTORY_SIZE; i++) now.put(old.get(i));
        } catch (JSONException e) {
            throw new IllegalStateException(e);   // only strings and numbers go in
        }
        SharedPreferences.Editor e = prefs(c).edit().putString("history", now.toString());
        if (value == null) e.putString("last_text", text);   // only text can bounce back through automatic sending
        if (at >= prefs(c).getLong("pending_at", Long.MAX_VALUE)) e.remove("pending").remove("pending_at");   // a newer crossing replaces the text that waits
        e.apply();
    }

    /** Keeps a text that could not be sent. SyncService sends it when the link is back. */
    static void keepForLater(Context c, String text) {
        prefs(c).edit().putString("pending", text).putLong("pending_at", System.currentTimeMillis()).apply();
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

    /** The text as a web link, when the whole copy is one http(s) address; otherwise null. Only http(s), so a copy never opens anything else. */
    static Uri link(String text) {
        String s = text.trim();
        if (s.isEmpty() || s.length() >= 2048 || s.matches(".*\\s.*")) return null;
        Uri u = Uri.parse(s);
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        return (scheme.equals("https") || scheme.equals("http")) && u.getHost() != null && !u.getHost().isEmpty() ? u : null;
    }

    /** Short single-line preview of a crossing. */    static String preview(String text) {
        String p = text.trim().replaceAll("\\s+", " ");
        return p.length() > 160 ? p.substring(0, 160) + "…" : p;
    }

    /** True in Ferry Auto, the edition with automatic sending (installed from a computer). */
    static boolean autoEdition(Context c) {
        return c.getResources().getBoolean(R.bool.has_auto_send);
    }

    /**
     * Asks Android which accessibility services are on, instead of reading the raw setting:
     * some phones (Xiaomi) store the name in a short form that a text match misses.
     */
    static boolean autoSendOn(Context c) {
        if (!autoEdition(c)) return false;
        AccessibilityManager am = c.getSystemService(AccessibilityManager.class);
        for (AccessibilityServiceInfo s : am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            ServiceInfo info = s.getResolveInfo().serviceInfo;
            // By name: AutoSendService only exists in the auto edition's sources.
            if (c.getPackageName().equals(info.packageName) && info.name.endsWith(".AutoSendService")) return true;
        }
        return false;
    }

    /** Encrypts and sends text to the laptop. Blocks, so call it off the main thread. */
    static void send(Context c, String text, String tags) throws IOException {
        String code = code(c);
        if (code.isEmpty()) throw new IOException("Not paired");
        send(code, text, tags);
    }

    private static void send(String code, String text, String tags) throws IOException {
        try {
            post(code, Crypto.seal(code, text), tags);
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    /**
     * A message that sets up a direct Wi-Fi transfer: "offer" (this phone sends; port and ips set),
     * or "accept" (the laptop sends; ips empty when this phone is not on Wi-Fi). size is -1 when unknown.
     */
    static void postLan(Context c, String type, String id, String name, long size, int port, java.util.List<String> ips) throws IOException {
        String code = code(c);
        if (code.isEmpty()) throw new IOException("Not paired");
        try {
            JSONObject m = new JSONObject().put("t", type).put("id", id).put("port", port).put("ips", new JSONArray(ips));
            if (name != null) m.put("name", name).put("size", size);
            post(code, Crypto.sealLan(code, m.toString()), "phone,lan");
        } catch (JSONException | GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    /** A phone notification from Roost, for a pop-up on the laptop. Each part is cut to a short preview, so the message stays under ntfy.sh's 4 KB. */
    static void sendNotification(Context c, String app, String title, String text) throws IOException {
        String code = code(c);
        if (code.isEmpty()) throw new IOException("Not paired");
        try {
            JSONObject m = new JSONObject().put("app", preview(app)).put("title", preview(title)).put("text", preview(text));
            post(code, Crypto.sealNotification(code, m.toString()), "phone,notification");
        } catch (JSONException | GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    private static void post(String code, String body, String tags) throws IOException {
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

    /** ntfy.sh takes attachments up to 15 MB. The name and the encryption add a few hundred bytes. */
    static final int PART_BYTES = 14 * 1024 * 1024;
    /** Larger files go in parts. ntfy.sh keeps at most 100 MB of one sender's files at a time, so 90 MB leaves room. */
    static final long MAX_FILE_BYTES = 90L * 1024 * 1024;

    /** ntfy.sh refused a file: too large, or over its 100 MB of files per user. */
    static final class LimitException extends IOException {
        LimitException(int status) {
            super("ntfy.sh answered " + status);
        }
    }

    /** Encrypts and sends an image (PNG or JPEG bytes) to the laptop as a file. Blocks; call it off the main thread. */
    static void sendImage(Context c, byte[] image, String tags) throws IOException {
        String code = code(c);
        if (code.isEmpty()) throw new IOException("Not paired");
        try {
            put(code, Crypto.sealImage(code, image), tags, "image");
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    /**
     * Encrypts and sends a file with its name. ntfy.sh only sees "file.ferry" or "part.ferry" and the size.
     * First a short notice tells the laptop what is coming. A file over 14 MB goes in 14 MB parts.
     * Reports the percent sent to progress. Blocks; call it off the main thread.
     */
    static void sendFile(Context c, String name, File file, String tags, IntConsumer progress) throws IOException {
        String code = code(c);
        if (code.isEmpty()) throw new IOException("Not paired");
        long size = file.length();
        int count = (int) Math.max(1, (size + PART_BYTES - 1) / PART_BYTES);
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            post(code, Crypto.sealIncoming(code, size, name), tags + ",incoming");
            byte[] id = Crypto.newId();
            for (int i = 0; i < count; i++) {
                byte[] part = new byte[(int) Math.min(PART_BYTES, size - (long) i * PART_BYTES)];
                in.readFully(part);
                int done = i;
                IntConsumer partProgress = percent -> progress.accept((done * 100 + percent) / count);
                // One part goes the old way, so a laptop with an older Ferry still gets files up to 14 MB.
                if (count == 1) put(code, Crypto.sealFile(code, name, part), tags, "file", partProgress);
                else put(code, Crypto.sealPart(code, id, i, count, name, part), tags, "part", partProgress);
            }
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    /**
     * Copies a shared file into the app's cache, so its size is known before sending.
     * Throws TooLargeException when it is over MAX_FILE_BYTES. The caller deletes the copy.
     */
    static File stage(Context c, Uri uri) throws IOException {
        File dir = new File(c.getCacheDir(), "outgoing");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        File file = File.createTempFile("send", null, dir);
        try (InputStream in = c.getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(file)) {
            if (in == null) throw new IOException("Cannot open " + uri);
            byte[] chunk = new byte[64 * 1024];
            long total = 0;
            for (int n; (n = in.read(chunk)) != -1; ) {
                total += n;
                if (total > MAX_FILE_BYTES) throw new TooLargeException();
                out.write(chunk, 0, n);
            }
        } catch (IOException | RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            throw e;
        }
        return file;
    }

    static final class TooLargeException extends IOException {
    }

    private static void put(String code, byte[] body, String tags, String kind) throws IOException {
        put(code, body, tags, kind, percent -> {});
    }

    private static void put(String code, byte[] body, String tags, String kind, IntConsumer progress) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(SERVER + Crypto.topic(code)).openConnection();
        try {
            con.setRequestMethod("PUT");
            con.setDoOutput(true);
            con.setFixedLengthStreamingMode(body.length);
            con.setConnectTimeout(15_000);
            con.setReadTimeout(120_000);   // a few MB on a slow connection
            con.setRequestProperty("Filename", kind + ".ferry");
            con.setRequestProperty("Tags", tags + "," + kind);
            try (OutputStream out = con.getOutputStream()) {
                // In 64 KB parts: with a fixed length, each write goes to the network, so the percent is real.
                for (int at = 0, part = 64 * 1024; at < body.length; at += part) {
                    out.write(body, at, Math.min(part, body.length - at));
                    progress.accept((int) (100L * Math.min(at + part, body.length) / body.length));
                }
            }
            int status = con.getResponseCode();
            if (status == 413 || status == 429) throw new LimitException(status);
            if (status != 200) throw new IOException("ntfy.sh answered " + status);
        } finally {
            con.disconnect();
        }
    }

    /** Writes a file's contents. A direct transfer writes straight into Downloads, with no copy in between. */
    interface Contents {
        void writeTo(OutputStream out) throws IOException;
    }

    static Contents from(InputStream in) {
        return out -> {
            byte[] chunk = new byte[64 * 1024];
            for (int n; (n = in.read(chunk)) != -1; ) out.write(chunk, 0, n);
        };
    }

    /** The size of a shared file, or -1 when the sharing app does not say. */
    static long size(Context c, Uri uri) {
        try (Cursor cur = c.getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cur != null && cur.moveToFirst() && !cur.isNull(0)) return cur.getLong(0);
        } catch (RuntimeException e) {
            // Some apps share links that cannot be queried.
        }
        return -1;
    }

    /** The bar of a file on its way, in a quiet notification that stays until the transfer ends. */
    static void progress(Context c, int id, String title, long bytes, int percent) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("sending", "Sending files", NotificationManager.IMPORTANCE_LOW));
        nm.notify(id, new Notification.Builder(c, "sending")
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(title)
                .setContentText(bytes < 0 ? percent + "%" : percent + "% of " + String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0))
                .setProgress(100, percent, bytes < 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build());
    }

    /** Passes on at most 4 updates a second: Android drops notification updates that come faster. */
    static IntConsumer throttled(IntConsumer progress) {
        long[] shown = {0};
        return percent -> {
            long now = SystemClock.uptimeMillis();
            if (now - shown[0] < 250) return;
            shown[0] = now;
            progress.accept(percent);
        };
    }

    /** Saves a file in Downloads/Ferry and returns its content:// link. Android renames it if the name is taken. */
    static Uri saveDownload(Context c, String name, Contents contents) throws IOException {
        // No folder parts or characters that Android refuses, and no hidden ".name" files.
        String safe = name.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_").replaceAll("^[.\\s]+|[.\\s]+$", "");
        if (safe.isEmpty()) safe = "file";
        int dot = safe.lastIndexOf('.');
        String ext = dot < 0 ? "" : safe.substring(dot + 1).toLowerCase(Locale.ROOT);
        String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        ContentResolver cr = c.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, safe);
        v.put(MediaStore.Downloads.MIME_TYPE, type != null ? type : "application/octet-stream");
        v.put(MediaStore.Downloads.RELATIVE_PATH, "Download/Ferry");
        v.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri item = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (item == null) throw new IOException("Downloads refused " + safe);
        try (OutputStream out = cr.openOutputStream(item)) {
            if (out == null) throw new IOException("Cannot write " + safe);
            contents.writeTo(out);
        } catch (IOException e) {
            cr.delete(item, null, null);   // no empty, half-written file left in Downloads
            throw e;
        }
        v.clear();
        v.put(MediaStore.Downloads.IS_PENDING, 0);
        cr.update(item, v, null, null);
        return item;
    }

    /** Opens a saved file in an app that can show it. */
    static Intent viewFile(Context c, Uri uri) {
        return new Intent(Intent.ACTION_VIEW).setDataAndType(uri, c.getContentResolver().getType(uri))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** The display name of a shared file, or "file" when the sharing app does not give one. */
    static String displayName(Context c, Uri uri) {
        try (Cursor cur = c.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst() && cur.getString(0) != null) return cur.getString(0);
        } catch (RuntimeException e) {
            // Some apps share links that cannot be queried. The name is only a label.
        }
        String last = uri.getLastPathSegment();
        return last == null || last.isEmpty() ? "file" : last;
    }

    static byte[] readBytes(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[64 * 1024];
        for (int n; (n = in.read(chunk)) != -1; ) {
            buf.write(chunk, 0, n);
            if (buf.size() > limit) throw new IOException("File too large");
        }
        return buf.toByteArray();
    }

    static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        for (int n; (n = in.read(chunk)) != -1; ) buf.write(chunk, 0, n);
        return buf.toString("UTF-8");
    }

    private Ferry() {}
}
