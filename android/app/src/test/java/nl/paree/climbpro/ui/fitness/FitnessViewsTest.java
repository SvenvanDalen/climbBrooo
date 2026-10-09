package nl.paree.climbpro.ui.fitness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.domain.training.FitnessCalculator;
import nl.paree.climbpro.domain.training.TrainingLoadCalendar;
import nl.paree.climbpro.ui.Construct;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class FitnessViewsTest {

    private final Context ctx = ApplicationProvider.getApplicationContext();

    private static void layout(View v, int w, int h) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, w, h);
    }

    private static List<String> texts(Canvas c) {
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(c, atLeastOnce()).drawText(cap.capture(), anyFloat(), anyFloat(), any(Paint.class));
        return cap.getAllValues();
    }

    // --- FitnessChartView ---

    private static FitnessCalculator.Day day(LocalDate d, double ctl, double atl, double tsb) {
        return Construct.of(FitnessCalculator.Day.class, d, 0.0, ctl, atl, tsb);
    }

    @Test
    public void fitness_drawsThreeSeriesWithAxisLabels() {
        FitnessChartView v = new FitnessChartView(ctx);
        LocalDate d0 = LocalDate.of(2026, 3, 1);
        v.setDays(Arrays.asList(day(d0, 40, 60, -20), day(d0.plusDays(1), 42, 55, -13),
                day(d0.plusDays(2), 44, 30, 14)));
        layout(v, 400, 240);
        Canvas c = mock(Canvas.class);
        List<Integer> colors = new ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            colors.add(((Paint) inv.getArgument(1)).getColor());
            return null;
        }).when(c).drawPath(any(Path.class), any(Paint.class));

        v.onDraw(c);

        assertEquals(Arrays.asList(FitnessChartView.COLOR_FITNESS, FitnessChartView.COLOR_FATIGUE,
                FitnessChartView.COLOR_FORM), colors);
        List<String> t = texts(c);
        assertTrue(t.toString(), t.contains("60"));
        assertTrue(t.contains("0"));
        assertTrue(t.contains("-20"));
        assertEquals(5, t.size());
    }

    @Test
    public void fitness_positiveFormOnly_hasNoNegativeLabel() {
        FitnessChartView v = new FitnessChartView(ctx, null);
        LocalDate d0 = LocalDate.of(2026, 3, 1);
        v.setDays(Arrays.asList(day(d0, 1, 1, 1), day(d0.plusDays(1), 2, 2, 2)));
        layout(v, 400, 240);
        Canvas c = mock(Canvas.class);

        v.onDraw(c);

        List<String> t = texts(c);
        assertEquals(4, t.size());
        assertTrue(t.contains("10"));
    }

    @Test
    public void fitness_lessThanTwoDays_drawsNothing() {
        FitnessChartView v = new FitnessChartView(ctx, null, 0);
        v.setDays(null);
        layout(v, 400, 240);
        Canvas c = mock(Canvas.class);
        v.onDraw(c);
        v.setDays(Collections.singletonList(day(LocalDate.now(), 1, 1, 1)));
        v.onDraw(c);
        verify(c, never()).drawPath(any(Path.class), any(Paint.class));
    }

    @Test
    public void fitness_measure() {
        FitnessChartView v = new FitnessChartView(ctx);
        v.measure(View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.UNSPECIFIED);
        assertEquals(300, v.getMeasuredHeight());
        v.measure(View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.AT_MOST));
        assertEquals(100, v.getMeasuredHeight());
    }

    // --- TrainingLoadHeatmapView ---

    private static TrainingLoadCalendar.Result calendar(LocalDate monday, int days) {
        List<TrainingLoadCalendar.Day> list = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            list.add(Construct.of(TrainingLoadCalendar.Day.class, monday.plusDays(i),
                    (double) (i * 30), 1, 0, 100.0));
        }
        int weeks = (days + 6) / 7;
        return Construct.of(TrainingLoadCalendar.Result.class, monday, weeks, list, days, 0.0, 1,
                null, 0.0);
    }

    @Test
    public void heatmap_measuresGridSize() {
        TrainingLoadHeatmapView v = new TrainingLoadHeatmapView(ctx);
        float d = ctx.getResources().getDisplayMetrics().density;
        v.setResult(calendar(LocalDate.of(2026, 1, 5), 21));
        v.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        assertEquals((int) Math.ceil(34 * d + 3 * 17 * d), v.getMeasuredWidth());
        assertEquals((int) Math.ceil(18 * d + 7 * 17 * d), v.getMeasuredHeight());
    }

    @Test
    public void heatmap_drawsOneCellPerDayInLevelColor() {
        TrainingLoadHeatmapView v = new TrainingLoadHeatmapView(ctx, null);
        TrainingLoadCalendar.Result r = calendar(LocalDate.of(2026, 1, 26), 14);
        v.setResult(r);
        layout(v, 200, 200);
        Canvas c = mock(Canvas.class);
        List<Integer> colors = new ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            colors.add(((Paint) inv.getArgument(3)).getColor());
            return null;
        }).when(c).drawRoundRect(any(RectF.class), anyFloat(), anyFloat(), any(Paint.class));

        v.onDraw(c);

        assertEquals(14, colors.size());
        for (int i = 0; i < 14; i++) {
            assertEquals(TrainingLoadHeatmapView.LEVEL_COLORS[r.days.get(i).level.ordinal()],
                    (int) colors.get(i));
        }
        // Day-of-week labels plus a month label for the first column and February.
        List<String> t = texts(c);
        assertEquals(5, t.size());
    }

    @Test
    public void heatmap_tapSelectsDayAndNotifiesListener() {
        TrainingLoadHeatmapView v = new TrainingLoadHeatmapView(ctx, null, 0);
        TrainingLoadCalendar.Result r = calendar(LocalDate.of(2026, 1, 5), 14);
        v.setResult(r);
        layout(v, 200, 200);
        float d = ctx.getResources().getDisplayMetrics().density;
        TrainingLoadCalendar.Day[] got = {null};
        v.setOnDayClickListener(day -> got[0] = day);

        assertTrue(v.onTouchEvent(event(MotionEvent.ACTION_DOWN, 0, 0)));
        // Column 1, row 2 = day index 9.
        float x = 26 * d + 17 * d + 2 * d;
        float y = 18 * d + 2 * 17 * d + 2 * d;
        assertTrue(v.onTouchEvent(event(MotionEvent.ACTION_UP, x, y)));
        assertSame(r.days.get(9), got[0]);

        Canvas c = mock(Canvas.class);
        v.onDraw(c);
        // Selected cell gets a second (outline) round rect.
        verify(c, times(15)).drawRoundRect(any(RectF.class), anyFloat(), anyFloat(), any(Paint.class));
    }

    @Test
    public void heatmap_tapsOutsideGridOrPastLastDay_areIgnored() {
        TrainingLoadHeatmapView v = new TrainingLoadHeatmapView(ctx);
        v.setResult(calendar(LocalDate.of(2026, 1, 5), 9));
        layout(v, 400, 200);
        float d = ctx.getResources().getDisplayMetrics().density;
        TrainingLoadCalendar.Day[] got = {null};
        v.setOnDayClickListener(day -> got[0] = day);

        assertTrue(v.onTouchEvent(event(MotionEvent.ACTION_UP, 1, 1)));
        assertTrue(v.onTouchEvent(event(MotionEvent.ACTION_UP, 26 * d + 1, 18 * d + 8 * 17 * d)));
        assertTrue(v.onTouchEvent(event(MotionEvent.ACTION_UP, 26 * d + 1 + 5 * 17 * d, 18 * d + 1)));
        assertNull(got[0]);
        v.onTouchEvent(event(MotionEvent.ACTION_MOVE, 5, 5));
        assertNull(got[0]);
    }

    @Test
    public void heatmap_withoutResult_ignoresTouchAndDrawsNothing() {
        TrainingLoadHeatmapView v = new TrainingLoadHeatmapView(ctx);
        layout(v, 100, 100);
        assertFalse(v.onTouchEvent(event(MotionEvent.ACTION_UP, 50, 50)));
        Canvas c = mock(Canvas.class);
        v.onDraw(c);
        verify(c, never()).drawText(any(String.class), anyFloat(), anyFloat(), any(Paint.class));
    }

    private static MotionEvent event(int action, float x, float y) {
        long t = SystemClock.uptimeMillis();
        return MotionEvent.obtain(t, t, action, x, y, 0);
    }
}
