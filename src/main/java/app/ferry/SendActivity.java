package app.ferry;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;

/**
 * Invisible screen that sends to the laptop: shared or selected text or images if there are some,
 * otherwise the clipboard. Android only lets an app read the clipboard while it has focus,
 * which is why this has to be an activity and not a background job.
 */
public class SendActivity extends Activity {
    /** Set by AutoSendService after a copy. Stays quiet when there is nothing new to send. */
    static final String EXTRA_AUTO = "auto";
    private boolean started, auto;

    @Override
    @SuppressWarnings("deprecation")   // the typed getParcelableExtra needs API 33; Ferry runs from API 29
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        auto = getIntent().getBooleanExtra(EXTRA_AUTO, false);
        String type = getIntent().getType();
        Object stream = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
        if (type != null && type.startsWith("image/") && stream instanceof Uri) {
            sendImage((Uri) stream);
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
                result = "Not sent. Ferry could not reach ntfy.sh. Try again when you are online.";
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

    private void done(String message) {
        started = true;
        if (message != null) Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
        finish();
    }
}
