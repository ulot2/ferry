package app.ferry;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Opt-in automatic sending. Android blocks background clipboard reads, so this service watches
 * only for taps on a "Copy" button (event text, no screen content), then opens the invisible
 * SendActivity, which reads the clipboard with focus and sends it.
 */
public class AutoSendService extends AccessibilityService {
    private final Handler main = new Handler(Looper.getMainLooper());
    private String copyLabel = "Copy";
    private long lastLaunch;

    @Override
    protected void onServiceConnected() {
        copyLabel = Resources.getSystem().getString(android.R.string.copy);   // "Copy" in the phone's language
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        CharSequence app = e.getPackageName();
        if (e.getEventType() != AccessibilityEvent.TYPE_VIEW_CLICKED || app == null || getPackageName().contentEquals(app)) return;
        if (!Ferry.paired(this) || !isCopy(e)) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastLaunch < 1000) return;
        lastLaunch = now;
        // Give the app a moment to put the text on the clipboard first.
        main.postDelayed(() -> startActivity(new Intent(this, SendActivity.class)
                .putExtra(SendActivity.EXTRA_AUTO, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION)), 250);
    }

    private boolean isCopy(AccessibilityEvent e) {
        List<CharSequence> labels = new ArrayList<>(e.getText());
        if (e.getContentDescription() != null) labels.add(e.getContentDescription());
        for (CharSequence l : labels) {
            String s = l.toString().trim();
            if (s.equalsIgnoreCase(copyLabel) || s.equalsIgnoreCase("Copy") || s.equalsIgnoreCase("Copy text") || s.equalsIgnoreCase("Copy link")) return true;
        }
        return false;
    }

    @Override
    public void onInterrupt() {
    }
}
