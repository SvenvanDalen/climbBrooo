package nl.paree.climbpro.ui.fitness;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import nl.paree.climbpro.domain.training.FitnessCalculator.Day;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Fitness (CTL), fatigue (ATL) and form (TSB) over the last weeks (issue #220): three lines
 * against one value axis, with a dashed zero line for form. Hand-rolled {@link Canvas}
 * drawing like {@code RideFatigueChartView}, no charting dependency.
 */
public final class FitnessChartView extends View {

    public static final int COLOR_FITNESS = Color.parseColor("#2196F3");
    public static final int COLOR_FATIGUE = Color.parseColor("#E91E63");
    public static final int COLOR_FORM = Color.parseColor("#FFC107");

    private static final float PAD_LEFT = 36f;
    private static final float PAD_RIGHT = 8f;
    private static final float PAD_TOP = 12f;
    private static final float PAD_BOTTOM = 24f;

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zeroPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final DateTimeFormatter monthDay =
            DateTimeFormatter.ofPattern("d MMM", new Locale("nl"));

    private List<Day> days = new ArrayList<>();

    public FitnessChartView(Context context) {
        super(context);
        init();
    }

    public FitnessChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public FitnessChartView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2.5f * density);
        linePaint.setStrokeJoin(Paint.Join.ROUND);

        zeroPaint.setStyle(Paint.Style.STROKE);
        zeroPaint.setColor(Color.parseColor("#9299A1"));
        zeroPaint.setStrokeWidth(1f * density);
        zeroPaint.setPathEffect(new DashPathEffect(new float[]{8f, 6f}, 0f));

        axisPaint.setColor(Color.parseColor("#2A2F35"));
        axisPaint.setStrokeWidth(1f);
        axisPaint.setStyle(Paint.Style.STROKE);

        labelPaint.setColor(Color.parseColor("#9299A1"));
        labelPaint.setTextSize(11f * density);
    }

    public void setDays(List<Day> days) {
        this.days = days != null ? days : new ArrayList<>();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = (int) (width * 0.6f);
        int mode = MeasureSpec.getMode(heightMeasureSpec);
        if (mode == MeasureSpec.EXACTLY) {
            height = MeasureSpec.getSize(heightMeasureSpec);
        } else if (mode == MeasureSpec.AT_MOST) {
            height = Math.min(height, MeasureSpec.getSize(heightMeasureSpec));
        }
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (days.size() < 2) return;

        double max = 10, min = 0;
        for (Day d : days) {
            max = Math.max(max, Math.max(d.ctl, Math.max(d.atl, d.tsb)));
            min = Math.min(min, d.tsb);
        }
        max = Math.ceil(max / 10) * 10;
        min = Math.floor(min / 10) * 10;

        float density = getResources().getDisplayMetrics().density;
        float left = PAD_LEFT * density;
        float right = getWidth() - PAD_RIGHT * density;
        float top = PAD_TOP * density;
        float bottom = getHeight() - PAD_BOTTOM * density;

        canvas.drawLine(left, top, left, bottom, axisPaint);
        canvas.drawLine(left, bottom, right, bottom, axisPaint);
        float zeroY = y(0, min, max, top, bottom);
        canvas.drawLine(left, zeroY, right, zeroY, zeroPaint);

        labelPaint.setTextAlign(Paint.Align.RIGHT);
        float labelX = left - 4 * density;
        canvas.drawText(String.valueOf((int) max), labelX, top + labelPaint.getTextSize() / 2, labelPaint);
        canvas.drawText("0", labelX, zeroY + labelPaint.getTextSize() / 3, labelPaint);
        if (min < 0) canvas.drawText(String.valueOf((int) min), labelX, bottom, labelPaint);

        labelPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(monthDay.format(days.get(0).date), left, getHeight() - 4 * density, labelPaint);
        labelPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(monthDay.format(days.get(days.size() - 1).date), right,
                getHeight() - 4 * density, labelPaint);

        drawSeries(canvas, 0, COLOR_FITNESS, min, max, left, right, top, bottom);
        drawSeries(canvas, 1, COLOR_FATIGUE, min, max, left, right, top, bottom);
        drawSeries(canvas, 2, COLOR_FORM, min, max, left, right, top, bottom);
    }

    private void drawSeries(Canvas canvas, int which, int color, double min, double max,
                            float left, float right, float top, float bottom) {
        path.reset();
        float step = (right - left) / (days.size() - 1);
        for (int i = 0; i < days.size(); i++) {
            Day d = days.get(i);
            double v = which == 0 ? d.ctl : which == 1 ? d.atl : d.tsb;
            float x = left + i * step;
            float yy = y(v, min, max, top, bottom);
            if (i == 0) path.moveTo(x, yy);
            else path.lineTo(x, yy);
        }
        linePaint.setColor(color);
        canvas.drawPath(path, linePaint);
    }

    private static float y(double v, double min, double max, float top, float bottom) {
        return (float) (bottom - (v - min) / (max - min) * (bottom - top));
    }
}
