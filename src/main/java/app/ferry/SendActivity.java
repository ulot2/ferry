package app.ferry;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import java.io.IOException;

/**
 * Invisible screen that sends text to the laptop: shared or selected text if there is some,
 * otherwise the clipboard. Android only lets an app read the clipboard while it has focus,
 * which is why this has to be an activity and not a background job.
 */
public class SendActivity extends Activity {
    private boolean started;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
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
            done("Clipboard is empty");
            return;
        }
        send(text.toString());
    }

    private void send(String text) {
        started = true;
        String topic = Ferry.topic(this);
        if (topic.isEmpty()) {
            done("Open Ferry and set your topic first");
            return;
        }
        new Thread(() -> {
            String result;
            try {
                Ferry.send(topic, text, "phone");
                Ferry.crossed(this, "Phone → Laptop", text);
                result = "Sent to laptop";
            } catch (IOException e) {
                result = "Not sent. Ferry could not reach ntfy.sh. Try again when you are online.";
            }
            String r = result;
            runOnUiThread(() -> done(r));
        }).start();
    }

    private void done(String message) {
        started = true;
        Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show();
        finish();
    }
}
