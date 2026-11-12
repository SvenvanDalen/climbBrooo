package nl.paree.climbpro.ui.mywhoosh;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** Dashboard bar chart (issue #383). */
@RunWith(RobolectricTestRunner.class)
public class SimpleBarChartViewTest {

    @Test
    public void drawsOneBarPerNonZeroValueAndEveryLabel() {
        SimpleBarChartView v = new SimpleBarChartView(ApplicationProvider.getApplicationContext());
        v.setBars(new double[]{10, 0, 25}, new String[]{"a", "b", "c"}, "km");
        v.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(160, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, 300, 160);
        Canvas c = mock(Canvas.class);
        v.onDraw(c);
        verify(c, times(2)).drawRect(anyFloat(), anyFloat(), anyFloat(), anyFloat(), any(Paint.class));
        // Three labels plus the maximum.
        verify(c, times(4)).drawText(anyString(), anyFloat(), anyFloat(), any(Paint.class));
    }

    @Test
    public void mismatchedLabelsDrawNothing() {
        SimpleBarChartView v = new SimpleBarChartView(ApplicationProvider.getApplicationContext());
        v.setBars(new double[]{10}, new String[0], "km");
        Canvas c = mock(Canvas.class);
        v.onDraw(c);
        verify(c, never()).drawText(anyString(), anyFloat(), anyFloat(), any(Paint.class));
    }
}
