package nl.paree.climbpro.ui.records;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * One horizontal bar split into zone colors by time share (issue #218). Five segments for
 * heart-rate zones, seven for power zones; the colors run from easy (grey) to hard.
 */
public final class ZoneBarView extends View {

    /** Heart-rate zones 1-5. */
    static final int[] HR_COLORS = {
            Color.parseColor("#9E9E9E"), Color.parseColor("#42A5F5"),
            Color.parseColor("#66BB6A"), Color.parseColor("#FFA726"),
            Color.parseColor("#EF5350")};
    /** Coggan power zones 1-7. */
    static final int[] POWER_COLORS = {
            Color.parseColor("#9E9E9E"), Color.parseColor("#42A5F5"),
            Color.parseColor("#66BB6A"), Color.parseColor("#FFEE58"),
            Color.parseColor("#FFA726"), Color.parseColor("#EF5350"),
            Color.parseColor("#AB47BC")};

    private static final float HEIGHT_DP = 14f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int[] seconds = new int[0];
    private int[] colors = HR_COLORS;

    public ZoneBarView(Context context) {
        super(context);
    }

    public ZoneBarView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public void setZones(int[] seconds, int[] colors) {
        this.seconds = seconds != null ? seconds : new int[0];
        this.colors = colors;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int height = (int) (HEIGHT_DP * getResources().getDisplayMetrics().density);
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec),
                resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long total = 0;
        for (int s : seconds) total += s;
        if (total <= 0) return;
        float x = 0;
        float width = getWidth();
        for (int k = 0; k < seconds.length && k < colors.length; k++) {
            float w = width * seconds[k] / total;
            if (w <= 0) continue;
            paint.setColor(colors[k]);
            canvas.drawRect(x, 0, x + w, getHeight(), paint);
            x += w;
        }
    }
}
