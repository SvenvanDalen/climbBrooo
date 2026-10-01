package nl.paree.climbpro.domain.route;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Issue #205: shorter variants of a route within its own geometry. */
public class RouteShortenerTest {

    private static final double M_PER_DEG = 111_195.0;
    private static final double STEP_M = 50.0;

    /** Builds points along a path of (dxM, dyM) legs, each sampled every 50 m. */
    private static List<RoutePoint> path(double[][] legs, double[] gradients) {
        List<RoutePoint> raw = new ArrayList<>();
        double x = 0, y = 0, ele = 100;
        raw.add(point(x, y, ele));
        for (int l = 0; l < legs.length; l++) {
            double len = Math.hypot(legs[l][0], legs[l][1]);
            int steps = (int) Math.round(len / STEP_M);
            for (int s = 1; s <= steps; s++) {
                x += legs[l][0] / steps;
                y += legs[l][1] / steps;
                ele += gradients[l] * len / steps;
                raw.add(point(x, y, ele));
            }
        }
        return CumulativeDistance.compute(raw);
    }

    private static RoutePoint point(double xM, double yM, double ele) {
        return new RoutePoint(51.0 + yM / M_PER_DEG,
                5.0 + xM / (M_PER_DEG * Math.cos(Math.toRadians(51.0))), ele, 0);
    }

    private static double[][] arrays(List<RoutePoint> pts) {
        int n = pts.size();
        double[][] a = new double[4][n];
        for (int i = 0; i < n; i++) {
            a[0][i] = pts.get(i).lat;
            a[1][i] = pts.get(i).lon;
            a[2][i] = pts.get(i).elevation;
            a[3][i] = pts.get(i).distance;
        }
        return a;
    }

    /**
     * Figure eight through the origin: east lobe (5 km, flat), then north lobe with a climb
     * (8 km), then back to the start along the first lobe's return leg.
     */
    private static List<RoutePoint> figureEight() {
        return path(new double[][]{
                {1000, 0}, {0, 1500}, {-1000, 0}, {0, -1500},   // east lobe, back at origin
                {0, -0}, {-1500, 0}, {0, 2500}, {1500, 0}, {0, -2500} // west lobe with climb
        }, new double[]{0, 0, 0, 0, 0, 0, 0.05, 0, -0.05});
    }

    @Test
    public void figureEightSuggestsSkippingTheClimbLobe() {
        double[][] a = arrays(figureEight());
        double total = a[3][a[3].length - 1];
        // Climb on the 2.5 km northbound leg of the west lobe: 6.5 km .. 9 km along the route.
        int[][] climbs = {{6500, 9000}};
        List<RouteShortener.Variant> v = RouteShortener.suggest(a[0], a[1], a[2], a[3], climbs, 5);
        assertTrue("expected a suggestion", !v.isEmpty());
        RouteShortener.Variant best = v.get(0);
        assertEquals(total - best.savedM, best.newLengthM, 1e-6);
        assertTrue(best.savedM >= 7_000);
        assertTrue(best.skippedClimbs.contains(0));
        assertTrue(best.savedGainM > 100);
        assertTrue(best.connectorM <= RouteShortener.JOIN_RADIUS_M);
    }

    @Test
    public void straightRouteHasNoShortcut() {
        double[][] a = arrays(path(new double[][]{{0, 10_000}}, new double[]{0.01}));
        assertTrue(RouteShortener.suggest(a[0], a[1], a[2], a[3], null, 5).isEmpty());
    }

    @Test
    public void outAndBackSuggestsTurningEarlierWithoutDuplicates() {
        // 10 km out, 10 km back on a parallel road 30 m away.
        double[][] a = arrays(path(new double[][]{{0, 10_000}, {30, 0}, {0, -10_000}},
                new double[]{0, 0, 0}));
        List<RouteShortener.Variant> v = RouteShortener.suggest(a[0], a[1], a[2], a[3], null, 20);
        assertTrue(v.size() > 1);
        for (int p = 0; p < v.size(); p++) {
            RouteShortener.Variant x = v.get(p);
            assertTrue(x.newLengthM >= RouteShortener.MIN_REMAINING_M);
            assertTrue(x.savedM >= RouteShortener.MIN_SAVING_M);
            if (p > 0) assertTrue(v.get(p - 1).savedM >= x.savedM);
            for (int q = 0; q < p; q++) {
                RouteShortener.Variant y = v.get(q);
                assertTrue(Math.abs(x.fromDistanceM - y.fromDistanceM)
                        >= RouteShortener.DEDUP_ALONG_M
                        || Math.abs(x.toDistanceM - y.toDistanceM)
                        >= RouteShortener.DEDUP_ALONG_M);
            }
        }
    }

    @Test
    public void respectsMax() {
        double[][] a = arrays(path(new double[][]{{0, 10_000}, {30, 0}, {0, -10_000}},
                new double[]{0, 0, 0}));
        assertEquals(2, RouteShortener.suggest(a[0], a[1], a[2], a[3], null, 2).size());
    }

    @Test
    public void applyJoinsAndRecomputesDistances() {
        List<RoutePoint> pts = figureEight();
        double[][] a = arrays(pts);
        RouteShortener.Variant best = RouteShortener.suggest(a[0], a[1], a[2], a[3], null, 1).get(0);
        List<RoutePoint> shortened = RouteShortener.apply(pts, best.fromIndex, best.toIndex);
        assertEquals(pts.size() - (best.toIndex - best.fromIndex - 1), shortened.size());
        assertEquals(best.newLengthM, shortened.get(shortened.size() - 1).distance, 1.0);
        assertEquals(0.0, shortened.get(0).distance, 0.0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void applyRejectsInvalidIndices() {
        RouteShortener.apply(figureEight(), 5, 5);
    }

    @Test
    public void degenerateInputYieldsNothing() {
        assertTrue(RouteShortener.suggest(null, null, null, null, null, 5).isEmpty());
        assertTrue(RouteShortener.suggest(new double[]{51}, new double[]{5}, null,
                new double[]{0}, null, 5).isEmpty());
    }
}
