package nl.paree.climbpro.ui.climbs;

import static org.junit.Assert.assertEquals;
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
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.domain.climb.RideFatigueCurveCalculator.FatiguePoint;
import nl.paree.climbpro.ui.Construct;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class ClimbChartViewsTest {

    private final Context ctx = ApplicationProvider.getApplicationContext();

    private static void layout(View v, int w, int h) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, w, h);
    }

    private static int measuredHeight(View v, int w, int hSpec) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), hSpec);
        return v.getMeasuredHeight();
    }

    private static List<String> texts(Canvas c) {
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(c, atLeastOnce()).drawText(cap.capture(), anyFloat(), anyFloat(), any(Paint.class));
        return cap.getAllValues();
    }

    // --- ClimbCompareProfileView ---

    @Test
    public void compare_measuresHalfWidth() {
        ClimbCompareProfileView v = new ClimbCompareProfileView(ctx);
        assertEquals(200, measuredHeight(v, 400, View.MeasureSpec.UNSPECIFIED));
        assertEquals(150, measuredHeight(v, 400,
                View.MeasureSpec.makeMeasureSpec(150, View.MeasureSpec.AT_MOST)));
    }

    @Test
    public void compare_drawsBothProfilesAndScaleLabel() {
        ClimbCompareProfileView v = new ClimbCompareProfileView(ctx, null);
        v.setProfiles(new double[]{0, 1000, 2500}, new double[]{0, 50, 180},
                new double[]{0, 2000}, new double[]{0, 120});
        layout(v, 400, 200);
        Canvas c = mock(Canvas.class);

        v.onDraw(c);

        verify(c, times(2)).drawPath(any(Path.class), any(Paint.class));
        assertEquals(Collections.singletonList(String.format(java.util.Locale.getDefault(),
                "%.1f km · %.0f m", 2.5, 180.0)), texts(c));
    }

    @Test
    public void compare_singlePointSecondProfile_onlyFirstDrawn() {
        ClimbCompareProfileView v = new ClimbCompareProfileView(ctx);
        v.setProfiles(new double[]{0, 1000}, new double[]{0, 50}, new double[]{0}, null);
        layout(v, 400, 200);
        Canvas c = mock(Canvas.class);

        v.onDraw(c);

        verify(c, times(1)).drawPath(any(Path.class), any(Paint.class));
    }

    @Test
    public void compare_noData_drawsNothing() {
        ClimbCompareProfileView v = new ClimbCompareProfileView(ctx);
        layout(v, 400, 200);
        Canvas c = mock(Canvas.class);
        v.onDraw(c);
        v.setProfiles(new double[]{0, 1000}, new double[]{0, 0}, null, null);
        v.onDraw(c);
        verify(c, never()).drawLine(anyFloat(), anyFloat(), anyFloat(), anyFloat(), any(Paint.class));
    }

    // --- RideFatigueChartView ---

    private static FatiguePoint fp(int ordinal, double vam, double rel) {
        return Construct.of(FatiguePoint.class, ordinal, "K" + ordinal, 600, vam, rel);
    }

    @Test
    public void fatigue_barColorsFollowRelativePace() {
        RideFatigueChartView v = new RideFatigueChartView(ctx);
        v.setPoints(Arrays.asList(fp(1, 1000, 100), fp(2, 950, 95), fp(3, 800, 80),
                fp(4, 600, 60)));
        layout(v, 400, 200);
        Canvas c = mock(Canvas.class);
        List<Integer> colors = new ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            colors.add(((Paint) inv.getArgument(4)).getColor());
            return null;
        }).when(c).drawRect(anyFloat(), anyFloat(), anyFloat(), anyFloat(), any(Paint.class));

        v.onDraw(c);

        assertEquals(Arrays.asList(0xFF4CAF50, 0xFFFFC107, 0xFFFF9800, 0xFFF44336), colors);
        assertEquals(Arrays.asList("1", "2", "3", "4"), texts(c));
        verify(c).drawPath(any(Path.class), any(Paint.class));
    }

    @Test
    public void fatigue_emptyOrNull_drawsNothing() {
        RideFatigueChartView v = new RideFatigueChartView(ctx, null, 0);
        v.setPoints(null);
        layout(v, 400, 200);
        Canvas c = mock(Canvas.class);
        v.onDraw(c);
        v.setPoints(Collections.singletonList(fp(1, 0, 100)));
        v.onDraw(c);
        verify(c, never()).drawRect(anyFloat(), anyFloat(), anyFloat(), anyFloat(), any(Paint.class));
    }

    @Test
    public void fatigue_measure_respectsModes() {
        RideFatigueChartView v = new RideFatigueChartView(ctx);
        assertEquals(200, measuredHeight(v, 400, View.MeasureSpec.UNSPECIFIED));
        assertEquals(120, measuredHeight(v, 400,
                View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.AT_MOST)));
        assertEquals(333, measuredHeight(v, 400,
                View.MeasureSpec.makeMeasureSpec(333, View.MeasureSpec.EXACTLY)));
    }

    // --- ClimbProfileView ---

    private static StoredSegment seg(int dist, int gain, double grad, int color) {
        StoredSegment s = new StoredSegment();
        s.distance = dist;
        s.elevationGain = gain;
        s.gradient = grad;
        s.colorIndex = color;
        return s;
    }

    @Test
    public void profile_drawsOneShapePerSegmentPlusOutline_andLabels() {
        ClimbProfileView v = new ClimbProfileView(ctx);
        v.setSegments(Arrays.asList(seg(600, 30, 0.05, 2), seg(600, 54, 0.09, 4),
                seg(600, 72, 0.12, 9)));
        layout(v, 1080, 432);
        Canvas c = mock(Canvas.class);
        List<Integer> fills = new ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            fills.add(((Paint) inv.getArgument(1)).getColor());
            return null;
        }).when(c).drawPath(any(Path.class), any(Paint.class));

        v.onDraw(c);

        assertEquals(4, fills.size());
        // Out-of-range color index is clamped to the last palette color.
        assertEquals(SegmentColorPalette.toColor(5), (int) fills.get(2));
        List<String> t = texts(c);
        assertEquals(true, t.contains("1.8 km"));
        assertEquals(true, t.contains("156 m"));
        assertEquals(true, t.contains("0 m"));
        assertEquals(true, t.contains("9%"));
    }

    @Test
    public void profile_shortClimb_labelsMeters_andNarrowSegmentsHaveNoGradient() {
        ClimbProfileView v = new ClimbProfileView(ctx, null);
        List<StoredSegment> segs = new ArrayList<>();
        for (int i = 0; i < 40; i++) segs.add(seg(20, 1, 0.05, 2));
        v.setSegments(segs);
        layout(v, 400, 160);
        Canvas c = mock(Canvas.class);

        v.onDraw(c);

        List<String> t = texts(c);
        assertEquals(true, t.contains("800 m"));
        assertEquals(false, t.contains("5%"));
    }

    @Test
    public void profile_flatOrEmpty_drawsNothing() {
        ClimbProfileView v = new ClimbProfileView(ctx, null, 0);
        v.setSegments(null);
        layout(v, 400, 160);
        Canvas c = mock(Canvas.class);
        v.onDraw(c);
        v.setSegments(Collections.singletonList(seg(500, 0, 0, 0)));
        v.onDraw(c);
        verify(c, never()).drawPath(any(Path.class), any(Paint.class));
        assertEquals(160, measuredHeight(v, 400, View.MeasureSpec.UNSPECIFIED));
        assertEquals(100, measuredHeight(v, 400,
                View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.AT_MOST)));
        assertEquals(90, measuredHeight(v, 400,
                View.MeasureSpec.makeMeasureSpec(90, View.MeasureSpec.EXACTLY)));
    }

    @Test
    public void views_drawOnRealCanvasWithoutCrashing() {
        ClimbProfileView p = new ClimbProfileView(ctx);
        p.setSegments(Collections.singletonList(seg(900, 60, 0.066, 3)));
        nl.paree.climbpro.ui.UiTestEnv.draw(p, 300, 120);
        ClimbCompareProfileView cp = new ClimbCompareProfileView(ctx);
        cp.setProfiles(new double[]{0, 10}, new double[]{0, 1}, null, null);
        nl.paree.climbpro.ui.UiTestEnv.draw(cp, 300, 150);
    }
}
