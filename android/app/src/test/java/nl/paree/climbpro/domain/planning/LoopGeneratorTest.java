package nl.paree.climbpro.domain.planning;

import nl.paree.climbpro.domain.route.CumulativeDistance;
import nl.paree.climbpro.domain.route.RoutePoint;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Issue #202: loops of about X km from saved route geometry only. */
public class LoopGeneratorTest {

    private static final double M_PER_DEG = 111_195.0;
    private static final double LAT0 = 51.0, LON0 = 5.0;

    /** Path through (x, y) metre corners relative to the origin, sampled every 50 m. */
    private static List<RoutePoint> path(double[][] corners) {
        List<RoutePoint> raw = new ArrayList<>();
        raw.add(pt(corners[0][0], corners[0][1]));
        for (int c = 1; c < corners.length; c++) {
            double dx = corners[c][0] - corners[c - 1][0];
            double dy = corners[c][1] - corners[c - 1][1];
            int steps = Math.max(1, (int) Math.round(Math.hypot(dx, dy) / 50));
            for (int s = 1; s <= steps; s++) {
                raw.add(pt(corners[c - 1][0] + dx * s / steps, corners[c - 1][1] + dy * s / steps));
            }
        }
        return CumulativeDistance.compute(raw);
    }

    private static RoutePoint pt(double x, double y) {
        return new RoutePoint(LAT0 + y / M_PER_DEG,
                LON0 + x / (M_PER_DEG * Math.cos(Math.toRadians(LAT0))), 10 + y / 100, 0);
    }

    /** Square loop of side {@code side} m whose corner (offset, 0) is closest to the origin. */
    private static List<RoutePoint> square(double offset, double side) {
        return path(new double[][]{{offset + side, 0}, {offset + side, side}, {offset, side},
                {offset, 0}, {offset + side, 0}});
    }

    private static LoopGenerator.Source src(String id, List<RoutePoint> pts) {
        return new LoopGenerator.Source(id, "Route " + id, pts);
    }

    private static double length(List<RoutePoint> pts) {
        return pts.get(pts.size() - 1).distance;
    }

    @Test
    public void matchingLoopNearStartIsTopSuggestionAndStartsAndEndsAtStart() {
        List<LoopGenerator.Source> sources = Collections.singletonList(src("a", square(100, 5000)));
        List<LoopGenerator.Suggestion> s = LoopGenerator.suggest(LAT0, LON0, 20_000, sources, 5);

        assertEquals(LoopGenerator.Kind.LOOP, s.get(0).kind);
        assertEquals(20_200, s.get(0).lengthM, 5);
        List<RoutePoint> pts = s.get(0).points();
        assertEquals(LAT0, pts.get(0).lat, 1e-9);
        assertEquals(LAT0, pts.get(pts.size() - 1).lat, 1e-9);
        assertEquals(s.get(0).lengthM, length(pts), 5);
    }

    @Test
    public void loopIsRestartedAtThePointNearestTheStart() {
        // Loop's own start is the far corner; the start point lies next to its middle.
        List<RoutePoint> loop = path(new double[][]{{5000, 5000}, {0, 5000}, {0, 0}, {5000, 0},
                {5000, 5000}});
        LoopGenerator.Suggestion s = LoopGenerator.suggest(LAT0, LON0 - 0.001, 20_000,
                Collections.singletonList(src("a", loop)), 1).get(0);
        assertEquals(LoopGenerator.Kind.LOOP, s.kind);
        assertTrue("approach should be short", s.approachM < 200);
    }

    @Test
    public void routesFarFromStartAreIgnored() {
        List<LoopGenerator.Source> sources = Collections.singletonList(src("far", square(5000, 5000)));
        assertTrue(LoopGenerator.suggest(LAT0, LON0, 20_000, sources, 5).isEmpty());
    }

    @Test
    public void twoShortLoopsAreCombined() {
        List<LoopGenerator.Source> sources = Arrays.asList(
                src("a", square(50, 2500)), src("b", square(-2550, 2500)));
        List<LoopGenerator.Suggestion> s = LoopGenerator.suggest(LAT0, LON0, 20_000, sources, 5);
        LoopGenerator.Suggestion top = s.get(0);
        assertEquals(LoopGenerator.Kind.COMBINED, top.kind);
        assertEquals(Arrays.asList("a", "b"), top.routeIds);
        assertEquals(top.lengthM, length(top.points()), 5);
    }

    @Test
    public void straightRouteGivesOutAndBackOfTargetLength() {
        List<LoopGenerator.Source> sources = Collections.singletonList(
                src("line", path(new double[][]{{0, 100}, {0, 30_000}})));
        List<LoopGenerator.Suggestion> s = LoopGenerator.suggest(LAT0, LON0, 20_000, sources, 5);
        assertEquals(1, s.size());
        assertEquals(LoopGenerator.Kind.OUT_AND_BACK, s.get(0).kind);
        assertEquals(20_000, s.get(0).lengthM, 120);
        List<RoutePoint> pts = s.get(0).points();
        assertEquals(s.get(0).lengthM, length(pts), 5);
        assertEquals(LAT0, pts.get(pts.size() - 1).lat, 1e-9);
    }

    @Test
    public void outAndBackGoesBackwardWhenTheRouteEndsNearStart() {
        List<LoopGenerator.Source> sources = Collections.singletonList(
                src("line", path(new double[][]{{0, 30_000}, {0, 100}})));
        LoopGenerator.Suggestion s = LoopGenerator.suggest(LAT0, LON0, 20_000, sources, 5).get(0);
        assertEquals(20_000, s.lengthM, 120);
    }

    @Test
    public void realLoopBeatsExactOutAndBackWithinPenalty() {
        List<LoopGenerator.Source> sources = Arrays.asList(
                src("loop", square(100, 5200)), // ≈ 21 km incl. approach, 5 % off
                src("line", path(new double[][]{{-100, 0}, {-100, -30_000}})));
        List<LoopGenerator.Suggestion> s = LoopGenerator.suggest(LAT0, LON0, 20_000, sources, 5);
        assertEquals(LoopGenerator.Kind.LOOP, s.get(0).kind);
        assertEquals(LoopGenerator.Kind.OUT_AND_BACK, s.get(1).kind);
    }

    @Test
    public void tooLongLoopWithShortcutIsShortened() {
        // Figure eight through the origin: two 10 km lobes; target ≈ one lobe.
        List<RoutePoint> eight = path(new double[][]{{0, 0}, {2500, 0}, {2500, 2500}, {0, 2500},
                {0, 0}, {-2500, 0}, {-2500, -2500}, {0, -2500}, {0, 0}});
        List<LoopGenerator.Suggestion> s = LoopGenerator.suggest(LAT0, LON0, 10_000,
                Collections.singletonList(src("eight", eight)), 5);
        LoopGenerator.Suggestion shortened = null;
        for (LoopGenerator.Suggestion x : s) {
            if (x.kind == LoopGenerator.Kind.SHORTENED_LOOP) shortened = x;
        }
        assertTrue(shortened != null);
        assertEquals(10_000, shortened.lengthM, 500);
        assertEquals(shortened.lengthM, length(shortened.points()), 5);
    }

    @Test
    public void suggestionsOutsideToleranceAreDropped() {
        List<LoopGenerator.Source> sources = Collections.singletonList(src("a", square(100, 1000)));
        assertTrue(LoopGenerator.suggest(LAT0, LON0, 50_000, sources, 5).isEmpty());
    }

    @Test
    public void invalidInputYieldsNothing() {
        assertTrue(LoopGenerator.suggest(LAT0, LON0, 0, new ArrayList<>(), 5).isEmpty());
        assertTrue(LoopGenerator.suggest(LAT0, LON0, 10_000, null, 5).isEmpty());
    }
}
