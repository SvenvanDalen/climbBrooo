package nl.paree.climbpro.ui.routes;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Rect;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.weather.RadarTiles;
import nl.paree.climbpro.domain.weather.TemperatureTrend;
import nl.paree.climbpro.ui.Construct;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.osmdroid.api.IGeoPoint;
import org.osmdroid.views.Projection;
import org.robolectric.RobolectricTestRunner;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class RouteViewsTest {

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

    // --- RouteElevationProfileView ---

    private static RouteElevationProfile profile(double[] d, double[] e, double min, double max,
                                                 double total, List<RouteElevationProfile.Band> bands) {
        return Construct.of(RouteElevationProfile.class, d, e, min, max, total, bands);
    }

    @Test
    public void elevation_setProfile_describesRouteAndCountsClimbs() {
        RouteElevationProfileView v = new RouteElevationProfileView(ctx);
        List<RouteElevationProfile.Band> bands = Arrays.asList(
                new RouteElevationProfile.Band(1000, 1500, 2, 0),
                new RouteElevationProfile.Band(1500, 2000, 4, 0),
                new RouteElevationProfile.Band(5000, 6000, 3, 1));
        v.setProfile(profile(new double[]{0, 3000, 8000}, new double[]{100, 250, 180}, 100, 250,
                8000, bands));

        assertEquals(ctx.getString(nl.paree.climbpro.R.string.route_profile_description,
                8.0, 100L, 250L, 2), v.getContentDescription().toString());
    }

    @Test
    public void elevation_nullOrEmpty_hasEmptyDescriptionAndDrawsNothing() {
        RouteElevationProfileView v = new RouteElevationProfileView(ctx, null);
        v.setProfile(null);
        assertEquals(ctx.getString(nl.paree.climbpro.R.string.route_profile_empty),
                v.getContentDescription().toString());
        layout(v, 400, 140);
        Canvas c = mock(Canvas.class);
        v.onDraw(c);
        v.setProfile(RouteElevationProfile.from(new StoredRoute(), 10));
        v.onDraw(c);
        verify(c, never()).drawPath(any(Path.class), any(Paint.class));
    }

    @Test
    public void elevation_drawsAreaBandsAndLabels() {
        RouteElevationProfileView v = new RouteElevationProfileView(ctx, null, 0);
        List<RouteElevationProfile.Band> bands = Arrays.asList(
                new RouteElevationProfile.Band(1000, 1500, 2, 0),
                new RouteElevationProfile.Band(5000, 5000, 5, 1));
        v.setProfile(profile(new double[]{0, 3000, 8000}, new double[]{100, 250, 180}, 100, 250,
                8000, bands));
        layout(v, 400, 140);
        Canvas c = mock(Canvas.class);

        v.onDraw(c);

        // Base fill, two bands, outline.
        verify(c, times(4)).drawPath(any(Path.class), any(Paint.class));
        verify(c, times(2)).clipRect(anyFloat(), anyFloat(), anyFloat(), anyFloat());
        assertEquals(Arrays.asList("250 m", "100 m", "8.0 km", "0"), texts(c));
    }

    @Test
    public void elevation_offsetStart_labelsStartDistance_andFlatRouteIsPadded() {
        RouteElevationProfileView v = new RouteElevationProfileView(ctx);
        v.setProfile(profile(new double[]{2500, 4000}, new double[]{50, 50}, 50, 50, 4000,
                Collections.emptyList()));
        layout(v, 400, 140);
        Canvas c = mock(Canvas.class);

        v.onDraw(c);

        assertEquals(Arrays.asList("50 m", "50 m", "4.0 km", "2.5 km"), texts(c));
    }

    @Test
    public void elevation_tooSmallToDraw_isSkipped() {
        RouteElevationProfileView v = new RouteElevationProfileView(ctx);
        v.setProfile(profile(new double[]{0, 1000}, new double[]{0, 10}, 0, 10, 1000,
                Collections.emptyList()));
        layout(v, 40, 30);
        Canvas c = mock(Canvas.class);
        v.onDraw(c);
        verify(c, never()).drawPath(any(Path.class), any(Paint.class));
    }

    @Test
    public void elevation_measureModes() {
        RouteElevationProfileView v = new RouteElevationProfileView(ctx);
        v.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.UNSPECIFIED);
        assertEquals(140, v.getMeasuredHeight());
        v.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(90, View.MeasureSpec.AT_MOST));
        assertEquals(90, v.getMeasuredHeight());
    }

    // --- TemperatureTrendView ---

    private static TemperatureTrend trend(TemperatureTrend.Point... pts) {
        return Construct.of(TemperatureTrend.class, new ArrayList<>(Arrays.asList(pts)), 0);
    }

    private static final Instant T0 = Instant.parse("2026-06-01T08:00:00Z");

    @Test
    public void temperature_describesRangeAndDrawsLabels() {
        TemperatureTrendView v = new TemperatureTrendView(ctx);
        v.setTrend(trend(new TemperatureTrend.Point(0, T0, 12.4),
                new TemperatureTrend.Point(20_000, T0.plusSeconds(3600), 18.6),
                new TemperatureTrend.Point(40_000, T0.plusSeconds(7200), 15.0)), ZoneOffset.UTC);
        assertEquals("Verwachte temperatuur tussen 12 en 19 graden",
                v.getContentDescription().toString());
        layout(v, 400, 140);
        Canvas c = mock(Canvas.class);

        v.onDraw(c);

        verify(c, times(3)).drawCircle(anyFloat(), anyFloat(), anyFloat(), any(Paint.class));
        verify(c).drawPath(any(Path.class), any(Paint.class));
        assertEquals(Arrays.asList("19°", "12°", "40 km · 10:00", "0 km · 08:00"), texts(c));
    }

    @Test
    public void temperature_steadyForecast_usesFourDegreeWindow_singlePointCentred() {
        TemperatureTrendView v = new TemperatureTrendView(ctx, null);
        v.setTrend(trend(new TemperatureTrend.Point(5000, T0, 20)), ZoneId.of("Europe/Amsterdam"));
        layout(v, 400, 140);
        Canvas c = mock(Canvas.class);
        ArgumentCaptor<Float> xs = ArgumentCaptor.forClass(Float.class);

        v.onDraw(c);

        verify(c).drawCircle(xs.capture(), anyFloat(), anyFloat(), any(Paint.class));
        float d = ctx.getResources().getDisplayMetrics().density;
        float left = 44 * d;
        float right = 400 - 16 * d;
        assertEquals(left + (right - left) / 2, xs.getValue(), 0.01f);
        assertEquals(Arrays.asList("22°", "18°", "5 km · 10:00", "5 km · 10:00"), texts(c));
    }

    @Test
    public void temperature_nullTrend_drawsNothing() {
        TemperatureTrendView v = new TemperatureTrendView(ctx, null, 0);
        v.setTrend(null, ZoneOffset.UTC);
        assertEquals("Geen temperatuurverwachting", v.getContentDescription().toString());
        layout(v, 400, 140);
        Canvas c = mock(Canvas.class);
        v.onDraw(c);
        layout(v, 40, 30);
        v.setTrend(trend(new TemperatureTrend.Point(0, T0, 10),
                new TemperatureTrend.Point(10, T0, 11)), ZoneOffset.UTC);
        v.onDraw(c);
        verify(c, never()).drawLine(anyFloat(), anyFloat(), anyFloat(), anyFloat(), any(Paint.class));
        v.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(77, View.MeasureSpec.EXACTLY));
        assertEquals(77, v.getMeasuredHeight());
        v.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(60, View.MeasureSpec.AT_MOST));
        assertEquals(60, v.getMeasuredHeight());
    }

    // --- RainRadarOverlay ---

    @Test
    public void radar_stretchesEachTileOverItsProjectedBounds() {
        RadarTiles.Tile a = Construct.of(RadarTiles.Tile.class, 6, 33, 21);
        RadarTiles.Tile b = Construct.of(RadarTiles.Tile.class, 6, 34, 21);
        Bitmap bmA = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
        Bitmap bmB = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
        // A third tile without a bitmap is ignored.
        RadarTiles.Tile c3 = Construct.of(RadarTiles.Tile.class, 6, 35, 21);
        RainRadarOverlay o = new RainRadarOverlay(Arrays.asList(a, b, c3), Arrays.asList(bmA, bmB));
        Projection p = mock(Projection.class);
        when(p.toPixels(any(IGeoPoint.class), any(Point.class))).thenAnswer(inv -> {
            IGeoPoint g = inv.getArgument(0);
            Point out = inv.getArgument(1);
            out.x = (int) Math.round(g.getLongitude() * 10);
            out.y = (int) Math.round(-g.getLatitude() * 10);
            return out;
        });
        Canvas canvas = mock(Canvas.class);
        List<Rect> dst = new ArrayList<>();
        List<Bitmap> bitmaps = new ArrayList<>();
        List<Integer> alphas = new ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            bitmaps.add(inv.getArgument(0));
            dst.add(new Rect((Rect) inv.getArgument(2)));
            alphas.add(((Paint) inv.getArgument(3)).getAlpha());
            return null;
        }).when(canvas).drawBitmap(any(Bitmap.class), isNull(), any(Rect.class), any(Paint.class));

        o.draw(canvas, p);

        assertEquals(Arrays.asList(bmA, bmB), bitmaps);
        assertEquals((int) Math.round(a.west * 10), dst.get(0).left);
        assertEquals((int) Math.round(-a.north * 10), dst.get(0).top);
        assertEquals((int) Math.round(a.east * 10), dst.get(0).right);
        assertEquals((int) Math.round(-a.south * 10), dst.get(0).bottom);
        assertEquals(Arrays.asList(160, 160), alphas);
    }
}
