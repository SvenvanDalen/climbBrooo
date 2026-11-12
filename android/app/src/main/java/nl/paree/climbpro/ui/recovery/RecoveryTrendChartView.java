package nl.paree.climbpro.ui.recovery;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import nl.paree.climbpro.domain.recovery.RecoveryTrendAnalyzer;
import nl.paree.climbpro.domain.recovery.RecoveryTrendAnalyzer.Point;
import nl.paree.climbpro.ui.ChartArea;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * RPE (1–10) and sleep quality (1–5, drawn ×2 on the same 0–10 axis) per logged ride, oldest
 * left (issue #183). Hand-rolled {@link Canvas} drawing like {@code FitnessChartView}.
 */
public final class RecoveryTrendChartView extends View {

    public static final int COLOR_RPE = Color.parseColor("#FFD400");
    public static final int COLOR_SLEEP = Color.parseColor("#2196F3");

    private static final float PAD_LEFT = 28f;
    private static final float PAD_RIGHT = 8f;
    private static final float PAD_TOP = 10f;
    private static final float PAD_BOTTOM = 10f;
    private static final float AXIS_MAX = 10f;

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private List<Point> points = new ArrayList<>();

    public RecoveryTrendChartView(Context context) {
        super(context);
        init();
    }

    public RecoveryTrendChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public RecoveryTrendChartView(Context context, @Nullable AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2.5f * density);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        dotPaint.setStyle(Paint.Style.FILL);

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setColor(Color.parseColor("#2A2F35"));
        gridPaint.setStrokeWidth(1f * density);
        gridPaint.setPathEffect(new DashPathEffect(new float[]{8f, 6f}, 0f));

        labelPaint.setColor(Color.parseColor("#9299A1"));
        labelPaint.setTextSize(11f * density);
        labelPaint.setTextAlign(Paint.Align.RIGHT);
    }

    /** Points oldest first; the caller decides how many to show. */
    public void setPoints(List<Point> points) {
        this.points = Objects.requireNonNull(points, "points");
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(width, ChartArea.measuredHeight(width, heightMeasureSpec, 0.5f));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (points.isEmpty()) return;

        float density = getResources().getDisplayMetrics().density;
        float left = PAD_LEFT * density;
        float right = getWidth() - PAD_RIGHT * density;
        float top = PAD_TOP * density;
        float bottom = getHeight() - PAD_BOTTOM * density;
        ChartArea area = new ChartArea(left, top, right, bottom);

        for (int v = 0; v <= (int) AXIS_MAX; v += 5) {
            float yy = y((float) v, top, bottom);
            canvas.drawLine(left, yy, right, yy, gridPaint);
            canvas.drawText(String.valueOf(v), left - 4 * density,
                    yy + labelPaint.getTextSize() / 3, labelPaint);
        }

        drawSeries(canvas, true, COLOR_RPE, area, density);
        drawSeries(canvas, false, COLOR_SLEEP, area, density);
    }

    private void drawSeries(Canvas canvas, boolean rpe, int color, ChartArea area,
                            float density) {
        path.reset();
        int n = points.size();
        float step = n > 1 ? (area.right - area.left) / (n - 1) : 0f;
        float scale = AXIS_MAX / (rpe ? RecoveryTrendAnalyzer.MAX_RPE
                : RecoveryTrendAnalyzer.MAX_SLEEP);
        linePaint.setColor(color);
        dotPaint.setColor(color);
        for (int i = 0; i < n; i++) {
            Point p = points.get(i);
            float x = n > 1 ? area.left + i * step : (area.left + area.right) / 2f;
            float yy = y((rpe ? p.rpe : p.sleepQuality) * scale, area.top, area.bottom);
            if (i == 0) path.moveTo(x, yy);
            else path.lineTo(x, yy);
            canvas.drawCircle(x, yy, 3f * density, dotPaint);
        }
        if (n > 1) canvas.drawPath(path, linePaint);
    }

    private static float y(float v, float top, float bottom) {
        return bottom - v / AXIS_MAX * (bottom - top);
    }
}
