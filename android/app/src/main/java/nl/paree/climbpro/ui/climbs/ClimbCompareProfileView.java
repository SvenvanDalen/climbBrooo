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

    private double[] firstDist;
    private double[] firstHeight;
    private double[] secondDist;
    private double[] secondHeight;

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
        drawProfile(new ProfileStroke()
                .canvas(canvas)
                .dist(secondDist)
                .height(secondHeight)
                .left(left)
                .baseline(baseline)
                .sx(sx)
                .sy(sy)
                .paint(secondPaint));
        drawProfile(new ProfileStroke()
                .canvas(canvas)
                .dist(firstDist)
                .height(firstHeight)
                .left(left)
                .baseline(baseline)
                .sx(sx)
                .sy(sy)
                .paint(firstPaint));
    }

    private void drawProfile(ProfileStroke in) {
        if (in.dist == null || in.height == null || in.dist.length < 2) return;
        path.reset();
        for (int i = 0; i < in.dist.length && i < in.height.length; i++) {
            float x = in.left + (float) in.dist[i] * in.sx;
            float y = in.baseline - (float) in.height[i] * in.sy;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        in.canvas.drawPath(path, in.paint);
    }

    private static double last(double[] a) {
        return a != null && a.length > 0 ? a[a.length - 1] : 0;
    }

    private static double max(double[] a) {
        double m = 0;
        if (a != null) for (double v : a) m = Math.max(m, v);
        return m;
    }

    /** Arguments of {@link #drawProfile}. */
    public static final class ProfileStroke {
        private Canvas canvas;
        private double[] dist;
        private double[] height;
        private float left;
        private float baseline;
        private float sx;
        private float sy;
        private Paint paint;

        public ProfileStroke canvas(Canvas canvas) {
            this.canvas = canvas;
            return this;
        }

        public ProfileStroke dist(double[] dist) {
            this.dist = dist;
            return this;
        }

        public ProfileStroke height(double[] height) {
            this.height = height;
            return this;
        }

        public ProfileStroke left(float left) {
            this.left = left;
            return this;
        }

        public ProfileStroke baseline(float baseline) {
            this.baseline = baseline;
            return this;
        }

        public ProfileStroke sx(float sx) {
            this.sx = sx;
            return this;
        }

        public ProfileStroke sy(float sy) {
            this.sy = sy;
            return this;
        }

        public ProfileStroke paint(Paint paint) {
            this.paint = paint;
            return this;
        }
    }
}
