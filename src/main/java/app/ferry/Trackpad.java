package app.ferry;

import android.content.Context;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * The Helm trackpad. One finger moves the pointer and a tap clicks. Two fingers scroll, and a two-finger tap is a
 * right click. Tap, then touch again at once and hold: the left button stays down, to drag.
 */
public class Trackpad extends View {
    interface Sink {
        void move(int dx, int dy);

        /** which: 0 left, 1 right. */
        void button(int which, boolean down);

        /** In wheel units: 120 is one notch. Positive v scrolls up, positive h scrolls right. */
        void scroll(int v, int h);
    }

    private static final int TAP_MS = 250, DOUBLE_TAP_MS = 300;
    /** Wheel units for one dp of finger travel: about 30 dp per notch. */
    private static final float WHEEL_PER_DP = 4f;
    private Sink sink;
    private float speed = 2f;
    private final float dp, slop;
    private float lastX, lastY, travel, restX, restY, restV, restH;
    private long downAt, lastTapAt;
    private int fingers;
    private boolean dragging;

    public Trackpad(Context c, AttributeSet a) {
        super(c, a);
        dp = getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
    }

    void setSink(Sink sink) {
        this.sink = sink;
    }

    /** Pointer moves per dp of finger travel. Windows adds its own pointer acceleration on top. */
    void setSpeed(float speed) {
        this.speed = speed;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (sink == null) return false;
        long now = SystemClock.uptimeMillis();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downAt = now;
                fingers = 1;
                travel = 0;
                restX = restY = restV = restH = 0;
                center(e, -1);
                if (now - lastTapAt < DOUBLE_TAP_MS) {
                    dragging = true;
                    sink.button(0, true);
                }
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                fingers = Math.max(fingers, e.getPointerCount());
                center(e, -1);   // the center of the fingers jumps: start from the new one
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                center(e, e.getActionIndex());
                return true;
            case MotionEvent.ACTION_MOVE: {
                float x = lastX, y = lastY;
                center(e, -1);
                float dx = (lastX - x) / dp, dy = (lastY - y) / dp;
                travel += Math.abs(dx) + Math.abs(dy);
                if (travel * dp < slop) return true;   // not a move yet: it can still be a tap
                if (e.getPointerCount() == 1 && fingers == 1) {
                    restX += dx * speed;
                    restY += dy * speed;
                    int mx = (int) restX, my = (int) restY;
                    restX -= mx;
                    restY -= my;
                    if (mx != 0 || my != 0) sink.move(mx, my);
                } else if (e.getPointerCount() >= 2) {
                    // The page follows the fingers, as on a phone: fingers down shows what is above.
                    restV += dy * WHEEL_PER_DP;
                    restH -= dx * WHEEL_PER_DP;
                    int v = (int) restV, h = (int) restH;
                    restV -= v;
                    restH -= h;
                    if (v != 0 || h != 0) sink.scroll(v, h);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    dragging = false;
                    sink.button(0, false);
                } else if (travel * dp < slop && now - downAt < TAP_MS) {
                    int which = fingers >= 2 ? 1 : 0;
                    sink.button(which, true);
                    sink.button(which, false);
                    if (which == 0) {
                        lastTapAt = now;
                        performClick();
                    }
                    return true;
                }
                lastTapAt = 0;
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (dragging) sink.button(0, false);
                dragging = false;
                lastTapAt = 0;
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    /** The center of the fingers on the pad, without the one at index skip (-1: all of them). */
    private void center(MotionEvent e, int skip) {
        float x = 0, y = 0;
        int n = 0;
        for (int i = 0; i < e.getPointerCount(); i++) {
            if (i == skip) continue;
            x += e.getX(i);
            y += e.getY(i);
            n++;
        }
        if (n == 0) return;
        lastX = x / n;
        lastY = y / n;
    }
}
