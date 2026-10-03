package app.ferry;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.widget.LinearLayout;

/**
 * The crossing ticket: a card with a perforated, signal-yellow stub on the right.
 * The second child is the stub; the perforation runs along its left edge.
 */
public class TicketView extends LinearLayout {
    private final Paint surface = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint signal = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint holes = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();
    private final Path notches = new Path();
    private final float d = getResources().getDisplayMetrics().density;

    public TicketView(Context c, AttributeSet a) {
        super(c, a);
        setWillNotDraw(false);
        surface.setColor(c.getColor(R.color.surface));
        signal.setColor(c.getColor(R.color.signal));
        holes.setColor(c.getColor(R.color.ground));
        holes.setStyle(Paint.Style.STROKE);
        holes.setStrokeWidth(2 * d);
        holes.setPathEffect(new DashPathEffect(new float[]{4 * d, 5 * d}, 0));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        float x = getChildCount() > 1 ? getChildAt(1).getLeft() : w;
        float r = 16 * d, notch = 10 * d;

        shape.reset();
        shape.addRoundRect(0, 0, w, h, r, r, Path.Direction.CW);
        notches.reset();
        notches.addCircle(x, 0, notch, Path.Direction.CW);
        notches.addCircle(x, h, notch, Path.Direction.CW);
        shape.op(notches, Path.Op.DIFFERENCE);

        canvas.drawPath(shape, surface);
        canvas.save();
        canvas.clipPath(shape);
        canvas.drawRect(x, 0, w, h, signal);
        canvas.restore();
        canvas.drawLine(x, notch + 4 * d, x, h - notch - 4 * d, holes);
    }
}
