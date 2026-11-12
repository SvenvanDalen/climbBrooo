package nl.paree.climbpro.ui.mywhoosh;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import nl.paree.climbpro.domain.mywhoosh.RideProfile;

/**
 * Elevation profile as a filled area with power, heart rate and cadence drawn over it, each on
 * its own scale (issue #390). The view is as wide as the ride needs ({@link #DP_PER_BIN} per
 * 100 m bin) and sits in a HorizontalScrollView, so all lines scroll together.
 */
public final class RideProfileChartView extends View {

    public static final int POWER = 0;
    public static final int HEART_RATE = 1;
    public static final int CADENCE = 2;

    static final float DP_PER_BIN = 3f;
    static final int[] COLORS = {0xFFFFC107, 0xFFE53935, 0xFF29B6F6};

    private RideProfile profile;
    private final boolean[] visible = {true, true, true};
    private final float density;
    private final Paint area = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    public RideProfileChartView(Context context) {
        this(context, null);
    }

    public RideProfileChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        area.setColor(0x66B0BEC5);
        area.setStyle(Paint.Style.FILL);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1.8f * density);
        text.setColor(Color.LTGRAY);
        text.setTextSize(11 * density);
    }

    public void setProfile(RideProfile profile) {
        this.profile = profile;
        requestLayout();
        invalidate();
    }

    public void setVisible(int series, boolean on) {
        visible[series] = on;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int min = MeasureSpec.getSize(widthMeasureSpec);
        int w = profile != null ? Math.max(min, (int) (profile.size() * DP_PER_BIN * density)) : min;
        setMeasuredDimension(w, resolveSize((int) (240 * density), heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (profile == null || profile.size() < 2) return;
        float top = 8 * density;
        float bottom = getHeight() - 18 * density;
        drawArea(canvas, profile.altitude, top, bottom);
        double[][] series = {profile.watts, profile.heartrate, profile.cadence};
        for (int k = 0; k < series.length; k++) {
            if (!visible[k]) continue;
            line.setColor(COLORS[k]);
            drawLine(canvas, series[k], top, bottom);
        }
        drawKmTicks(canvas, bottom);
    }

    private float x(int bin) {
        return (bin + 0.5f) * getWidth() / profile.size();
    }

    private static double[] range(double[] v) {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (double d : v) {
            if (Double.isNaN(d)) continue;
            min = Math.min(min, d);
            max = Math.max(max, d);
        }
        if (min == Double.MAX_VALUE) return null;
        if (max - min < 1) max = min + 1;
        return new double[]{min, max};
    }

    private static float y(double v, double[] r, float top, float bottom) {
        return (float) (bottom - (v - r[0]) / (r[1] - r[0]) * (bottom - top));
    }

    private void drawArea(Canvas canvas, double[] alt, float top, float bottom) {
        double[] r = range(alt);
        if (r == null) return;
        // Leave the top third for the lines, so the profile reads as a backdrop.
        float areaTop = top + (bottom - top) / 3;
        Path p = new Path();
        p.moveTo(x(0), bottom);
        double last = r[0];
        for (int b = 0; b < alt.length; b++) {
            if (!Double.isNaN(alt[b])) last = alt[b];
            p.lineTo(x(b), y(last, r, areaTop, bottom));
        }
        p.lineTo(x(alt.length - 1), bottom);
        p.close();
        canvas.drawPath(p, area);
    }

    private void drawLine(Canvas canvas, double[] v, float top, float bottom) {
        double[] r = range(v);
        if (r == null) return;
        Path p = new Path();
        boolean pen = false;
        for (int b = 0; b < v.length; b++) {
            if (Double.isNaN(v[b])) {
                pen = false;
                continue;
            }
            float px = x(b);
            float py = y(v[b], r, top, bottom);
            if (pen) {
                p.lineTo(px, py);
            } else {
                p.moveTo(px, py);
                pen = true;
            }
        }
        canvas.drawPath(p, line);
    }

    private void drawKmTicks(Canvas canvas, float bottom) {
        int binsPerKm = (int) Math.round(1000 / profile.binM);
        int stepKm = profile.size() / binsPerKm > 60 ? 10 : 5;
        for (int km = stepKm; km * binsPerKm < profile.size(); km += stepKm) {
            float px = x(km * binsPerKm);
            canvas.drawText(km + " km", px, bottom + 14 * density, text);
        }
    }
}
