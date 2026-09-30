package nl.paree.climbpro.ui.climbs;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import nl.paree.climbpro.domain.climb.RideFatigueCurveCalculator.FatiguePoint;

/**
 * Post-ride fatigue-curve chart (issue #21): one bar per climb in ride order, bar height
 * proportional to that climb's actual VAM, plus a trend line across the bar tops so a
 * "getting slower" ride reads as a visibly descending line. Hand-rolled {@link Canvas}
 * drawing, mirroring {@link ClimbProfileView}'s approach rather than adding a charting
 * dependency — kept simple on purpose (bars + one trend line, no axes beyond a baseline).
 */
public final class RideFatigueChartView extends View {

    // Fatigue color scale by pace relative to the ride's first climb (100% = same pace).
    // Reuses the same visual grammar (yellow -> red = worse) as SegmentColorPalette without
    // reusing its gradient-specific thresholds, since this measures a different quantity.
    private static final int COLOR_STRONG   = Color.parseColor("#4CAF50"); // >= 100%
    private static final int COLOR_MILD     = Color.parseColor("#FFC107"); // 90-100%
    private static final int COLOR_TIRED    = Color.parseColor("#FF9800"); // 75-90%
    private static final int COLOR_FADING   = Color.parseColor("#F44336"); // < 75%

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trendPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path trendPath = new Path();

    private List<FatiguePoint> points = new ArrayList<>();
    private double maxVam = 0;

    private static final float PAD_LEFT = 8f;
    private static final float PAD_RIGHT = 8f;
    private static final float PAD_TOP = 16f;
    private static final float PAD_BOTTOM = 32f;
    private static final float BAR_GAP_FRACTION = 0.25f;

    public RideFatigueChartView(Context context) {
        super(context);
        init();
    }

    public RideFatigueChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public RideFatigueChartView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        barPaint.setStyle(Paint.Style.FILL);

        trendPaint.setStyle(Paint.Style.STROKE);
        trendPaint.setColor(Color.parseColor("#212121"));
        trendPaint.setStrokeWidth(3f);
        trendPaint.setPathEffect(new DashPathEffect(new float[]{10f, 8f}, 0f));

        axisPaint.setColor(Color.parseColor("#2A2F35"));
        axisPaint.setStrokeWidth(1f);
        axisPaint.setStyle(Paint.Style.STROKE);

        labelPaint.setColor(Color.parseColor("#9299A1"));
        labelPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setPoints(List<FatiguePoint> points) {
        this.points = points != null ? points : new ArrayList<>();
        maxVam = 0;
        for (FatiguePoint p : this.points) {
            if (p.vamMPerHour > maxVam) maxVam = p.vamMPerHour;
        }
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = (int) (width * 0.5f);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        if (heightMode == MeasureSpec.EXACTLY) {
            height = MeasureSpec.getSize(heightMeasureSpec);
        } else if (heightMode == MeasureSpec.AT_MOST) {
            height = Math.min(height, MeasureSpec.getSize(heightMeasureSpec));
        }
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (points.isEmpty() || maxVam <= 0) return;

        float density = getResources().getDisplayMetrics().density;
        float padLeft = PAD_LEFT * density;
        float padRight = PAD_RIGHT * density;
        float padTop = PAD_TOP * density;
        float padBottom = PAD_BOTTOM * density;

        float chartWidth = getWidth() - padLeft - padRight;
        float chartHeight = getHeight() - padTop - padBottom;
        float baseline = getHeight() - padBottom;

        canvas.drawLine(padLeft, baseline, padLeft + chartWidth, baseline, axisPaint);

        int n = points.size();
        float slotWidth = chartWidth / n;
        float barWidth = slotWidth * (1f - BAR_GAP_FRACTION);

        trendPath.reset();
        labelPaint.setTextSize(11f * density);

        for (int i = 0; i < n; i++) {
            FatiguePoint p = points.get(i);
            float x1 = padLeft + i * slotWidth + (slotWidth - barWidth) / 2f;
            float x2 = x1 + barWidth;
            float barHeight = (float) (p.vamMPerHour / maxVam) * chartHeight;
            float yTop = baseline - barHeight;

            barPaint.setColor(colorFor(p.relativeToFirstPct));
            canvas.drawRect(x1, yTop, x2, baseline, barPaint);

            float midX = (x1 + x2) / 2f;
            if (i == 0) {
                trendPath.moveTo(midX, yTop);
            } else {
                trendPath.lineTo(midX, yTop);
            }

            labelPaint.setColor(Color.parseColor("#9299A1"));
            canvas.drawText(String.valueOf(p.ordinal), midX, baseline + 18f * density, labelPaint);
        }
        canvas.drawPath(trendPath, trendPaint);
    }

    private static int colorFor(double relativeToFirstPct) {
        if (relativeToFirstPct >= 100) return COLOR_STRONG;
        if (relativeToFirstPct >= 90) return COLOR_MILD;
        if (relativeToFirstPct >= 75) return COLOR_TIRED;
        return COLOR_FADING;
    }
}
