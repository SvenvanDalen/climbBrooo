package nl.paree.climbpro.ui.fitness;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import nl.paree.climbpro.domain.training.TrainingLoadCalendar;
import nl.paree.climbpro.domain.training.TrainingLoadCalendar.Day;

import java.time.DayOfWeek;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * GitHub-contribution-style grid of daily training load (issue #182): one column per week
 * (Monday on top), one square per day coloured by {@link TrainingLoadCalendar.Level}. Fixed
 * cell size, so the view is as wide as the number of weeks needs; put it in a
 * {@code HorizontalScrollView}. Tapping a day reports it to the {@link OnDayClickListener}.
 * Hand-rolled {@link Canvas} drawing like {@link FitnessChartView}, no charting dependency.
 */
public final class TrainingLoadHeatmapView extends View {

    public interface OnDayClickListener {
        void onDayClick(Day day);
    }

    /** Empty, light, moderate, hard, very hard. */
    public static final int[] LEVEL_COLORS = {
            Color.parseColor("#2A2F35"),
            Color.parseColor("#0E4429"),
            Color.parseColor("#006D32"),
            Color.parseColor("#26A641"),
            Color.parseColor("#39D353"),
    };

    private static final float CELL_DP = 14f;
    private static final float GAP_DP = 3f;
    private static final float LABEL_LEFT_DP = 26f;
    private static final float LABEL_TOP_DP = 18f;
    private static final float PAD_RIGHT_DP = 8f;

    private final Paint cellPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Locale dutch = new Locale("nl");
    private final DateTimeFormatter month = DateTimeFormatter.ofPattern("MMM", dutch);

    private float density;
    private TrainingLoadCalendar.Result result;
    private Day selected;
    @Nullable private OnDayClickListener listener;

    public TrainingLoadHeatmapView(Context context) {
        super(context);
        init();
    }

    public TrainingLoadHeatmapView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TrainingLoadHeatmapView(Context context, @Nullable AttributeSet attrs,
                                   int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        density = getResources().getDisplayMetrics().density;
        cellPaint.setStyle(Paint.Style.FILL);
        selectedPaint.setStyle(Paint.Style.STROKE);
        selectedPaint.setStrokeWidth(1.5f * density);
        selectedPaint.setColor(Color.WHITE);
        labelPaint.setColor(Color.parseColor("#9299A1"));
        labelPaint.setTextSize(10f * density);
    }

    public void setResult(TrainingLoadCalendar.Result result) {
        this.result = result;
        this.selected = null;
        requestLayout();
        invalidate();
    }

    public void setOnDayClickListener(@Nullable OnDayClickListener listener) {
        this.listener = listener;
    }

    private float step() { return (CELL_DP + GAP_DP) * density; }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int weeks = result != null ? result.weeks : 0;
        int width = (int) Math.ceil((LABEL_LEFT_DP + PAD_RIGHT_DP) * density + weeks * step());
        int height = (int) Math.ceil(LABEL_TOP_DP * density + 7 * step());
        setMeasuredDimension(resolveSize(width, widthMeasureSpec),
                resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (result == null) return;
        float left = LABEL_LEFT_DP * density;
        float top = LABEL_TOP_DP * density;
        float cell = CELL_DP * density;
        float radius = 2f * density;

        labelPaint.setTextAlign(Paint.Align.LEFT);
        for (DayOfWeek dow : new DayOfWeek[]{DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.FRIDAY}) {
            float y = top + (dow.getValue() - 1) * step() + cell * 0.8f;
            canvas.drawText(dow.getDisplayName(TextStyle.SHORT, dutch), 0, y, labelPaint);
        }

        int lastMonth = -1;
        for (Day d : result.days) {
            int col = (int) ChronoUnit.WEEKS.between(result.firstDay, d.date);
            int row = d.date.getDayOfWeek().getValue() - 1;
            float x = left + col * step();
            float y = top + row * step();
            if (row == 0 || d == result.days.get(0)) {
                int m = d.date.getMonthValue();
                // Label a column when a new month starts in it (and the very first column).
                if (m != lastMonth && (d.date.getDayOfMonth() <= 7 || lastMonth == -1)) {
                    canvas.drawText(month.format(d.date), x, top - 6 * density, labelPaint);
                }
                lastMonth = m;
            }
            cellPaint.setColor(LEVEL_COLORS[d.level.ordinal()]);
            rect.set(x, y, x + cell, y + cell);
            canvas.drawRoundRect(rect, radius, radius, cellPaint);
            if (d == selected) canvas.drawRoundRect(rect, radius, radius, selectedPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (result == null) return false;
        if (event.getAction() == MotionEvent.ACTION_DOWN) return true;
        if (event.getAction() != MotionEvent.ACTION_UP) return super.onTouchEvent(event);
        float left = LABEL_LEFT_DP * density;
        float top = LABEL_TOP_DP * density;
        int col = (int) Math.floor((event.getX() - left) / step());
        int row = (int) Math.floor((event.getY() - top) / step());
        if (col < 0 || row < 0 || row > 6) return true;
        int index = col * 7 + row;
        if (index >= result.days.size()) return true;
        selected = result.days.get(index);
        invalidate();
        performClick();
        if (listener != null) listener.onDayClick(selected);
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
