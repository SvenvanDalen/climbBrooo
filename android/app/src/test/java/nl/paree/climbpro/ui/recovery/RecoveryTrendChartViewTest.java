package nl.paree.climbpro.ui.recovery;

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

import nl.paree.climbpro.data.recovery.RecoveryCheck;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.domain.recovery.RecoveryTrendAnalyzer;
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
public class RecoveryTrendChartViewTest {

    private final Context ctx = ApplicationProvider.getApplicationContext();

    private static RecoveryTrendAnalyzer.Point point(int rpe, int sleep) {
        RecoveryCheck c = new RecoveryCheck();
        c.rpe = rpe;
        c.sleepQuality = sleep;
        StoredRide r = new StoredRide();
        r.activityId = rpe * 10L + sleep;
        r.name = "Rit";
        r.distanceM = 30_000;
        r.movingTimeSec = 3600;
        return Construct.of(RecoveryTrendAnalyzer.Point.class, c, r);
    }

    private static void layout(View v, int w, int h) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, w, h);
    }

    @Test
    public void drawsGridAndBothSeriesInTheirColors() {
        RecoveryTrendChartView v = new RecoveryTrendChartView(ctx);
        v.setPoints(Arrays.asList(point(6, 3), point(8, 2), point(4, 5)));
        layout(v, 400, 200);
        Canvas c = mock(Canvas.class);
        List<Integer> lineColors = new ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            lineColors.add(((Paint) inv.getArgument(1)).getColor());
            return null;
        }).when(c).drawPath(any(Path.class), any(Paint.class));

        v.onDraw(c);

        ArgumentCaptor<String> labels = ArgumentCaptor.forClass(String.class);
        verify(c, times(3)).drawText(labels.capture(), anyFloat(), anyFloat(), any(Paint.class));
        assertEquals(Arrays.asList("0", "5", "10"), labels.getAllValues());
        verify(c, times(3)).drawLine(anyFloat(), anyFloat(), anyFloat(), anyFloat(), any(Paint.class));
        verify(c, times(6)).drawCircle(anyFloat(), anyFloat(), anyFloat(), any(Paint.class));
        assertEquals(Arrays.asList(RecoveryTrendChartView.COLOR_RPE,
                RecoveryTrendChartView.COLOR_SLEEP), lineColors);
    }

    @Test
    public void singlePoint_isCentredDotWithoutLine() {
        RecoveryTrendChartView v = new RecoveryTrendChartView(ctx, null);
        v.setPoints(Collections.singletonList(point(10, 5)));
        layout(v, 400, 200);
        Canvas c = mock(Canvas.class);
        ArgumentCaptor<Float> xs = ArgumentCaptor.forClass(Float.class);

        v.onDraw(c);

        verify(c, never()).drawPath(any(Path.class), any(Paint.class));
        verify(c, times(2)).drawCircle(xs.capture(), anyFloat(), anyFloat(), any(Paint.class));
        float density = ctx.getResources().getDisplayMetrics().density;
        float centre = (28 * density + 400 - 8 * density) / 2f;
        assertEquals(centre, xs.getAllValues().get(0), 0.01f);
    }

    @Test
    public void empty_drawsNothing_andMeasureModes() {
        RecoveryTrendChartView v = new RecoveryTrendChartView(ctx, null, 0);
        v.setPoints(null);
        layout(v, 400, 200);
        Canvas c = mock(Canvas.class);
        v.onDraw(c);
        verify(c, never()).drawLine(anyFloat(), anyFloat(), anyFloat(), anyFloat(), any(Paint.class));

        v.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.UNSPECIFIED);
        assertEquals(200, v.getMeasuredHeight());
        v.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.AT_MOST));
        assertEquals(80, v.getMeasuredHeight());
    }
}
