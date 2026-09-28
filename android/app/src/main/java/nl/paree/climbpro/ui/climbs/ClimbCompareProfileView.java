package nl.paree.climbpro.ui.climbs;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import nl.paree.climbpro.R;

/**
 * Two climb profiles on one scale (issue #214): height above the foot against distance from
 * the foot, so a long gentle climb and a short steep one can be compared at a glance. The
 * first climb is yellow, the second blue. Hand-rolled {@link Canvas} drawing like
 * {@link ClimbProfileView}.
 */
public final class ClimbCompareProfileView extends View {

    static final int COLOR_SECOND = Color.parseColor("#42A5F5");

    private static final float PAD = 12f;
    private static final float PAD_BOTTOM = 24f;

    private final Paint firstPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint secondPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private double[] firstDist, firstHeight, secondDist, secondHeight;

    public ClimbCompareProfileView(Context context) {
        super(context);
        init();
    }

    public ClimbCompareProfileView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        firstPaint.setStyle(Paint.Style.STROKE);
        firstPaint.setStrokeWidth(3f * density);
        firstPaint.setColor(ContextCompat.getColor(getContext(), R.color.color_accent));
        secondPaint.setStyle(Paint.Style.STROKE);
        secondPaint.setStrokeWidth(3f * density);
        secondPaint.setColor(COLOR_SECOND);
        axisPaint.setStyle(Paint.Style.STROKE);
        axisPaint.setColor(ContextCompat.getColor(getContext(), R.color.color_surface_raised));
        labelPaint.setColor(ContextCompat.getColor(getContext(), R.color.color_text_tertiary));
        labelPaint.setTextSize(11f * density);
    }

    public void setProfiles(double[] firstDist, double[] firstHeight, double[] secondDist,
                            double[] secondHeight) {
        this.firstDist = firstDist;
        this.firstHeight = firstHeight;
        this.secondDist = secondDist;
        this.secondHeight = secondHeight;
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
        double maxDist = Math.max(last(firstDist), last(secondDist));
        double maxHeight = Math.max(max(firstHeight), max(secondHeight));
        if (maxDist <= 0 || maxHeight <= 0) return;

        float density = getResources().getDisplayMetrics().density;
        float left = PAD * density;
        float right = getWidth() - PAD * density;
        float top = PAD * density;
        float baseline = getHeight() - PAD_BOTTOM * density;
        float sx = (float) ((right - left) / maxDist);
        float sy = (float) ((baseline - top) / maxHeight);

        canvas.drawLine(left, baseline, right, baseline, axisPaint);
        canvas.drawText(String.format(java.util.Locale.getDefault(), "%.1f km · %.0f m",
                maxDist / 1000.0, maxHeight), left, baseline + 16f * density, labelPaint);
        drawProfile(canvas, secondDist, secondHeight, left, baseline, sx, sy, secondPaint);
        drawProfile(canvas, firstDist, firstHeight, left, baseline, sx, sy, firstPaint);
    }

    private void drawProfile(Canvas canvas, double[] dist, double[] height, float left,
                             float baseline, float sx, float sy, Paint paint) {
        if (dist == null || height == null || dist.length < 2) return;
        path.reset();
        for (int i = 0; i < dist.length && i < height.length; i++) {
            float x = left + (float) dist[i] * sx;
            float y = baseline - (float) height[i] * sy;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        canvas.drawPath(path, paint);
    }

    private static double last(double[] a) {
        return a != null && a.length > 0 ? a[a.length - 1] : 0;
    }

    private static double max(double[] a) {
        double m = 0;
        if (a != null) for (double v : a) m = Math.max(m, v);
        return m;
    }
}
