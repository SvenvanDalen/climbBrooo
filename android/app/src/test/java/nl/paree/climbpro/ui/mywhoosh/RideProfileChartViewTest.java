package nl.paree.climbpro.ui.mywhoosh;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyString;
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

import nl.paree.climbpro.domain.mywhoosh.RideProfile;
import nl.paree.climbpro.domain.ride.RideStreams;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** The ride profile overlay (issue #390). */
@RunWith(RobolectricTestRunner.class)
public class RideProfileChartViewTest {

    private final Context ctx = ApplicationProvider.getApplicationContext();

    /** 12 km at 1 Hz, 10 m/s, with altitude, power and heart rate but no cadence. */
    private static RideProfile profile() {
        int n = 1201;
        int[] t = new int[n];
        double[] d = new double[n];
        double[] alt = new double[n];
        double[] w = new double[n];
        double[] hr = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = i;
            d[i] = i * 10.0;
            alt[i] = 100 + i * 0.05;
            w[i] = 200 + (i % 60);
            hr[i] = 140 + (i % 20);
        }
        return RideProfile.of(new RideStreams(t, d, w, alt, hr, null));
    }

    private static void layout(View v, int w, int h) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, w, h);
    }

    @Test
    public void drawsProfileAreaAndEachVisibleLine() {
        RideProfileChartView v = new RideProfileChartView(ctx);
        v.setProfile(profile());
        layout(v, 1200, 300);
        Canvas c = mock(Canvas.class);
        v.onDraw(c);
        // Area + power + heart rate; cadence has no data and draws no path. km labels below.
        verify(c, times(3)).drawPath(any(Path.class), any(Paint.class));
        verify(c, atLeastOnce()).drawText(anyString(), anyFloat(), anyFloat(), any(Paint.class));

        v.setVisible(RideProfileChartView.POWER, false);
        Canvas c2 = mock(Canvas.class);
        v.onDraw(c2);
        verify(c2, times(2)).drawPath(any(Path.class), any(Paint.class));
    }

    @Test
    public void withoutProfileNothingIsDrawn() {
        RideProfileChartView v = new RideProfileChartView(ctx);
        layout(v, 400, 200);
        Canvas c = mock(Canvas.class);
        v.onDraw(c);
        verify(c, never()).drawPath(any(Path.class), any(Paint.class));
    }

    @Test
    public void wideRideMeasuresWiderThanTheScreen() {
        RideProfileChartView v = new RideProfileChartView(ctx);
        v.setProfile(profile());
        v.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        org.junit.Assert.assertTrue(v.getMeasuredWidth() >= 120 * RideProfileChartView.DP_PER_BIN);
    }
}
