package app.ferry;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Opt-in automatic sending. Android blocks background clipboard reads, so this service watches for
 * signs of a copy: a tap on a "Copy" button, or a "Copied" message (toast) or clipboard pop-up that
 * apps and the system show afterwards. It reads no screen content. Then it opens the invisible
 * SendActivity, which reads the clipboard with focus and sends it.
 */
public class AutoSendService extends AccessibilityService {
    static final String TAG = "Ferry";
    private final Handler main = new Handler(Looper.getMainLooper());
    private String copyLabel = "Copy";
    private long lastLaunch;

    @Override
    protected void onServiceConnected() {
        copyLabel = Resources.getSystem().getString(android.R.string.copy);   // "Copy" in the phone's language
        Log.i(TAG, "automatic sending connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        CharSequence app = e.getPackageName();
        if (app == null || getPackageName().contentEquals(app) || !Ferry.paired(this)) return;
        String why = copySignal(e);
        // Diagnostics for "adb logcat -s Ferry": the app and the kind of event, never the copied text.
        // Window changes are only logged when they match, so the log does not list every app you open.
        if (why != null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Log.d(TAG, "event " + AccessibilityEvent.eventTypeToString(e.getEventType()) + " in " + app
                    + " class=" + e.getClassName() + " -> " + (why == null ? "ignored" : why));
        }
        if (why == null) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastLaunch < 1500) return;   // one copy often gives both a tap and a "Copied" toast
        lastLaunch = now;
        // Give the app a moment to put the text on the clipboard first.
        main.postDelayed(() -> {
            try {
                startActivity(new Intent(this, SendActivity.class)
                        .putExtra(SendActivity.EXTRA_AUTO, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
                Log.i(TAG, "opened the send screen");
            } catch (RuntimeException ex) {
                Log.w(TAG, "could not open the send screen: " + ex);
            }
        }, 300);
    }

    /** Returns why this event means "something was just copied", or null. */
    private String copySignal(AccessibilityEvent e) {
        List<CharSequence> labels = new ArrayList<>(e.getText());
        if (e.getContentDescription() != null) labels.add(e.getContentDescription());
        switch (e.getEventType()) {
            case AccessibilityEvent.TYPE_VIEW_CLICKED:
                for (CharSequence l : labels) {
                    String s = l.toString().trim();
                    if (s.equalsIgnoreCase(copyLabel) || s.equalsIgnoreCase("Copy") || s.equalsIgnoreCase("Copy text")
                            || s.equalsIgnoreCase("Copy link")) return "tap on Copy";
                }
                return null;
            case AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED:   // toasts such as "Copied" or "Copied to clipboard"
                for (CharSequence l : labels) {
                    if (l.toString().toLowerCase(Locale.ROOT).contains("copied")) return "Copied message";
                }
                return null;
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:   // Android 13+ clipboard pop-up in the corner
                CharSequence cls = e.getClassName();
                return cls != null && cls.toString().toLowerCase(Locale.ROOT).contains("clipboard") ? "clipboard pop-up" : null;
            default:
                return null;
        }
    }

    @Override
    public void onInterrupt() {
    }
}
