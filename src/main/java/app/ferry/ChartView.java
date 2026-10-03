package app.ferry;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

/**
 * The sea chart at the top of the screen: water with depth contours, land at both edges, two harbors
 * (the laptop and this phone) and the dashed course between them, its arrow pointing the way the last
 * crossing went. Colors come from resources, so the day and night charts both work. See DESIGN.md.
 */
public class ChartView extends View {
    private final float d = getResources().getDisplayMetrics().density;
    private final float sp = getResources().getDisplayMetrics().scaledDensity;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint course = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final int water, contour1, contour2, land, ink, courseColor;
    private String left = "Laptop", right = "This phone", courseLabel = "";
    private boolean towardRight = true;   // true: the last crossing went laptop → phone

    public ChartView(Context c, AttributeSet a) {
        super(c, a);
        water = c.getColor(R.color.water);
        contour1 = c.getColor(R.color.contour1);
        contour2 = c.getColor(R.color.contour2);
        land = c.getColor(R.color.land);
        ink = c.getColor(R.color.ink);
        courseColor = c.getColor(R.color.course);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1.2f * d);
        course.setStyle(Paint.Style.STROKE);
        course.setStrokeWidth(2.5f * d);
        course.setStrokeCap(Paint.Cap.ROUND);
        course.setColor(courseColor);
        Typeface italic = c.getResources().getFont(R.font.barlow_italic);
        label.setTypeface(italic);
        label.setTextSize(14 * sp);
    }

    /** Names the harbors and the course, and which way the last crossing went. */
    void set(String leftHarbor, String rightHarbor, String courseText, boolean wentRight) {
        left = leftHarbor;
        right = rightHarbor;
        courseLabel = courseText;
        towardRight = wentRight;
        setContentDescription("Chart: " + leftHarbor + " and " + rightHarbor + (courseText.isEmpty() ? "" : ", " + courseText));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        canvas.drawColor(water);

        // Depth contours: four soft curves across the water.
        for (int i = 0; i < 4; i++) {
            float y = h * (0.22f + i * 0.17f);
            float dy = h * 0.09f;
            path.reset();
            path.moveTo(-10 * d, y);
            path.cubicTo(w * 0.22f, y - dy, w * 0.42f, y + dy, w * 0.66f, y);
            path.cubicTo(w * 0.82f, y - dy * 0.6f, w * 0.95f, y - dy * 0.2f, w + 10 * d, y + dy * 0.4f);
            line.setColor(i < 2 ? contour1 : contour2);
            canvas.drawPath(path, line);
        }

        // Land at both edges.
        fill.setColor(land);
        path.reset();
        path.moveTo(0, 0);
        path.lineTo(w * 0.13f, 0);
        path.cubicTo(w * 0.18f, h * 0.17f, w * 0.10f, h * 0.33f, w * 0.16f, h * 0.5f);
        path.cubicTo(w * 0.23f, h * 0.67f, w * 0.15f, h * 0.83f, w * 0.18f, h);
        path.lineTo(0, h);
        path.close();
        canvas.drawPath(path, fill);
        path.reset();
        path.moveTo(w, 0);
        path.lineTo(w * 0.87f, 0);
        path.cubicTo(w * 0.82f, h * 0.2f, w * 0.90f, h * 0.37f, w * 0.84f, h * 0.53f);
        path.cubicTo(w * 0.77f, h * 0.7f, w * 0.86f, h * 0.84f, w * 0.82f, h);
        path.lineTo(w, h);
        path.close();
        canvas.drawPath(path, fill);

        // The course: a dashed arc between the two harbors.
        float y = h * 0.66f, x1 = w * 0.18f, x2 = w * 0.82f, top = h * 0.40f;
        path.reset();
        path.moveTo(x1, y);
        path.cubicTo(w * 0.36f, top, w * 0.64f, top, x2, y);
        course.setPathEffect(new DashPathEffect(new float[]{7 * d, 6 * d}, 0));
        canvas.drawPath(path, course);
        course.setPathEffect(null);

        // Arrowhead at the top of the arc, pointing the way the crossing went.
        float mx = w * 0.5f, my = y - (y - top) * 0.75f, s = 7 * d, dir = towardRight ? 1 : -1;
        path.reset();
        path.moveTo(mx - dir * s, my - s);
        path.lineTo(mx + dir * s * 0.4f, my);
        path.lineTo(mx - dir * s, my + s);
        canvas.drawPath(path, course);

        // Harbors.
        fill.setColor(ink);
        canvas.drawCircle(x1, y, 7 * d, fill);
        canvas.drawCircle(x2, y, 7 * d, fill);

        // Italic place labels.
        label.setColor(ink);
        label.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(left, 14 * d, y + 28 * d, label);
        label.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(right, w - 14 * d, y + 28 * d, label);
        if (!courseLabel.isEmpty()) {
            label.setColor(courseColor);
            label.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(courseLabel, mx, my - 14 * d, label);
        }
    }
}
