package nl.paree.climbpro.ui.explore;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.osmdroid.api.IGeoPoint;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class ExploredTilesOverlayTest {

    /** A projection over lat 50..51, lon 5..6 mapping one degree to 1000 px. */
    static MapView map() {
        MapView map = mock(MapView.class);
        Projection p = mock(Projection.class);
        when(map.getProjection()).thenReturn(p);
        when(p.getBoundingBox()).thenReturn(new BoundingBox(51, 6, 50, 5));
        when(p.toPixels(any(IGeoPoint.class), any(Point.class))).thenAnswer(inv -> {
            IGeoPoint g = inv.getArgument(0);
            Point out = inv.getArgument(1);
            out.x = (int) Math.round((g.getLongitude() - 5) * 1000);
            out.y = (int) Math.round((51 - g.getLatitude()) * 1000);
            return out;
        });
        return map;
    }

    private static List<float[]> captureRects(Canvas c) {
        List<float[]> rects = new ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            rects.add(new float[]{inv.getArgument(0), inv.getArgument(1), inv.getArgument(2),
                    inv.getArgument(3)});
            return null;
        }).when(c).drawRect(anyFloat(), anyFloat(), anyFloat(), anyFloat(), any(Paint.class));
        return rects;
    }

    @Test
    public void drawsVisibleCellsOnly_inGivenColor() {
        double[] cells = {
                50.5, 5.5, 50.6, 5.6,
                52.0, 5.0, 52.1, 5.1,
                50.0, 7.0, 50.1, 7.1,
        };
        ExploredTilesOverlay o = new ExploredTilesOverlay(cells, Color.RED);
        Canvas c = mock(Canvas.class);
        List<float[]> rects = captureRects(c);

        o.draw(c, map(), false);

        assertEquals(1, rects.size());
        assertEquals(500f, rects.get(0)[0], 0.5f);
        assertEquals(400f, rects.get(0)[1], 0.5f);
        assertEquals(600f, rects.get(0)[2], 0.5f);
        assertEquals(500f, rects.get(0)[3], 0.5f);
        ArgumentCaptor<Paint> paint = ArgumentCaptor.forClass(Paint.class);
        verify(c).drawRect(anyFloat(), anyFloat(), anyFloat(), anyFloat(), paint.capture());
        assertEquals(Color.RED, paint.getValue().getColor());
    }

    @Test
    public void subPixelCell_isDrawnAtLeastTwoPixels() {
        ExploredTilesOverlay o = new ExploredTilesOverlay(
                new double[]{50.5, 5.5, 50.5001, 5.5001}, Color.BLUE);
        Canvas c = mock(Canvas.class);
        List<float[]> rects = captureRects(c);

        o.draw(c, map(), false);

        assertEquals(2f, rects.get(0)[2] - rects.get(0)[0], 0.01f);
        assertEquals(2f, rects.get(0)[3] - rects.get(0)[1], 0.01f);
    }

    @Test
    public void shadowPassEmptyOrPartialCells_drawNothing() {
        Canvas c = mock(Canvas.class);
        new ExploredTilesOverlay(new double[]{50.5, 5.5, 50.6, 5.6}, Color.RED).draw(c, map(), true);
        new ExploredTilesOverlay(new double[0], Color.RED).draw(c, map(), false);
        new ExploredTilesOverlay(new double[]{50.5, 5.5, 50.6}, Color.RED).draw(c, map(), false);
        verify(c, never()).drawRect(anyFloat(), anyFloat(), anyFloat(), anyFloat(), any(Paint.class));
    }
}
