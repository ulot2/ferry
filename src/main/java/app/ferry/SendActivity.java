package app.ferry;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;

/**
 * Invisible screen that sends text to the laptop: shared or selected text if there is some,
 * otherwise the clipboard. Android only lets an app read the clipboard while it has focus,
 * which is why this has to be an activity and not a background job.
 */
public class SendActivity extends Activity {
    /** Set by AutoSendService after a tap on Copy. Stays quiet when there is nothing new to send. */
    static final String EXTRA_AUTO = "auto";
    private boolean started, auto;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        auto = getIntent().getBooleanExtra(EXTRA_AUTO, false);
        CharSequence text = getIntent().getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
        if (text == null) text = getIntent().getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (text != null) send(text.toString());
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus || started) return;
        ClipData clip = getSystemService(ClipboardManager.class).getPrimaryClip();
        CharSequence text = clip == null || clip.getItemCount() == 0 ? null : clip.getItemAt(0).coerceToText(this);
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

    private void done(String message) {
        started = true;
        if (message != null) Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show();
        finish();
    }
}
