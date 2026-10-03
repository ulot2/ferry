package app.ferry;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Opt-in automatic sending. Android blocks background clipboard reads, so this service watches for
 * signs of a copy: a tap on a "Copy" button, or a "Copied" message (toast or screen-reader announcement) or clipboard pop-up that
 * apps and the system show afterwards. On a tap it reads only the labels inside the tapped element. Then it opens the invisible
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
        int type = e.getEventType();
        if (why != null || type == AccessibilityEvent.TYPE_VIEW_CLICKED || type == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
                || type == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED || type == AccessibilityEvent.TYPE_ANNOUNCEMENT) {
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
                for (CharSequence l : labels) if (isCopyLabel(l)) return "tap on Copy";
                // Most apps put the word in a child view (an icon plus a text below it), so the tap itself carries
                // no label. Look only inside the tapped element, a few levels deep; nothing else on screen.
                AccessibilityNodeInfo tapped = e.getSource();
                return tapped != null && hasCopyLabel(tapped, 3) ? "tap on Copy (label inside)" : null;
            case AccessibilityEvent.TYPE_ANNOUNCEMENT:   // "Copied" read out for screen readers (TikTok's Copy link has no label, only this)
            case AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED:   // toasts such as "Copied" or "Copied to clipboard"
                for (CharSequence l : labels) {
                    if (l.toString().toLowerCase(Locale.ROOT).contains("copied")) return "Copied message";
                }
                return null;
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:   // Android 13+ clipboard pop-up in the corner
                CharSequence cls = e.getClassName();
                if (cls != null && cls.toString().toLowerCase(Locale.ROOT).contains("clipboard")) return "clipboard pop-up";
                // Diagnostics: name the system's own windows, to learn what this phone shows after a copy.
                if ("com.android.systemui".contentEquals(e.getPackageName())) Log.d(TAG, "system window " + cls);
                return null;
            default:
                return null;
        }
    }

    /** "Copy", "Copy link", "Copy text", "Copy message"… in the phone's language or English. Not "Copyright". */
    private boolean isCopyLabel(CharSequence label) {
        if (label == null) return false;
        String s = label.toString().trim().toLowerCase(Locale.ROOT);
        String copy = copyLabel.toLowerCase(Locale.ROOT);
        return s.equals(copy) || s.equals("copy") || ((s.startsWith(copy + " ") || s.startsWith("copy ")) && s.length() <= 24);
    }

    private boolean hasCopyLabel(AccessibilityNodeInfo node, int depth) {
        if (isCopyLabel(node.getText()) || isCopyLabel(node.getContentDescription())) return true;
        if (depth == 0) return false;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null && hasCopyLabel(child, depth - 1)) return true;
        }
        return false;
    }

    @Override
    public void onInterrupt() {
    }
}
