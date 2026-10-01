package nl.paree.climbpro.ui.routes;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import nl.paree.climbpro.domain.weather.TemperatureTrend;

/**
 * Line chart of the expected temperature over the planned ride (issue #153): x = route distance,
 * y = °C at the moment the rider passes. All numbers come from {@link TemperatureTrend}; this
 * view only scales and paints.
 */
public final class TemperatureTrendView extends View {

    private static final float PAD_LEFT = 44f;
    private static final float PAD_RIGHT = 16f;
    private static final float PAD_TOP = 16f;
    private static final float PAD_BOTTOM = 28f;
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm");

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();

    private TemperatureTrend trend;
    private ZoneId zone = ZoneId.systemDefault();
    /** Display units for the axis labels (issue #262); refreshed on every setTrend. */
    private nl.paree.climbpro.domain.units.UnitFormatter units =
            new nl.paree.climbpro.domain.units.UnitFormatter(
                    nl.paree.climbpro.domain.units.UnitPreferences.METRIC);

    public TemperatureTrendView(Context context) {
        super(context);
        init();
    }

    public TemperatureTrendView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TemperatureTrendView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setColor(Color.parseColor("#F28C28"));
        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(Color.parseColor("#F28C28"));
        textPaint.setColor(Color.parseColor("#9299A1"));
        axisPaint.setStyle(Paint.Style.STROKE);
        axisPaint.setStrokeWidth(1f);
        axisPaint.setColor(Color.parseColor("#2A2F35"));
    }

    public void setTrend(@Nullable TemperatureTrend t, ZoneId zone) {
        this.units = nl.paree.climbpro.data.settings.UnitPreferencesRepository.formatter(getContext());
        this.trend = t;
        this.zone = zone;
        if (t == null || t.isEmpty()) {
            setContentDescription("Geen temperatuurverwachting");
        } else {
            setContentDescription(String.format(Locale.ROOT,
                    "Verwachte temperatuur tussen %d en %d graden",
                    Math.round(t.minCelsius), Math.round(t.maxCelsius)));
        }
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = (int) (width * 0.35f);
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
        TemperatureTrend t = trend;
        if (t == null || t.isEmpty()) return;
        List<TemperatureTrend.Point> pts = t.points;

        float density = getResources().getDisplayMetrics().density;
        float left = PAD_LEFT * density;
        float right = getWidth() - PAD_RIGHT * density;
        float top = PAD_TOP * density;
        float baseline = getHeight() - PAD_BOTTOM * density;
        if (right <= left || baseline <= top) return;

        double x0 = pts.get(0).distanceM;
        double xSpan = Math.max(1.0, pts.get(pts.size() - 1).distanceM - x0);
        // At least a 4 °C window so a steady forecast is a flat line, not a zoomed-in zigzag.
        double mid = (t.minCelsius + t.maxCelsius) / 2;
        double ySpan = Math.max(4.0, t.maxCelsius - t.minCelsius);
        double yMin = mid - ySpan / 2;
        float w = right - left;
        float h = baseline - top;

        canvas.drawLine(left, baseline, right, baseline, axisPaint);
        canvas.drawLine(left, top, left, baseline, axisPaint);

        linePath.reset();
        linePaint.setStrokeWidth(2f * density);
        float r = 2.5f * density;
        for (int i = 0; i < pts.size(); i++) {
            TemperatureTrend.Point p = pts.get(i);
            float x = pts.size() == 1 ? left + w / 2
                    : left + (float) ((p.distanceM - x0) / xSpan) * w;
            float y = baseline - (float) ((p.celsius - yMin) / ySpan) * h;
            if (i == 0) linePath.moveTo(x, y); else linePath.lineTo(x, y);
            canvas.drawCircle(x, y, r, dotPaint);
        }
        canvas.drawPath(linePath, linePaint);

        textPaint.setTextSize(10f * density);
        textPaint.setTextAlign(Paint.Align.RIGHT);
        // Axis labels in the rider's temperature unit (issue #262); the plot stays in °C.
        canvas.drawText(Math.round(units.temperatureValue(yMin + ySpan)) + "°", left - 4 * density,
                top + textPaint.getTextSize(), textPaint);
        canvas.drawText(Math.round(units.temperatureValue(yMin)) + "°", left - 4 * density,
                baseline, textPaint);

        float labelY = baseline + 16 * density;
        TemperatureTrend.Point first = pts.get(0);
        TemperatureTrend.Point last = pts.get(pts.size() - 1);
        canvas.drawText(String.format(Locale.ROOT, "%.0f %s · %s",
                units.distanceValue(last.distanceM), units.distanceUnit(),
                HOUR.format(last.eta.atZone(zone))), right, labelY, textPaint);
        textPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(String.format(Locale.ROOT, "%.0f %s · %s",
                units.distanceValue(first.distanceM), units.distanceUnit(),
                HOUR.format(first.eta.atZone(zone))), left, labelY, textPaint);
    }
}
