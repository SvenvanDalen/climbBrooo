package nl.paree.climbpro.ui.mywhoosh;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
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
        float left = 8 * density;
        float right = getWidth() - 8 * density;
        float top = 18 * density;
        float bottom = getHeight() - 18 * density;
        canvas.drawLine(left, bottom, right, bottom, grid);
        canvas.drawLine(left, top, right, top, grid);
        for (int si = 0; si < series.size(); si++) {
            Series s = series.get(si);
            int n = s.values.size();
            if (n == 0) continue;
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (Double v : s.values) {
                if (v == null) continue;
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
            if (min == Double.MAX_VALUE) continue;
            if (zeroLine) {
                min = Math.min(min, 0);
                max = Math.max(max, 0);
            }
            if (max - min < 1e-9) {
                max += 1;
                min -= 1;
            }
            line.setColor(s.color);
            dot.setColor(s.color);
            Path path = new Path();
            boolean pen = false;
            for (int i = 0; i < n; i++) {
                Double v = s.values.get(i);
                if (v == null) {
                    pen = false;
                    continue;
                }
                float x = n == 1 ? (left + right) / 2 : left + (right - left) * i / (n - 1f);
                float y = y(v, min, max, top, bottom, s.invertY);
                if (pen) path.lineTo(x, y); else path.moveTo(x, y);
                pen = true;
                boolean hi = s.highlighted != null && s.highlighted.contains(i);
                canvas.drawCircle(x, y, (hi ? 6 : 3) * density, dot);
            }
            canvas.drawPath(path, line);
            if (zeroLine && si == 0) {
                float zy = y(0, min, max, top, bottom, s.invertY);
                canvas.drawLine(left, zy, right, zy, grid);
            }
            if (si < 2 && s.format != null) {
                text.setColor(s.color);
                String hiLabel = s.format.format(s.invertY ? min : max);
                String loLabel = s.format.format(s.invertY ? max : min);
                String name = s.label != null ? s.label + "  " : "";
                if (si == 0) {
                    text.setTextAlign(Paint.Align.LEFT);
                    canvas.drawText(name + hiLabel, left, top - 5 * density, text);
                    canvas.drawText(loLabel, left, bottom + 13 * density, text);
                } else {
                    text.setTextAlign(Paint.Align.RIGHT);
                    canvas.drawText(name + hiLabel, right, top - 5 * density, text);
                    canvas.drawText(loLabel, right, bottom + 13 * density, text);
                }
            }
        }
    }

    private static float y(double v, double min, double max, float top, float bottom,
                           boolean invert) {
        double f = (v - min) / (max - min);
        if (invert) f = 1 - f;
        return (float) (bottom - f * (bottom - top));
    }
}
