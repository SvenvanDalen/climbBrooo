package nl.paree.climbpro.ui.climbs;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import nl.paree.climbpro.data.route.StoredSegment;

/**
 * Custom view that renders a climb elevation profile similar to ClimbFinder.
 * Each segment is drawn as a colored column where:
 * - Width is proportional to the segment distance
 * - Height represents cumulative elevation
 * - Color reflects the gradient intensity
 */
public final class ClimbProfileView extends View {

    private static final int[] SEGMENT_COLORS = SegmentColorPalette.COLORS;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path profilePath = new Path();

    private List<StoredSegment> segments = new ArrayList<>();
    private int totalDistance = 0;
    private int totalElevation = 0;

    // Padding
    private static final float PAD_LEFT = 48f;
    private static final float PAD_RIGHT = 16f;
    private static final float PAD_TOP = 24f;
    private static final float PAD_BOTTOM = 40f;

    public ClimbProfileView(Context context) {
        super(context);
        init();
    }

    public ClimbProfileView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ClimbProfileView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        fillPaint.setStyle(Paint.Style.FILL);

        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setStrokeWidth(2f);
        outlinePaint.setColor(Color.parseColor("#424242"));

        textPaint.setColor(Color.parseColor("#616161"));
        textPaint.setTextSize(24f);

        axisPaint.setColor(Color.parseColor("#BDBDBD"));
        axisPaint.setStrokeWidth(1f);
        axisPaint.setStyle(Paint.Style.STROKE);
    }

    public void setSegments(List<StoredSegment> segs) {
        this.segments = segs != null ? segs : new ArrayList<>();
        totalDistance = 0;
        totalElevation = 0;
        for (StoredSegment s : segments) {
            totalDistance += s.distance;
            totalElevation += s.elevationGain;
        }
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = (int) (width * 0.4f); // 2.5:1 aspect ratio
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
        if (segments.isEmpty() || totalDistance == 0 || totalElevation == 0) return;

        float density = getResources().getDisplayMetrics().density;
        float padLeft = PAD_LEFT * density;
        float padRight = PAD_RIGHT * density;
        float padTop = PAD_TOP * density;
        float padBottom = PAD_BOTTOM * density;

        float chartWidth = getWidth() - padLeft - padRight;
        float chartHeight = getHeight() - padTop - padBottom;

        // Draw baseline
        canvas.drawLine(padLeft, getHeight() - padBottom, padLeft + chartWidth,
                getHeight() - padBottom, axisPaint);

        // Calculate cumulative elevations for profile shape
        float cumulativeElevation = 0;
        float cumulativeDistance = 0;

        // Draw each segment as a filled bar
        for (int i = 0; i < segments.size(); i++) {
            StoredSegment seg = segments.get(i);

            float x1 = padLeft + (cumulativeDistance / totalDistance) * chartWidth;
            float segWidth = ((float) seg.distance / totalDistance) * chartWidth;
            float x2 = x1 + segWidth;

            float y1Bottom = getHeight() - padBottom;
            // Bottom of bar = cumulative elevation before this segment
            float elevBottom = (cumulativeElevation / totalElevation) * chartHeight;
            // Top of bar = cumulative elevation after this segment
            cumulativeElevation += seg.elevationGain;
            float elevTop = (cumulativeElevation / totalElevation) * chartHeight;

            float yBottom = y1Bottom - elevBottom;
            float yTop = y1Bottom - elevTop;

            // Draw filled trapezoid for this segment
            Path segPath = new Path();
            segPath.moveTo(x1, y1Bottom);           // bottom-left (baseline)
            segPath.lineTo(x1, yBottom);             // top-left (prev elevation)
            segPath.lineTo(x2, yTop);                // top-right (new elevation)
            segPath.lineTo(x2, y1Bottom);            // bottom-right (baseline)
            segPath.close();

            int ci = Math.max(0, Math.min(5, seg.colorIndex));
            fillPaint.setColor(SEGMENT_COLORS[ci]);
            canvas.drawPath(segPath, fillPaint);

            // Draw segment border
            outlinePaint.setColor(Color.parseColor("#00000020"));
            outlinePaint.setStrokeWidth(1f);
            canvas.drawLine(x2, y1Bottom, x2, yTop, outlinePaint);

            // Draw gradient label on segments wide enough
            if (segWidth > 30 * density) {
                textPaint.setTextSize(11f * density);
                textPaint.setColor(Color.parseColor("#212121"));
                textPaint.setTextAlign(Paint.Align.CENTER);
                String label = String.format("%.0f%%", seg.gradient * 100);
                float labelX = (x1 + x2) / 2f;
                float labelY = (yBottom + yTop) / 2f - 4 * density;
                canvas.drawText(label, labelX, labelY, textPaint);
            }

            cumulativeDistance += seg.distance;
        }

        // Draw profile outline on top
        outlinePaint.setColor(Color.parseColor("#424242"));
        outlinePaint.setStrokeWidth(2f * density);
        profilePath.reset();
        cumulativeElevation = 0;
        cumulativeDistance = 0;
        float baseline = getHeight() - padBottom;
        profilePath.moveTo(padLeft, baseline);
        for (StoredSegment seg : segments) {
            cumulativeElevation += seg.elevationGain;
            cumulativeDistance += seg.distance;
            float x = padLeft + (cumulativeDistance / (float) totalDistance) * chartWidth;
            float y = baseline - (cumulativeElevation / (float) totalElevation) * chartHeight;
            profilePath.lineTo(x, y);
        }
        canvas.drawPath(profilePath, outlinePaint);

        // Draw axis labels
        textPaint.setTextSize(10f * density);
        textPaint.setColor(Color.parseColor("#757575"));
        textPaint.setTextAlign(Paint.Align.LEFT);

        // Distance label (bottom)
        String distLabel = totalDistance >= 1000
                ? String.format("%.1f km", totalDistance / 1000f)
                : totalDistance + " m";
        textPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(distLabel, padLeft + chartWidth, getHeight() - padBottom + 16 * density, textPaint);

        textPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("0", padLeft, getHeight() - padBottom + 16 * density, textPaint);

        // Elevation labels (left side)
        textPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("0 m", padLeft - 4 * density, baseline, textPaint);
        canvas.drawText(totalElevation + " m", padLeft - 4 * density, padTop + textPaint.getTextSize(), textPaint);
    }
}
