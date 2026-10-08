package nl.paree.climbpro.ui.records;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
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

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class RecordsViewsTest {

    private final Context ctx = ApplicationProvider.getApplicationContext();

    private static void layout(View v, int w, int h) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, w, h);
    }

    // --- PowerCurveChartView ---

    @Test
    public void powerCurve_drawsLabelsAndBothCurves_stoppingAtGaps() {
        PowerCurveChartView v = new PowerCurveChartView(ctx);
        v.setCurves(new int[]{800, 400, 0, 250}, new int[]{900, 450, 320, 270},
                new String[]{"5s", "1m", "5m", "20m"});
        layout(v, 400, 200);
        Canvas c = mock(Canvas.class);

        v.onDraw(c);

        ArgumentCaptor<String> labels = ArgumentCaptor.forClass(String.class);
        verify(c, times(4)).drawText(labels.capture(), anyFloat(), anyFloat(), any(Paint.class));
        assertEquals(Arrays.asList("5s", "1m", "5m", "20m"), labels.getAllValues());
        verify(c, times(2)).drawPath(any(Path.class), any(Paint.class));
        // All-time has 4 points, the period stops at its 0: 4 + 2 dots.
        verify(c, times(6)).drawCircle(anyFloat(), anyFloat(), anyFloat(), any(Paint.class));
    }

    @Test
    public void powerCurve_tooFewLabelsOrNoWatts_drawsNothing() {
        PowerCurveChartView v = new PowerCurveChartView(ctx, null);
        layout(v, 400, 200);
        Canvas c = mock(Canvas.class);
        v.setCurves(null, null, null);
        v.onDraw(c);
        v.setCurves(new int[]{0, 0}, new int[]{0, 0}, new String[]{"a", "b"});
        v.onDraw(c);
        verify(c, never()).drawLine(anyFloat(), anyFloat(), anyFloat(), anyFloat(), any(Paint.class));
    }

    @Test
    public void powerCurve_measuresHalfWidth() {
        PowerCurveChartView v = new PowerCurveChartView(ctx);
        v.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.UNSPECIFIED);
        assertEquals(150, v.getMeasuredHeight());
    }

    // --- ZoneBarView ---

    @Test
    public void zoneBar_drawsProportionalSegmentsSkippingEmptyZones() {
        ZoneBarView v = new ZoneBarView(ctx);
        v.setZones(new int[]{60, 0, 180, 60}, ZoneBarView.HR_COLORS);
        layout(v, 300, 14);
        Canvas c = mock(Canvas.class);
        List<float[]> rects = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            rects.add(new float[]{inv.getArgument(0), inv.getArgument(2)});
            colors.add(((Paint) inv.getArgument(4)).getColor());
            return null;
        }).when(c).drawRect(anyFloat(), anyFloat(), anyFloat(), anyFloat(), any(Paint.class));

        v.onDraw(c);

        assertEquals(3, rects.size());
        assertEquals(0f, rects.get(0)[0], 0.01f);
        assertEquals(60f, rects.get(0)[1], 0.01f);
        assertEquals(240f, rects.get(1)[1], 0.01f);
        assertEquals(300f, rects.get(2)[1], 0.01f);
        assertEquals(Arrays.asList(ZoneBarView.HR_COLORS[0], ZoneBarView.HR_COLORS[2],
                ZoneBarView.HR_COLORS[3]), colors);
    }

    @Test
    public void zoneBar_moreZonesThanColors_isClamped() {
        ZoneBarView v = new ZoneBarView(ctx, null);
        v.setZones(new int[]{10, 10, 10}, new int[]{0xFF000000});
        layout(v, 300, 14);
        Canvas c = mock(Canvas.class);
        v.onDraw(c);
        verify(c, times(1)).drawRect(anyFloat(), anyFloat(), anyFloat(), anyFloat(), any(Paint.class));
    }

    @Test
    public void zoneBar_noTime_drawsNothing_andMeasuresFixedHeight() {
        ZoneBarView v = new ZoneBarView(ctx);
        v.setZones(null, ZoneBarView.POWER_COLORS);
        Canvas c = mock(Canvas.class);
        layout(v, 300, 14);
        v.onDraw(c);
        verify(c, never())
                .drawRect(anyFloat(), anyFloat(), anyFloat(), anyFloat(), any(Paint.class));
        v.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.UNSPECIFIED);
        assertEquals((int) (14 * ctx.getResources().getDisplayMetrics().density),
                v.getMeasuredHeight());
    }
}
