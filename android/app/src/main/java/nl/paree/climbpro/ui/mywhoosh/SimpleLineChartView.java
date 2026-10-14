package nl.paree.climbpro.ui.mywhoosh;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Small line chart for the MyWhoosh screens (issues #387, #405): one or two series over the
 * same x positions, each scaled to its own range (left and right labels), with optional
 * highlighted points (PRs) and an optional zero line. Missing values (null) break the line.
 */
public final class SimpleLineChartView extends View {

    /** One line; values may contain nulls. */
    public static final class Series {
        final List<Double> values;
        final int color;
        final String label;
        final boolean invertY;
        final java.util.Set<Integer> highlighted;
        final ValueFormat format;

        /**
         * @param invertY draw lower values higher (a faster time is "up")
         */
        public Series(List<Double> values, int color, String label, boolean invertY,
                      java.util.Set<Integer> highlighted, ValueFormat format) {
            this.values = values;
            this.color = color;
            this.label = label;
            this.invertY = invertY;
            this.highlighted = highlighted;
            this.format = format;
        }
    }

    public interface ValueFormat {
        String format(double value);
    }

    private final List<Series> series = new ArrayList<>();
    private boolean zeroLine;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    /** Plot area, recomputed on every draw (kept as a field to avoid allocating in onDraw). */
    private final RectF area = new RectF();

    public SimpleLineChartView(Context context) {
        this(context, null);
    }

    public SimpleLineChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2.5f * density);
        dot.setStyle(Paint.Style.FILL);
        text.setTextSize(11 * density);
        text.setColor(Color.LTGRAY);
        grid.setColor(0x44FFFFFF);
        grid.setStrokeWidth(density);
    }

    /** Replaces the series (at most two are labelled: left and right). */
    public void setSeries(List<Series> newSeries, boolean drawZeroLine) {
        series.clear();
        if (newSeries != null) series.addAll(newSeries);
        zeroLine = drawZeroLine;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = (int) (180 * density);
        setMeasuredDimension(w, resolveSize(h, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        area.set(8 * density, 18 * density, getWidth() - 8 * density,
                getHeight() - 18 * density);
        canvas.drawLine(area.left, area.bottom, area.right, area.bottom, grid);
        canvas.drawLine(area.left, area.top, area.right, area.top, grid);
        for (int si = 0; si < series.size(); si++) drawSeries(canvas, si, series.get(si));
    }

    private void drawSeries(Canvas canvas, int si, Series s) {
        double[] range = valueRange(s);
        if (range.length == 0) return;
        double min = range[0];
        double max = range[1];
        line.setColor(s.color);
        dot.setColor(s.color);
        canvas.drawPath(plot(canvas, s, min, max), line);
        if (zeroLine && si == 0) {
            float zy = y(0, min, max, area.top, area.bottom, s.invertY);
            canvas.drawLine(area.left, zy, area.right, zy, grid);
        }
        if (si < 2 && s.format != null) drawRangeLabels(canvas, si, s, min, max);
    }

    /** {min, max} of the series (with the zero line and a minimum span); empty without values. */
    private double[] valueRange(Series s) {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (Double v : s.values) {
            if (v == null) continue;
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        if (min == Double.MAX_VALUE) return new double[0];
        if (zeroLine) {
            min = Math.min(min, 0);
            max = Math.max(max, 0);
        }
        if (max - min < 1e-9) {
            max += 1;
            min -= 1;
        }
        return new double[]{min, max};
    }

    /** Draws the dots (big ones highlighted) and returns the line, broken at missing values. */
    private Path plot(Canvas canvas, Series s, double min, double max) {
        int n = s.values.size();
        Path path = new Path();
        boolean pen = false;
        for (int i = 0; i < n; i++) {
            Double v = s.values.get(i);
            if (v == null) {
                pen = false;
                continue;
            }
            float x = n == 1 ? (area.left + area.right) / 2
                    : area.left + (area.right - area.left) * i / (n - 1f);
            float y = y(v, min, max, area.top, area.bottom, s.invertY);
            if (pen) path.lineTo(x, y); else path.moveTo(x, y);
            pen = true;
            boolean hi = s.highlighted != null && s.highlighted.contains(Integer.valueOf(i));
            canvas.drawCircle(x, y, (hi ? 6 : 3) * density, dot);
        }
        return path;
    }

    /** Top and bottom value labels: the first series on the left, the second on the right. */
    private void drawRangeLabels(Canvas canvas, int si, Series s, double min, double max) {
        text.setColor(s.color);
        String hiLabel = s.format.format(s.invertY ? min : max);
        String loLabel = s.format.format(s.invertY ? max : min);
        String name = s.label != null ? s.label + "  " : "";
        float x = si == 0 ? area.left : area.right;
        text.setTextAlign(si == 0 ? Paint.Align.LEFT : Paint.Align.RIGHT);
        canvas.drawText(name + hiLabel, x, area.top - 5 * density, text);
        canvas.drawText(loLabel, x, area.bottom + 13 * density, text);
    }

    private static float y(double v, double min, double max, float top, float bottom,
                           boolean invert) {
        double f = (v - min) / (max - min);
        if (invert) f = 1 - f;
        return (float) (bottom - f * (bottom - top));
    }
}
