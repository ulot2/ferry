package app.ferry;

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
    private static final String CHANNEL = "sync";
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private volatile HttpURLConnection con;
    private Thread worker;
    private volatile String label = "Connecting…";

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
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        Ferry.status(this, Ferry.STATUS_OFFLINE);
        HttpURLConnection c = con;
        if (c != null) c.disconnect();   // unblocks the read in loop()
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void loop() {
        String topic = Ferry.topic(this);
        while (running) {
            try {
                con = (HttpURLConnection) new URL(Ferry.SERVER + topic + "/json").openConnection();
                con.setConnectTimeout(15_000);
                con.setReadTimeout(90_000);   // ntfy.sh sends a keepalive every 45 s; silence means the link is dead
                BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8));
                state(Ferry.STATUS_CONNECTED, "Connected");
                for (String line; (line = in.readLine()) != null; ) {
                    JSONObject m = new JSONObject(line);
                    if (!"message".equals(m.optString("event")) || hasTag(m, "phone")) continue;
                    String text = m.has("attachment") ? fetchText(m.getJSONObject("attachment")) : m.optString("message");
                    if (text != null && !text.isEmpty()) {
                        setClipboard(text);
                        Ferry.crossed(this, "Laptop → Phone", text);
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
