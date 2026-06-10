package nl.paree.climbpro.domain;

import nl.paree.climbpro.domain.climb.ClimbTrimmer;
import nl.paree.climbpro.domain.route.RoutePoint;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class ClimbTrimmerTest {

    /**
     * Build route points from segments. Each entry is [lengthMetres, gradientFraction].
     * Segments are joined continuously; elevation starts at 100 m, distance at 0.
     * 10 points per segment (so the step length is lengthMetres / 10).
     */
    private static List<RoutePoint> route(double[][] segs) {
        List<RoutePoint> pts = new ArrayList<>();
        double dist = 0.0;
        double ele = 100.0;
        pts.add(new RoutePoint(51.0, 5.0, ele, dist));
        for (double[] s : segs) {
            double len = s[0];
            double grad = s[1];
            int steps = 10;
            for (int i = 0; i < steps; i++) {
                dist += len / steps;
                ele  += (len / steps) * grad;
                pts.add(new RoutePoint(51.0, 5.0, ele, dist));
            }
        }
        return pts;
    }

    private static double len(List<RoutePoint> pts) {
        return pts.get(pts.size() - 1).distance - pts.get(0).distance;
    }

    @Test
    public void pureClimbIsNotTrimmed() {
        List<RoutePoint> in = route(new double[][]{{1200, 0.05}});
        List<RoutePoint> out = ClimbTrimmer.trim(in);
        assertEquals("no trim on a pure climb", in.size(), out.size());
        assertEquals(0.0, out.get(0).distance, 1e-6);
    }

    @Test
    public void trimsLeadingFalseFlat() {
        // 300 m at 1% (vals plat) then 1000 m at 6%
        List<RoutePoint> out = ClimbTrimmer.trim(route(new double[][]{{300, 0.01}, {1000, 0.06}}));
        assertTrue("leading flat removed", out.get(0).distance >= 250);
        assertTrue("remaining length ~1000 m", len(out) >= 950 && len(out) <= 1050);
    }

    @Test
    public void trimsTrailingFalseFlat() {
        // 1000 m at 6% then 300 m at 1.5% (vals plat)
        List<RoutePoint> out = ClimbTrimmer.trim(route(new double[][]{{1000, 0.06}, {300, 0.015}}));
        assertTrue("trailing flat removed", out.get(out.size() - 1).distance <= 1050);
        assertTrue("remaining length ~1000 m", len(out) >= 950 && len(out) <= 1050);
    }

    @Test
    public void shortLeadInBelowMinLengthIsKept() {
        // only 100 m of flat (< 200 m) — must NOT be trimmed
        List<RoutePoint> out = ClimbTrimmer.trim(route(new double[][]{{100, 0.01}, {1000, 0.06}}));
        assertEquals("short lead-in kept", 0.0, out.get(0).distance, 1e-6);
    }

    @Test
    public void trimIsSkippedWhenItWouldDropBelow800m() {
        // 300 m flat + only 700 m of climb: trimming the flat leaves 700 m < 800 m, so keep everything
        List<RoutePoint> out = ClimbTrimmer.trim(route(new double[][]{{300, 0.01}, {700, 0.06}}));
        assertEquals("no trim — would fall below 800 m", 0.0, out.get(0).distance, 1e-6);
        assertTrue("full length kept", len(out) >= 950);
    }

    @Test
    public void tooFewPointsReturnedUnchanged() {
        List<RoutePoint> in = new ArrayList<>();
        in.add(new RoutePoint(51.0, 5.0, 100.0, 0.0));
        assertEquals(in.size(), ClimbTrimmer.trim(in).size());
    }
}
