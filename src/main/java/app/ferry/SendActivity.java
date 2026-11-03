package app.ferry;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Invisible screen that sends to the laptop: shared or selected text or images if there are some,
 * otherwise the clipboard. Android only lets an app read the clipboard while it has focus,
 * which is why this has to be an activity and not a background job.
 */
public class SendActivity extends Activity {
    /** Set by AutoSendService after a copy. Stays quiet when there is nothing new to send. */
    static final String EXTRA_AUTO = "auto";
    private static final int PROGRESS = 3;   // notification id; 1 is the connection, 2 the last crossing
    private boolean started, auto;

    @Override
    @SuppressWarnings("deprecation")   // the typed getParcelableExtra needs API 33; Ferry runs from API 29
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        auto = getIntent().getBooleanExtra(EXTRA_AUTO, false);
        if (Intent.ACTION_SEND_MULTIPLE.equals(getIntent().getAction())) {
            // Several files, also several photos: they go as files, since the clipboard holds one image only.
            List<Uri> uris = getIntent().getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (uris != null && !uris.isEmpty()) sendFiles(uris);
            else done("Nothing to send");
            return;
        }
        String type = getIntent().getType();
        Object stream = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
        if (type != null && type.startsWith("image/") && stream instanceof Uri) {
            sendImage((Uri) stream);
            return;
        }
        if (stream instanceof Uri) {
            sendFiles(Collections.singletonList((Uri) stream));   // any other shared file, also a .txt file
            return;
        }
        CharSequence text = getIntent().getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
        if (text == null) text = getIntent().getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (text != null) send(text.toString());
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus || started) return;
        ClipData clip = getSystemService(ClipboardManager.class).getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            Log.i("Ferry", "send screen: clipboard empty or unreadable");
            done(auto ? null : "Clipboard is empty");
            return;
        }
        // Password managers mark their copies as sensitive (Android 13+). Automatic sending never sends those;
        // tapping Send yourself still does, because then you chose to.
        PersistableBundle extras = clip.getDescription().getExtras();
        if (auto && extras != null && extras.getBoolean("android.content.extra.IS_SENSITIVE")) {   // ClipDescription.EXTRA_IS_SENSITIVE
            Log.i("Ferry", "send screen: marked sensitive (password), not sent");
            done(null);
            return;
        }
        Uri uri = clip.getItemAt(0).getUri();
        if (uri != null && clip.getDescription().hasMimeType("image/*")) {
            // Ours means it came from the laptop; the same picture again means it already crossed.
            if (Images.isOurs(uri) || (auto && uri.toString().equals(Ferry.prefs(this).getString("last_image", "")))) {
                Log.i("Ferry", "send screen: image already crossed, not sending again");
                done(null);
                return;
            }
            sendImage(uri);
            return;
        }
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        if (text == null || text.length() == 0) {
            Log.i("Ferry", "send screen: clipboard empty or unreadable");
            done(auto ? null : "Clipboard is empty");
            return;
        }
        if (auto && text.toString().equals(Ferry.lastText(this))) {
            Log.i("Ferry", "send screen: already crossed, not sending again");
            done(null);   // already crossed (for example, it came from the laptop)
            return;
        }
        send(text.toString());
    }

    @Override
    protected void onPause() {
        super.onPause();
        overridePendingTransition(0, 0);   // no flash when this invisible screen closes
    }

    private void send(String text) {
        started = true;
        if (!Ferry.paired(this)) {
            done("Open Ferry and pair it with your laptop first");
            return;
        }
        new Thread(() -> {
            String result;
            try {
                Ferry.send(this, text, "phone");
                Ferry.crossed(this, Ferry.TO_LAPTOP, text, System.currentTimeMillis());
                result = "Sent to laptop";
                Log.i("Ferry", "send screen: sent");
            } catch (IOException e) {
                Log.w("Ferry", "send screen: not sent: " + e);
                Ferry.keepForLater(this, text);
                result = "Not sent yet. Ferry could not reach ntfy.sh. It sends the text when the link is back.";
            }
            String r = result;
            runOnUiThread(() -> done(r));
        }).start();
    }

    private void sendImage(Uri uri) {
        started = true;
        if (!Ferry.paired(this)) {
            done("Open Ferry and pair it with your laptop first");
            return;
        }
        if (!Ferry.imagesOn(this)) {
            done(auto ? null : "Images are turned off in Ferry");
            return;
        }
        // Read now, while this screen still holds the permission to the picture.
        byte[] raw;
        try {
            raw = Images.read(this, uri);
        } catch (IOException | SecurityException e) {
            Log.w("Ferry", "send screen: image unreadable: " + e);
            done("Ferry could not read that image");
            return;
        }
        if (raw == null) {
            done("That image is too large to send");
            return;
        }
        Toast.makeText(getApplicationContext(), "Sending image…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String result;
            try {
                byte[] image = Images.shrink(raw);
                if (image == null) {
                    result = "That image is over 10 MB, too large to send";
                } else {
                    Ferry.sendImage(this, image, "phone");
                    Ferry.crossed(this, Ferry.TO_LAPTOP, "Image", System.currentTimeMillis(), "");
                    Ferry.prefs(this).edit().putString("last_image", uri.toString()).apply();
                    result = "Image sent to laptop";
                    Log.i("Ferry", "send screen: image sent");
                }
            } catch (IOException e) {
                Log.w("Ferry", "send screen: image not sent: " + e);
                result = "Image not sent. Ferry could not reach ntfy.sh. Try again when you are online.";
            }
            String r = result;
            runOnUiThread(() -> done(r));
        }).start();
    }

    /** Files shared with Ferry, sent one after the other. Only Share sends files: Ferry never sends a file because it was copied. */
    private void sendFiles(List<Uri> uris) {
        started = true;
        if (!Ferry.paired(this)) {
            done("Open Ferry and pair it with your laptop first");
            return;
        }
        String first = Ferry.displayName(this, uris.get(0));
        Toast.makeText(getApplicationContext(), uris.size() == 1 ? "Sending " + first + "…" : "Sending " + uris.size() + " files…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String failed = null;
            int sent = 0;
            // Open them all first: the permission to read shared files can end when this screen closes,
            // but a file that is open stays readable. Files up to 90 MB are copied, so ntfy.sh can take them
            // when Wi-Fi cannot. Larger files are read once, straight onto the Wi-Fi connection.
            int count = uris.size();
            String[] names = new String[count];
            File[] staged = new File[count];
            InputStream[] streams = new InputStream[count];
            long[] sizes = new long[count];
            for (int i = 0; i < count; i++) {
                Uri uri = uris.get(i);
                names[i] = Ferry.displayName(this, uri);
                sizes[i] = Ferry.size(this, uri);
                try {
                    if (sizes[i] <= Ferry.MAX_FILE_BYTES) {
                        try {
                            staged[i] = Ferry.stage(this, uri);
                            sizes[i] = staged[i].length();
                            continue;
                        } catch (Ferry.TooLargeException e) {
                            // larger than the app said: read it once over Wi-Fi
                        }
                    }
                    streams[i] = getContentResolver().openInputStream(uri);
                    if (streams[i] == null) throw new IOException("Cannot open " + uri);
                } catch (IOException | SecurityException e) {
                    Log.w("Ferry", "send screen: file unreadable: " + e);
                    failed = "Ferry could not read " + names[i] + ".";
                }
            }
            // One send at a time, also across two shares: the laptop puts parts together in the order they come.
            synchronized (SENDING) {
                for (int i = 0; i < count; i++) {
                    if (staged[i] == null && streams[i] == null) continue;
                    String label = count == 1 ? names[i] : names[i] + " (" + (i + 1) + " of " + count + ")";
                    String error = sendFile(names[i], staged[i], streams[i], sizes[i], label);
                    if (error == null) sent++;
                    else failed = error;
                }
            }
            getSystemService(NotificationManager.class).cancel(PROGRESS);
            String r = failed == null ? (uris.size() == 1 ? "Sent to laptop: " + first : "Sent " + sent + " files to laptop")
                    : uris.size() == 1 ? failed : sent + " of " + uris.size() + " files sent. " + failed;
            runOnUiThread(() -> done(r));
        }).start();
    }

    private static final Object SENDING = new Object();

    /**
     * Sends one file: straight over Wi-Fi when the laptop can connect, otherwise through ntfy.sh (a staged copy only).
     * Deletes the copy and closes the stream. Returns null when it is sent, or the reason when it is not.
     */
    private String sendFile(String name, File staged, InputStream stream, long size, String label) {
        IntConsumer bar = Ferry.throttled(percent -> Ferry.progress(this, PROGRESS, "Sending " + label, size, percent));
        try {
            bar.accept(0);
            boolean direct;
            try (InputStream in = staged != null ? new FileInputStream(staged) : stream) {
                direct = Lan.offer(this, name, in, size, bar);
            } catch (IOException e) {
                Log.w("Ferry", "send screen: Wi-Fi transfer broke off: " + e);
                if (staged == null) return name + " not sent. The Wi-Fi connection to the laptop broke off. Try again.";
                direct = false;
            }
            if (!direct) {
                if (staged == null) return name + " is over 90 MB. Ferry sends files that large only when the phone and the laptop are on the same Wi-Fi.";
                Ferry.sendFile(this, name, staged, "phone", bar);
            }
            Ferry.crossedFile(this, Ferry.TO_LAPTOP, name, System.currentTimeMillis(), "");
            Log.i("Ferry", "send screen: file sent " + (direct ? "over Wi-Fi" : "through ntfy.sh"));
            return null;
        } catch (Ferry.LimitException e) {
            return "ntfy.sh refused " + name + ". It keeps at most 100 MB of your files at a time, for 3 hours. Try again later.";
        } catch (IOException e) {
            Log.w("Ferry", "send screen: file not sent: " + e);
            return name + " not sent. Ferry could not reach ntfy.sh. Try again when you are online.";
        } finally {
            //noinspection ResultOfMethodCallIgnored
            if (staged != null) staged.delete();
        }
    }

    private void done(String message) {
        started = true;
        if (message != null) Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
        finish();
    }
}
