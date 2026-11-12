package app.ferry;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import java.io.File;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.IntConsumer;

/** Keeps a connection to ntfy.sh open and puts whatever the laptop copies into this phone's clipboard. */
public class SyncService extends Service {
    private static final String CHANNEL = "sync", CROSSINGS = "crossings";
    static final String STOP_PHOTOS = "app.ferry.STOP_PHOTOS";
    private static final int PHOTOS_SHOWN = 6;   // notification id while the laptop looks at photos
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private volatile HttpURLConnection con;
    private Thread worker;
    private volatile String label = "Connecting…";
    private ContentObserver screenshots;
    private long lastScreenshot;   // MediaStore id of the last screenshot sent
    private ConnectivityManager.NetworkCallback netWatch;
    private Network network;
    private volatile boolean skipWait;   // a reconnect on purpose: no 5 s wait, no "Offline" in the notification
    private String partId;   // the file whose parts are coming in, and the part expected next
    private int partNext;

    static void start(Context c) {
        c.startForegroundService(new Intent(c, SyncService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP_PHOTOS.equals(intent.getAction())) Photos.stop();
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Connection", NotificationManager.IMPORTANCE_MIN));
        startForeground(1, status(label));
        if (worker == null) {
            Ferry.status(this, Ferry.STATUS_CONNECTING);
            running = true;
            worker = new Thread(this::loop, "ferry-sync");
            worker.start();
            watchScreenshots();
            watchNetwork();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        Ferry.status(this, Ferry.STATUS_OFFLINE);
        if (screenshots != null) getContentResolver().unregisterContentObserver(screenshots);
        if (netWatch != null) getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netWatch);
        HttpURLConnection c = con;
        if (c != null) c.disconnect();   // unblocks the read in loop()
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void loop() {
        String code = Ferry.code(this);
        if (code.isEmpty()) {
            stopSelf();   // not paired (or paired before encryption): nothing to listen to
            return;
        }
        String topic = Crypto.topic(code);
        while (running) {
            try {
                // "since" replays what arrived while we were away, so a short disconnection loses nothing.
                String since = Ferry.prefs(this).getString("last_id", "");
                con = (HttpURLConnection) new URL(Ferry.SERVER + topic + "/json" + (since.isEmpty() ? "" : "?since=" + since)).openConnection();
                con.setConnectTimeout(15_000);
                con.setReadTimeout(90_000);   // ntfy.sh sends a keepalive every 45 s; silence means the link is dead
                BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8));
                state(Ferry.STATUS_CONNECTED, "Connected");
                new Thread(this::sendPending).start();
                for (String line; (line = in.readLine()) != null; ) {
                    JSONObject m = new JSONObject(line);
                    if (!"message".equals(m.optString("event"))) continue;
                    Ferry.prefs(this).edit().putString("last_id", m.optString("id")).apply();
                    if (hasTag(m, "phone")) continue;   // our own message coming back
                    if (hasTag(m, "image")) {
                        receiveImage(code, m);
                        continue;
                    }
                    if (hasTag(m, "file")) {
                        receiveFile(code, m);
                        continue;
                    }
                    if (hasTag(m, "part")) {
                        receivePart(code, m);
                        continue;
                    }
                    if (hasTag(m, "incoming")) {
                        announceIncoming(code, m);
                        continue;
                    }
                    if (hasTag(m, "lan")) {
                        lanMessage(code, m);
                        continue;
                    }
                    String sealed = m.has("attachment") ? fetchText(m.getJSONObject("attachment")) : m.optString("message");
                    String text = Crypto.open(code, sealed);   // null: not from our laptop, ignore it
                    if (text == null || text.isEmpty()) continue;
                    if (hasTag(m, "unpair")) {
                        laptopUnpaired();
                        return;
                    }
                    long at = m.optLong("time", System.currentTimeMillis() / 1000) * 1000;
                    Ferry.crossed(this, Ferry.TO_PHONE, text, at);
                    // A copy that waited more than 10 minutes goes to history only; it must not replace what you copied since.
                    if (System.currentTimeMillis() - at < 10 * 60_000) {
                        setClipboard(text);
                        announce(text);
                    }
                }
            } catch (Exception e) {
                // ponytail: fixed 5 s retry; add backoff if it drains battery while offline
                if (running && !skipWait) {
                    state(Ferry.STATUS_OFFLINE, "Offline, retrying");
                    SystemClock.sleep(5_000);
                }
                skipWait = false;
            } finally {
                HttpURLConnection c = con;
                if (c != null) c.disconnect();
            }
        }
    }

    /**
     * After the phone changes network (Wi-Fi to mobile data, or Wi-Fi back), the old stream is dead,
     * and the read timeout needs up to 90 s to notice. Drop it at once, so loop() connects again.
     */
    private void watchNetwork() {
        netWatch = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network n) {
                // Android calls this once for the current network, then for each new one.
                if (network != null && !network.equals(n)) {
                    skipWait = true;
                    HttpURLConnection c = con;
                    if (c != null) c.disconnect();   // unblocks the read in loop()
                }
                network = n;
            }
        };
        getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(netWatch);
    }

    /** The laptop reset its pairing. Its old code is dead, so this phone unpairs too and says so. */
    private void laptopUnpaired() {
        String peer = Ferry.prefs(this).getString("peer", "");
        running = false;
        notifyCrossing("Unpaired from " + (peer.isEmpty() ? "your laptop" : peer),
                "The laptop reset its pairing. To pair again, open Ferry and scan the new code.", null);
        main.post(() -> Ferry.forget(this));
    }

    /** Sends the text that SendActivity could not send. A text older than 10 minutes is dropped, the same rule as for arrivals. */
    private synchronized void sendPending() {
        SharedPreferences p = Ferry.prefs(this);
        String text = p.getString("pending", null);
        if (text == null) return;
        if (System.currentTimeMillis() - p.getLong("pending_at", 0) > 10 * 60_000) {
            p.edit().remove("pending").remove("pending_at").apply();
            return;
        }
        try {
            Ferry.send(this, text, "phone");
            Ferry.crossed(this, Ferry.TO_LAPTOP, text, System.currentTimeMillis());   // this also clears "pending"
        } catch (IOException e) {
            Log.w("Ferry", "waiting text not sent: " + e);   // still offline: try again on the next connection
        }
    }

    /** An image from the laptop: a sealed file on ntfy.sh. Errors stay here, so the text connection keeps running. */
    private void receiveImage(String code, JSONObject m) {
        if (!Ferry.imagesOn(this)) return;
        try {
            byte[] sealed = fetchSealed(m);
            byte[] image = sealed == null ? null : Crypto.openImage(code, sealed);
            if (image == null) return;   // not from our laptop
            long at = m.optLong("time", System.currentTimeMillis() / 1000) * 1000;
            File file = Images.keep(this, image);
            Ferry.crossed(this, Ferry.TO_PHONE, "Image", at, file.getAbsolutePath());
            if (System.currentTimeMillis() - at < 10 * 60_000) {
                main.post(() -> getSystemService(ClipboardManager.class).setPrimaryClip(Images.clip(this, file)));
                String peer = Ferry.prefs(this).getString("peer", "");
                notifyCrossing("Image from " + (peer.isEmpty() ? "your laptop" : peer), "In your clipboard and in Pictures/Ferry", preview(file));
            }
        } catch (Exception e) {
            Log.w("Ferry", "image not received: " + e);
        }
    }

    /** A file from the laptop: a sealed attachment, saved in Downloads/Ferry. Errors stay here, so the text connection keeps running. */
    private void receiveFile(String code, JSONObject m) {
        try {
            byte[] sealed = fetchSealed(m);
            Crypto.SealedFile file = sealed == null ? null : Crypto.openFile(code, sealed);
            if (file == null) return;   // not from our laptop
            landed(file.name, Ferry.from(new ByteArrayInputStream(file.contents)), time(m));
        } catch (Exception e) {
            Log.w("Ferry", "file not received: " + e);
        }
    }

    /**
     * One part of a file over 14 MB. Parts come in order, so each one is added to a file in the cache,
     * and the last one moves it to Downloads/Ferry. A missing part drops the whole file.
     * ponytail: one file at a time and kept in memory only; a restart of Ferry in the middle drops that file.
     */
    private void receivePart(String code, JSONObject m) {
        try {
            byte[] sealed = fetchSealed(m);
            Crypto.Part part = sealed == null ? null : Crypto.openPart(code, sealed);
            if (part == null) return;   // not from our laptop
            File file = new File(getCacheDir(), "incoming.part");
            if (part.index == 0) {
                partId = part.id;
                partNext = 0;
            }
            if (!part.id.equals(partId) || part.index != partNext) {
                Log.w("Ferry", "file part missing: " + part.name + " dropped");
                partId = null;
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                return;
            }
            try (OutputStream out = new FileOutputStream(file, part.index > 0)) {
                out.write(part.contents);
            }
            if (++partNext < part.count) return;
            partId = null;
            try (InputStream in = new FileInputStream(file)) {
                landed(part.name, Ferry.from(in), time(m));
            } finally {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        } catch (Exception e) {
            Log.w("Ferry", "file part not received: " + e);
        }
    }

    /**
     * A message that sets up a direct Wi-Fi transfer. "request": the laptop wants to send a file.
     * "nolan": the laptop cannot connect to the file this phone offered, so the phone uses ntfy.sh at once.
     * Older messages are replays after a reconnect: the other side stopped waiting long ago.
     */
    private void lanMessage(String code, JSONObject m) {
        String json = Crypto.openLan(code, m.optString("message"));
        if (json == null || System.currentTimeMillis() - time(m) > 2 * 60_000) return;
        try {
            JSONObject lan = new JSONObject(json);
            String type = lan.optString("t");
            if (type.equals("nolan")) Lan.cancel(lan.optString("id"));
            else if (type.equals("request")) new Thread(() -> receiveDirect(code, lan, time(m)), "ferry-lan").start();
            else if (type.equals("open")) new Thread(() -> openSession(lan), "ferry-session").start();
        } catch (org.json.JSONException e) {
            Log.w("Ferry", "Wi-Fi message unreadable: " + e);
        }
    }

    private static final int RECEIVING = 5;   // notification id of the bar of a file coming in

    /**
     * The laptop sends a file straight to this phone. The phone listens and tells the laptop where;
     * with no Wi-Fi address it says so, and the laptop uses ntfy.sh. The file goes straight into Downloads/Ferry.
     */
    private void receiveDirect(String code, JSONObject request, long at) {
        String key = request.optString("id"), name = request.optString("name", "file");
        long size = request.optLong("size", -1);
        byte[] id = Lan.unhex(key);
        if (id == null) return;
        java.util.List<String> ips = Lan.addresses(this);
        try {
            java.net.ServerSocket server = ips.isEmpty() ? null : Lan.listen(key);
            Ferry.postLan(this, "accept", key, null, 0, server == null ? 0 : server.getLocalPort(), ips);
            if (server == null) return;
            java.net.Socket s = Lan.accept(server, key, code, id, 20_000);
            if (s == null) return;
            notifyCrossing("Coming from " + peer(), name + (size < 0 ? "" : ", " + String.format(Locale.ROOT, "%.1f MB", size / 1048576.0)), null);
            IntConsumer bar = Ferry.throttled(percent -> Ferry.progress(this, RECEIVING, "Receiving " + name, size, percent));
            try {
                landed(name, out -> Lan.receive(s, code, id, out, size, bar), at);
            } finally {
                s.close();
                getSystemService(NotificationManager.class).cancel(RECEIVING);
            }
        } catch (IOException e) {
            Lan.cancel(key);   // the accept did not go out: stop listening
            Log.w("Ferry", "Wi-Fi file not received: " + e);
        }
    }

    /**
     * The laptop asks for a session. Only "photos" for now, and only while "Let the laptop see photos" is on and Ferry may
     * read the photos. Otherwise the phone tells the laptop why not. While the laptop looks, a notification says so and can stop it.
     */
    private void openSession(JSONObject open) {
        byte[] id = Lan.unhex(open.optString("id"));
        if (id == null) return;
        String what = open.optString("for");
        String why = !what.equals("photos") ? "unknown" : !Ferry.photosOn(this) ? "off" : !canReadPhotos(this) ? "permission" : null;
        Session s;
        try {
            s = Session.answer(this, id, what, why, 15_000);
        } catch (IOException e) {
            Log.w("Ferry", "session not answered: " + e);
            return;
        }
        if (s == null) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        crossingsChannel(nm);   // a visible notice, not the quiet status channel: the user must see that the laptop looks
        PendingIntent stop = PendingIntent.getService(this, PHOTOS_SHOWN,
                new Intent(this, SyncService.class).setAction(STOP_PHOTOS), PendingIntent.FLAG_IMMUTABLE);
        String who = peer();
        nm.notify(PHOTOS_SHOWN, new Notification.Builder(this, CROSSINGS)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(Character.toUpperCase(who.charAt(0)) + who.substring(1) + " is looking at your photos")
                .setContentText("Ferry lets the laptop see them while its Phone photos window is open.")
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .build());
        try {
            Photos.serve(this, s);
        } finally {
            nm.cancel(PHOTOS_SHOWN);
        }
    }

    private static long time(JSONObject m) {
        return m.optLong("time", System.currentTimeMillis() / 1000) * 1000;
    }

    /** Saves a whole file in Downloads/Ferry, adds it to the history, and says so. */
    private void landed(String name, Ferry.Contents contents, long at) throws IOException {
        Uri saved = Ferry.saveDownload(this, name, contents);
        String shown = Ferry.displayName(this, saved);   // Android adds " (1)" when the name is taken
        Ferry.crossedFile(this, Ferry.TO_PHONE, shown, at, saved.toString());
        if (System.currentTimeMillis() - at < 10 * 60_000) {
            PendingIntent open = PendingIntent.getActivity(this, 4, Ferry.viewFile(this, saved), PendingIntent.FLAG_IMMUTABLE);
            notifyCrossing("File from " + peer(), shown + ", in Downloads/Ferry", null, open, "Open");
        }
    }

    /** The laptop is about to send a file: say what is coming before it is all here. */
    private void announceIncoming(String code, JSONObject m) {
        String notice = Crypto.openIncoming(code, m.optString("message"));
        int nl = notice == null ? -1 : notice.indexOf('\n');
        if (nl < 0) return;   // not from our laptop
        long at = m.optLong("time", System.currentTimeMillis() / 1000) * 1000;
        if (System.currentTimeMillis() - at > 10 * 60_000) return;   // the file itself says so when it lands
        long bytes;
        try {
            bytes = Long.parseLong(notice.substring(0, nl));
        } catch (NumberFormatException e) {
            return;
        }
        notifyCrossing("Coming from " + peer(), notice.substring(nl + 1) + ", " + String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0), null);
    }

    private String peer() {
        String peer = Ferry.prefs(this).getString("peer", "");
        return peer.isEmpty() ? "your laptop" : peer;
    }

    /** Downloads a sealed attachment, only from ntfy.sh and only up to 16 MB. Returns null for anything else. */
    private static byte[] fetchSealed(JSONObject m) throws IOException {
        JSONObject att = m.optJSONObject("attachment");
        if (att == null || !att.optString("url").startsWith(Ferry.SERVER) || att.optLong("size") > 16L * 1024 * 1024) return null;
        HttpURLConnection c = (HttpURLConnection) new URL(att.optString("url")).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(120_000);
        try (InputStream s = c.getInputStream()) {
            return Ferry.readBytes(s, 16 * 1024 * 1024);
        } finally {
            c.disconnect();
        }
    }

    static boolean canReadPhotos(Context c) {
        String p = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
        return c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    /** Opt-in: send each new screenshot. Android does not put screenshots on the clipboard, so Ferry watches for new ones. */
    private void watchScreenshots() {
        if (!Ferry.screenshotsOn(this) || !canReadPhotos(this)) return;
        screenshots = new ContentObserver(main) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                new Thread(SyncService.this::sendNewScreenshot).start();
            }
        };
        getContentResolver().registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, screenshots);
    }

    private synchronized void sendNewScreenshot() {
        Bundle q = new Bundle();
        q.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, MediaStore.Images.Media.RELATIVE_PATH + " LIKE ? AND "
                + MediaStore.Images.Media.DATE_ADDED + " >= ? AND " + MediaStore.Images.Media.IS_PENDING + " = 0");
        q.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                new String[]{"%Screenshots%", String.valueOf(System.currentTimeMillis() / 1000 - 20)});
        q.putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, new String[]{MediaStore.Images.Media.DATE_ADDED});
        q.putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING);
        q.putInt(ContentResolver.QUERY_ARG_LIMIT, 1);
        try (Cursor cur = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media._ID}, q, null)) {
            if (cur == null || !cur.moveToFirst()) return;
            long id = cur.getLong(0);
            if (id == lastScreenshot) return;   // one screenshot fires several changes
            lastScreenshot = id;
            byte[] image = Images.read(this, ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id));
            image = image == null ? null : Images.shrink(image);
            if (image == null) return;
            Ferry.sendImage(this, image, "phone");
            Ferry.crossed(this, Ferry.TO_LAPTOP, "Screenshot", System.currentTimeMillis(), "");
            String peer = Ferry.prefs(this).getString("peer", "");
            notifyCrossing("Screenshot sent to " + (peer.isEmpty() ? "your laptop" : peer), "It is in the laptop's clipboard", null);
        } catch (Exception e) {
            Log.w("Ferry", "screenshot not sent: " + e);
        }
    }

    /** A small preview for the notification, without loading a full-size image. */
    private static Bitmap preview(File file) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), o);
        o.inSampleSize = Math.max(1, Math.max(o.outWidth, o.outHeight) / 720);
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(file.getPath(), o);
    }

    private static boolean hasTag(JSONObject m, String tag) {
        JSONArray tags = m.optJSONArray("tags");
        for (int i = 0; tags != null && i < tags.length(); i++) if (tag.equals(tags.optString(i))) return true;
        return false;
    }

    /** ntfy.sh turns long text into a file. Fetch it if it is text from ntfy.sh; skip anything else, such as photos. */
    private static String fetchText(JSONObject att) throws IOException {
        String url = att.optString("url");
        if (!att.optString("type").startsWith("text/") || !url.startsWith(Ferry.SERVER)) return null;
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try (InputStream s = c.getInputStream()) {
            return Ferry.readAll(s);
        } finally {
            c.disconnect();
        }
    }

    private void setClipboard(String text) {
        main.post(() -> getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Ferry", text)));
    }

    /** A short pop-up (no sound) saying what just landed in the clipboard. It clears itself after 8 seconds. */
    private void announce(String text) {
        String peer = Ferry.prefs(this).getString("peer", "");
        String from = peer.isEmpty() ? "your laptop" : peer;
        Uri link = Ferry.link(text);
        if (link != null) {
            // A copied link: tapping the notification (or Open link) opens it in the browser.
            PendingIntent open = PendingIntent.getActivity(this, 3, new Intent(Intent.ACTION_VIEW, link)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE);
            notifyCrossing("Link from " + from, link.toString(), null, open, "Open link");
        } else {
            notifyCrossing("Copied from " + from, Ferry.preview(text), null);
        }
    }

    private void notifyCrossing(String title, String text, Bitmap picture) {
        notifyCrossing(title, text, picture, null, null);
    }

    /** With open set, tapping the notification (or the action button with openLabel) opens the link or the file. */
    private void notifyCrossing(String title, String text, Bitmap picture, PendingIntent open, String openLabel) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        crossingsChannel(nm);
        Notification.Builder b = new Notification.Builder(this, CROSSINGS)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true)
                .setTimeoutAfter(8_000);
        if (picture != null) b.setLargeIcon(picture).setStyle(new Notification.BigPictureStyle().bigPicture(picture));
        if (open != null) {
            b.setContentIntent(open).addAction(new Notification.Action.Builder(null, openLabel, open).build());
        }
        nm.notify(2, b.build());
    }

    /** Pop-ups without sound: what crossed, and while the laptop looks at photos. */
    private static void crossingsChannel(NotificationManager nm) {
        NotificationChannel ch = new NotificationChannel(CROSSINGS, "Crossings", NotificationManager.IMPORTANCE_HIGH);
        ch.setSound(null, null);
        ch.enableVibration(false);
        nm.createNotificationChannel(ch);
    }

    private void state(String status, String label) {
        this.label = label;
        Ferry.status(this, status);
        getSystemService(NotificationManager.class).notify(1, status(label));
    }

    private Notification status(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent send = PendingIntent.getActivity(this, 1,
                new Intent(this, SendActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle("Ferry")
                .setContentText(text)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Send clipboard", send).build())
                .setOngoing(true)
                .setOnlyAlertOnce(true)   // status updates change the text quietly; no pop-up on each reconnect
                .build();
    }
}
