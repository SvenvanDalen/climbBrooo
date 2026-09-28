package nl.paree.climbpro.ui.records;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import nl.paree.climbpro.R;

/**
 * Power-curve chart (issue #219): the chosen period's best watts per duration as a solid line,
 * with the all-time curve dashed behind it. The durations are roughly log-spaced, so they are
 * drawn at even steps. Hand-rolled {@link Canvas} drawing like the other charts in the app.
 */
public final class PowerCurveChartView extends View {

    private static final float PAD_SIDE = 28f;
    private static final float PAD_TOP = 16f;
    private static final float PAD_BOTTOM = 28f;

    private final Paint periodPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint allTimePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private int[] period = new int[0];
    private int[] allTime = new int[0];
    private String[] labels = new String[0];

    public PowerCurveChartView(Context context) {
        super(context);
        init();
    }

    public PowerCurveChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        periodPaint.setStyle(Paint.Style.STROKE);
        periodPaint.setStrokeWidth(3f * density);
        periodPaint.setColor(ContextCompat.getColor(getContext(), R.color.color_accent));

        allTimePaint.setStyle(Paint.Style.STROKE);
        allTimePaint.setStrokeWidth(2f * density);
        allTimePaint.setColor(ContextCompat.getColor(getContext(), R.color.color_text_disabled));
        allTimePaint.setPathEffect(new DashPathEffect(new float[]{8f * density, 6f * density}, 0f));

        axisPaint.setStyle(Paint.Style.STROKE);
        axisPaint.setStrokeWidth(1f);
        axisPaint.setColor(ContextCompat.getColor(getContext(), R.color.color_surface_raised));

        labelPaint.setColor(ContextCompat.getColor(getContext(), R.color.color_text_tertiary));
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextSize(11f * density);
    }

    /** Watts per duration (0 = none) for the period and all time, and the x-axis labels. */
    public void setCurves(int[] period, int[] allTime, String[] labels) {
        this.period = period != null ? period : new int[0];
        this.allTime = allTime != null ? allTime : new int[0];
        this.labels = labels != null ? labels : new String[0];
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(width, resolveSize((int) (width * 0.5f), heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int n = labels.length;
        if (n < 2) return;
        int max = 0;
        for (int w : period) max = Math.max(max, w);
        for (int w : allTime) max = Math.max(max, w);
        if (max <= 0) return;

        float density = getResources().getDisplayMetrics().density;
        float left = PAD_SIDE * density;
        float right = getWidth() - PAD_SIDE * density;
        float top = PAD_TOP * density;
        float baseline = getHeight() - PAD_BOTTOM * density;
        float step = (right - left) / (n - 1);
        float scale = (baseline - top) / (max * 1.1f);

        canvas.drawLine(left, baseline, right, baseline, axisPaint);
        for (int i = 0; i < n; i++) {
            canvas.drawText(labels[i], left + i * step, baseline + 18f * density, labelPaint);
        }
        drawCurve(canvas, allTime, left, step, baseline, scale, allTimePaint);
        drawCurve(canvas, period, left, step, baseline, scale, periodPaint);
    }

    /** Joins the points that have a value; a 0 (no ride that long) ends the line. */
    private void drawCurve(Canvas canvas, int[] watts, float left, float step, float baseline,
                           float scale, Paint paint) {
        path.reset();
        boolean started = false;
        for (int i = 0; i < watts.length && i < labels.length; i++) {
            if (watts[i] <= 0) break;
            float x = left + i * step;
            float y = baseline - watts[i] * scale;
            if (started) {
                path.lineTo(x, y);
            } else {
                path.moveTo(x, y);
                started = true;
            }
            canvas.drawCircle(x, y, paint.getStrokeWidth() * 1.2f, paint);
        }
        canvas.drawPath(path, paint);
    }
}
