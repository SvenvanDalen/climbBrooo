package nl.paree.climbpro.ui.mywhoosh;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/** Bars with a label under each and the value above the highest (issue #383). */
public final class SimpleBarChartView extends View {

    private double[] values = new double[0];
    private String[] labels = new String[0];
    private String unit = "";
    private final float density;
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    public SimpleBarChartView(Context context) {
        this(context, null);
    }

    public SimpleBarChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        bar.setColor(0xFFFFC107);
        text.setColor(Color.LTGRAY);
        text.setTextSize(10 * density);
        text.setTextAlign(Paint.Align.CENTER);
    }

    /** {@code labels} must be as long as {@code values}. */
    public void setBars(double[] values, String[] labels, String unit) {
        this.values = values;
        this.labels = labels;
        this.unit = unit;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec),
                resolveSize((int) (160 * density), heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int n = values.length;
        if (n == 0 || labels.length != n) return;
        double max = 0;
        int maxIndex = 0;
        for (int i = 0; i < n; i++) {
            if (values[i] > max) {
                max = values[i];
                maxIndex = i;
            }
        }
        float top = 16 * density;
        float bottom = getHeight() - 16 * density;
        float slot = (float) getWidth() / n;
        for (int i = 0; i < n; i++) {
            float cx = slot * i + slot / 2;
            if (max > 0 && values[i] > 0) {
                float h = (float) (values[i] / max) * (bottom - top);
                canvas.drawRect(cx - slot * 0.35f, bottom - h, cx + slot * 0.35f, bottom, bar);
            }
            canvas.drawText(labels[i], cx, bottom + 12 * density, text);
        }
        if (max > 0) {
            canvas.drawText(Math.round(max) + " " + unit, slot * maxIndex + slot / 2,
                    top - 4 * density, text);
        }
    }
}
