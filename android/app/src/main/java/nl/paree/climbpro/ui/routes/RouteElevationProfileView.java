package nl.paree.climbpro.ui.routes;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.Locale;

import nl.paree.climbpro.R;
import nl.paree.climbpro.ui.climbs.SegmentColorPalette;

/**
 * Draws the elevation profile of a whole route (issue #207) with every climb highlighted in its
 * segment gradient colors ({@link SegmentColorPalette}). All data preparation — downsampling,
 * climb bands, min/max — lives in {@link RouteElevationProfile}; this view only scales and paints.
 */
public final class RouteElevationProfileView extends View {

    private static final float PAD_LEFT = 48f;
    private static final float PAD_RIGHT = 16f;
    private static final float PAD_TOP = 16f;
    private static final float PAD_BOTTOM = 28f;

    private final Paint baseFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path areaPath = new Path();
    private final Path linePath = new Path();

    private RouteElevationProfile profile;

    public RouteElevationProfileView(Context context) {
        super(context);
        init();
    }

    public RouteElevationProfileView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public RouteElevationProfileView(Context context, @Nullable AttributeSet attrs,
                                     int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        baseFillPaint.setStyle(Paint.Style.FILL);
        baseFillPaint.setColor(Color.parseColor("#3A3F45"));
        bandPaint.setStyle(Paint.Style.FILL);
        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setColor(Color.parseColor("#F4F5F2"));
        textPaint.setColor(Color.parseColor("#9299A1"));
        axisPaint.setStyle(Paint.Style.STROKE);
        axisPaint.setStrokeWidth(1f);
        axisPaint.setColor(Color.parseColor("#2A2F35"));
    }

    public void setProfile(@Nullable RouteElevationProfile p) {
        this.profile = p;
        if (p == null || p.isEmpty()) {
            setContentDescription(getContext().getString(R.string.route_profile_empty));
        } else {
            setContentDescription(getContext().getString(R.string.route_profile_description,
                    p.totalDistance / 1000.0, Math.round(p.minElevation),
                    Math.round(p.maxElevation), countClimbs(p)));
        }
        invalidate();
    }

    private static int countClimbs(RouteElevationProfile p) {
        int count = 0;
        int last = -1;
        for (RouteElevationProfile.Band b : p.bands) {
            if (b.climbIndex != last) {
                count++;
                last = b.climbIndex;
            }
        }
        return count;
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
        RouteElevationProfile p = profile;
        if (p == null || p.isEmpty()) return;

        float density = getResources().getDisplayMetrics().density;
        float left = PAD_LEFT * density;
        float right = getWidth() - PAD_RIGHT * density;
        float top = PAD_TOP * density;
        float baseline = getHeight() - PAD_BOTTOM * density;
        if (right <= left || baseline <= top) return;

        double x0 = p.distances[0];
        double xSpan = p.totalDistance - x0;
        // Pad the elevation range so a flat route doesn't render as a divide-by-zero spike.
        double yMin = p.minElevation;
        double ySpan = Math.max(10.0, p.maxElevation - yMin);
        float w = right - left;
        float h = baseline - top;

        areaPath.reset();
        linePath.reset();
        areaPath.moveTo(left, baseline);
        for (int i = 0; i < p.distances.length; i++) {
            float x = left + (float) ((p.distances[i] - x0) / xSpan) * w;
            float y = baseline - (float) ((p.elevations[i] - yMin) / ySpan) * h;
            areaPath.lineTo(x, y);
            if (i == 0) linePath.moveTo(x, y); else linePath.lineTo(x, y);
        }
        areaPath.lineTo(right, baseline);
        areaPath.close();

        canvas.drawPath(areaPath, baseFillPaint);
        for (RouteElevationProfile.Band b : p.bands) {
            float bx1 = left + (float) ((b.startDistance - x0) / xSpan) * w;
            float bx2 = left + (float) ((b.endDistance - x0) / xSpan) * w;
            bandPaint.setColor(SegmentColorPalette.toColor(b.colorIndex));
            canvas.save();
            canvas.clipRect(bx1, top, Math.max(bx2, bx1 + 1f), baseline);
            canvas.drawPath(areaPath, bandPaint);
            canvas.restore();
        }

        canvas.drawLine(left, baseline, right, baseline, axisPaint);
        outlinePaint.setStrokeWidth(1.5f * density);
        canvas.drawPath(linePath, outlinePaint);

        textPaint.setTextSize(10f * density);
        textPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(String.format(Locale.ROOT, "%d m", Math.round(p.maxElevation)),
                left - 4 * density, top + textPaint.getTextSize(), textPaint);
        canvas.drawText(String.format(Locale.ROOT, "%d m", Math.round(p.minElevation)),
                left - 4 * density, baseline, textPaint);
        float labelY = baseline + 16 * density;
        canvas.drawText(String.format(Locale.ROOT, "%.1f km", p.totalDistance / 1000.0),
                right, labelY, textPaint);
        textPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(x0 < 50 ? "0"
                : String.format(Locale.ROOT, "%.1f km", x0 / 1000.0), left, labelY, textPaint);
    }
}
