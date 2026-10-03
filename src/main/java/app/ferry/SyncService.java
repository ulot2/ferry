package app.ferry;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Keeps a connection to ntfy.sh open and puts whatever the laptop copies into this phone's clipboard. */
public class SyncService extends Service {
    private static final String CHANNEL = "sync", CROSSINGS = "crossings";
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private volatile HttpURLConnection con;
    private Thread worker;
    private volatile String label = "Connecting…";
    private ContentObserver screenshots;
    private long lastScreenshot;   // MediaStore id of the last screenshot sent

    static void start(Context c) {
        c.startForegroundService(new Intent(c, SyncService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Connection", NotificationManager.IMPORTANCE_MIN));
        startForeground(1, status(label));
        if (worker == null) {
            Ferry.status(this, Ferry.STATUS_CONNECTING);
            running = true;
            worker = new Thread(this::loop, "ferry-sync");
            worker.start();
            watchScreenshots();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        Ferry.status(this, Ferry.STATUS_OFFLINE);
        if (screenshots != null) getContentResolver().unregisterContentObserver(screenshots);
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
                for (String line; (line = in.readLine()) != null; ) {
                    JSONObject m = new JSONObject(line);
                    if (!"message".equals(m.optString("event"))) continue;
                    Ferry.prefs(this).edit().putString("last_id", m.optString("id")).apply();
                    if (hasTag(m, "phone")) continue;   // our own message coming back
                    if (hasTag(m, "image")) {
                        receiveImage(code, m);
                        continue;
                    }
                    String sealed = m.has("attachment") ? fetchText(m.getJSONObject("attachment")) : m.optString("message");
                    String text = Crypto.open(code, sealed);   // null: not from our laptop, ignore it
                    if (text == null || text.isEmpty()) continue;
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
                if (running) {
                    state(Ferry.STATUS_OFFLINE, "Offline, retrying");
                    SystemClock.sleep(5_000);
                }
            } finally {
                HttpURLConnection c = con;
                if (c != null) c.disconnect();
            }
        }
    }

    /** An image from the laptop: a sealed file on ntfy.sh. Errors stay here, so the text connection keeps running. */
    private void receiveImage(String code, JSONObject m) {
        if (!Ferry.imagesOn(this)) return;
        JSONObject att = m.optJSONObject("attachment");
        if (att == null || !att.optString("url").startsWith(Ferry.SERVER) || att.optLong("size") > 16L * 1024 * 1024) return;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(att.getString("url")).openConnection();
            c.setConnectTimeout(15_000);
            c.setReadTimeout(120_000);
            byte[] sealed;
            try (InputStream s = c.getInputStream()) {
                sealed = Ferry.readBytes(s, 16 * 1024 * 1024);
            } finally {
                c.disconnect();
            }
            byte[] image = Crypto.openImage(code, sealed);
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
        notifyCrossing("Copied from " + (peer.isEmpty() ? "your laptop" : peer), Ferry.preview(text), null);
    }

    private void notifyCrossing(String title, String text, Bitmap picture) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CROSSINGS, "Crossings", NotificationManager.IMPORTANCE_HIGH);
        ch.setSound(null, null);
        ch.enableVibration(false);
        nm.createNotificationChannel(ch);
        Notification.Builder b = new Notification.Builder(this, CROSSINGS)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true)
                .setTimeoutAfter(8_000);
        if (picture != null) b.setLargeIcon(picture).setStyle(new Notification.BigPictureStyle().bigPicture(picture));
        nm.notify(2, b.build());
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
                .build();
    }
}
