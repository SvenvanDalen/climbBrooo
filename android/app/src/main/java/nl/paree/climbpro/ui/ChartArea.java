package nl.paree.climbpro.ui;

import android.view.View;

/** Plot rectangle of a chart view in pixels, plus the measuring rule the chart views share. */
public final class ChartArea {
    public final float left;
    public final float top;
    public final float right;
    public final float bottom;

    public ChartArea(float left, float top, float right, float bottom) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    /**
     * Height of a chart that is {@code ratio} times its width, honouring an exact or
     * at-most height from the parent.
     */
    public static int measuredHeight(int width, int heightMeasureSpec, float ratio) {
        int height = (int) (width * ratio);
        int mode = View.MeasureSpec.getMode(heightMeasureSpec);
        if (mode == View.MeasureSpec.EXACTLY) {
            return View.MeasureSpec.getSize(heightMeasureSpec);
        }
        if (mode == View.MeasureSpec.AT_MOST) {
            return Math.min(height, View.MeasureSpec.getSize(heightMeasureSpec));
        }
        return height;
    }
}
