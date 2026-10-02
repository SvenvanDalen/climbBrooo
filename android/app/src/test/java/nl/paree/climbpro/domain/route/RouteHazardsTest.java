package nl.paree.climbpro.domain.route;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Issue #203: technical descents from geometry, OSM tunnels projected onto the route. */
public class RouteHazardsTest {

    private static final double M_PER_DEG = 111_195.0;
    private static final double COS = Math.cos(Math.toRadians(51.0));

    /** Points every 50 m along legs {dxM, dyM, gradient}. Returns {lats, lons, eles, dists}. */
    private static double[][] route(double[][] legs) {
        List<double[]> pts = new ArrayList<>();
        double x = 0, y = 0, e = 500, d = 0;
        pts.add(new double[]{y, x, e, d});
        for (double[] leg : legs) {
            double len = Math.hypot(leg[0], leg[1]);
            int steps = Math.max(1, (int) Math.round(len / 50));
            for (int s = 1; s <= steps; s++) {
                x += leg[0] / steps;
                y += leg[1] / steps;
                e += leg[2] * len / steps;
                d += len / steps;
                pts.add(new double[]{y, x, e, d});
            }
        }
        double[][] out = new double[4][pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            out[0][i] = 51.0 + pts.get(i)[0] / M_PER_DEG;
            out[1][i] = 5.0 + pts.get(i)[1] / (M_PER_DEG * COS);
            out[2][i] = pts.get(i)[2];
            out[3][i] = pts.get(i)[3];
        }
        return out;
    }

    private static List<RouteHazards.Hazard> descents(double[][] r) {
        return RouteHazards.detectDescents(r[0], r[1], r[2], r[3]);
    }

    @Test
    public void steepStraightDescentIsDetected() {
        double[][] r = route(new double[][]{{0, 2000, 0}, {0, 1000, -0.10}, {0, 2000, 0}});
        List<RouteHazards.Hazard> h = descents(r);
        assertEquals(1, h.size());
        assertEquals(RouteHazards.TYPE_DESCENT, h.get(0).type);
        assertTrue(h.get(0).startM >= 1800 && h.get(0).startM <= 2050);
        assertTrue(h.get(0).endM >= 2950 && h.get(0).endM <= 3200);
    }

    @Test
    public void moderateStraightDescentIsNotTechnical() {
        assertTrue(descents(route(new double[][]{{0, 3000, -0.06}})).isEmpty());
    }

    @Test
    public void moderateDescentWithHairpinsIsTechnical() {
        // Zigzag of 100 m legs at -6 %: a hairpin (≈180°) every 100 m.
        double[][] legs = new double[20][];
        for (int i = 0; i < legs.length; i++) {
            legs[i] = new double[]{i % 2 == 0 ? 100 : -100, 10, -0.06};
        }
        assertEquals(1, descents(route(legs)).size());
    }

    @Test
    public void climbsAndFlatAreIgnored() {
        assertTrue(descents(route(new double[][]{{0, 2000, 0.10}, {0, 2000, 0}})).isEmpty());
    }

    @Test
    public void missingElevationYieldsNothing() {
        double[][] r = route(new double[][]{{0, 2000, -0.10}});
        Arrays.fill(r[2], Double.NaN);
        assertTrue(descents(r).isEmpty());
        assertTrue(RouteHazards.detectDescents(null, null, null, null).isEmpty());
    }

    @Test
    public void tunnelIsProjectedOntoTheRoute() {
        double[][] r = route(new double[][]{{0, 5000, 0}});
        // Tunnel along the road from y=1200 to y=1500, offset 8 m sideways, nodes every 150 m.
        double[][] way = {
                {51.0 + 1200 / M_PER_DEG, 5.0 + 8 / (M_PER_DEG * COS)},
                {51.0 + 1350 / M_PER_DEG, 5.0 + 8 / (M_PER_DEG * COS)},
                {51.0 + 1500 / M_PER_DEG, 5.0 + 8 / (M_PER_DEG * COS)}};
        List<RouteHazards.Hazard> t = RouteHazards.matchTunnels(r[0], r[1], r[3],
                Collections.singletonList(way));
        assertEquals(1, t.size());
        assertEquals(RouteHazards.TYPE_TUNNEL, t.get(0).type);
        assertEquals(1200, t.get(0).startM, 2);
        assertEquals(1500, t.get(0).endM, 2);
    }

    @Test
    public void tunnelAwayFromTheRouteIsIgnored() {
        double[][] r = route(new double[][]{{0, 5000, 0}});
        double[][] way = {
                {51.0 + 1200 / M_PER_DEG, 5.0 + 300 / (M_PER_DEG * COS)},
                {51.0 + 1500 / M_PER_DEG, 5.0 + 300 / (M_PER_DEG * COS)}};
        assertTrue(RouteHazards.matchTunnels(r[0], r[1], r[3],
                Collections.singletonList(way)).isEmpty());
    }

    @Test
    public void packSortsAndCaps() {
        List<RouteHazards.Hazard> h = new ArrayList<>();
        h.add(new RouteHazards.Hazard(5000, 6000, RouteHazards.TYPE_DESCENT));
        h.add(new RouteHazards.Hazard(1200, 1450, RouteHazards.TYPE_TUNNEL));
        assertArrayEquals(new int[]{1200, 1450, 0, 5000, 6000, 1}, RouteHazards.pack(h));
        assertNull(RouteHazards.pack(new ArrayList<>()));

        List<RouteHazards.Hazard> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) many.add(new RouteHazards.Hazard(i * 1000, i * 1000 + 100, 0));
        assertEquals(RouteHazards.MAX_WIRE_HAZARDS * 3, RouteHazards.pack(many).length);
    }

    @Test
    public void nearRangesAreMerged() {
        List<int[]> merged = RouteHazards.mergeRanges(Arrays.asList(
                new int[]{1000, 1300}, new int[]{1400, 1700}, new int[]{3000, 3200}));
        assertEquals(2, merged.size());
        assertArrayEquals(new int[]{1000, 1700}, merged.get(0));
    }
}
