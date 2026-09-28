package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class DescentAnalyzerTest {

    private static final double STEP_M = 25;
    private static final double M_PER_DEG_LAT = 111_195.0;

    /** Builds a route leg by leg, a point every 25 m. */
    private static final class Builder {
        final List<double[]> pts = new ArrayList<>(); // lat, lon, ele, dist
        final List<StoredClimb> climbs = new ArrayList<>();

        Builder() {
            pts.add(new double[]{45.0, 6.0, 1000, 0});
        }

        /** {@code gradient} as a fraction, positive uphill; heading in degrees from north. */
        Builder leg(double meters, double gradient, double headingDeg) {
            int steps = (int) Math.round(meters / STEP_M);
            for (int i = 0; i < steps; i++) {
                double[] p = pts.get(pts.size() - 1);
                double h = Math.toRadians(headingDeg);
                double dLat = STEP_M * Math.cos(h) / M_PER_DEG_LAT;
                double dLon = STEP_M * Math.sin(h) / (M_PER_DEG_LAT * Math.cos(Math.toRadians(p[0])));
                pts.add(new double[]{p[0] + dLat, p[1] + dLon, p[2] + gradient * STEP_M,
                        p[3] + STEP_M});
            }
            return this;
        }

        Builder climb(int start, int end) {
            StoredClimb c = new StoredClimb();
            c.startDistance = start;
            c.endDistance = end;
            climbs.add(c);
            return this;
        }

        StoredRoute build() {
            StoredRoute r = new StoredRoute();
            int n = pts.size();
            r.lats = new double[n];
            r.lons = new double[n];
            r.elevations = new double[n];
            r.distances = new double[n];
            for (int i = 0; i < n; i++) {
                double[] p = pts.get(i);
                r.lats[i] = p[0];
                r.lons[i] = p[1];
                r.elevations[i] = p[2];
                r.distances[i] = p[3];
            }
            r.climbs = climbs;
            return r;
        }
    }

    @Test
    public void straightDescentEndsAtRiseAndDropsTheRunOut() {
        StoredRoute r = new Builder()
                .leg(3000, 0.06, 0)
                .leg(3000, -0.08, 0)
                .leg(1000, -0.005, 0)  // valley run-out
                .leg(500, 0.05, 0)     // road rises again
                .climb(0, 3000).build();
        DescentAnalyzer.Descent d = DescentAnalyzer.analyze(r, 0);

        assertNotNull(d);
        assertEquals(3000, d.lengthM);
        assertEquals(240, d.dropM);
        assertEquals(0.08, d.avgGradient, 1e-6);
        assertEquals(0.08, d.maxGradient, 1e-6);
        assertEquals(0, d.hairpins);
        assertEquals(DescentAnalyzer.Twistiness.STRAIGHT, d.twistiness);
        assertEquals(DescentAnalyzer.End.RISE, d.end);
    }

    @Test
    public void labelSummarisesTheDescent() {
        StoredRoute r = new Builder()
                .leg(3000, 0.06, 0)
                .leg(3000, -0.08, 0)
                .climb(0, 3000).build();
        assertEquals("Afdaling na de top: 3,0 km · 240 m omlaag · gem. 8,0 % · max 8,0 % · "
                        + "vrij recht (tot het einde van de route)",
                DescentLabel.format(DescentAnalyzer.analyze(r, 0)));
        assertEquals("Afdaling na de top: op deze route volgt geen noemenswaardige afdaling.",
                DescentLabel.format(null));
    }

    @Test
    public void summitPlateauIsTrimmedAndSteepestStretchFound() {
        StoredRoute r = new Builder()
                .leg(3000, 0.06, 0)
                .leg(400, 0, 0)        // plateau at the top
                .leg(1000, -0.05, 0)
                .leg(200, -0.15, 0)    // steep ramp
                .leg(1000, -0.05, 0)
                .climb(0, 3000).build();
        DescentAnalyzer.Descent d = DescentAnalyzer.analyze(r, 0);

        assertEquals(2200, d.lengthM);
        assertEquals(130, d.dropM);
        assertEquals(0.15, d.maxGradient, 1e-6);
        assertEquals(DescentAnalyzer.End.ROUTE_END, d.end);
    }

    @Test
    public void stopsBeforeTheNextClimb() {
        StoredRoute r = new Builder()
                .leg(2000, 0.06, 0)
                .leg(1500, -0.06, 0)
                .leg(1500, -0.01, 0)   // gentle, but not a 15 m rise
                .leg(2000, 0.07, 0)
                .climb(0, 2000).climb(5000, 7000).build();
        DescentAnalyzer.Descent d = DescentAnalyzer.analyze(r, 0);
        assertEquals(1500, d.lengthM);
        assertEquals(DescentAnalyzer.End.NEXT_CLIMB, d.end);
    }

    @Test
    public void countsHairpinsButNotSBends() {
        Builder b = new Builder().leg(2000, 0.07, 0).climb(0, 2000);
        // Six switchbacks: 300 m one way, a 25 m bend, 300 m back.
        double heading = 90;
        for (int i = 0; i < 6; i++) {
            b.leg(300, -0.08, heading);
            b.leg(25, -0.08, 0);
            heading = heading == 90 ? 270 : 90;
        }
        DescentAnalyzer.Descent d = DescentAnalyzer.analyze(b.build(), 0);
        assertEquals(5, d.hairpins);
        assertEquals(DescentAnalyzer.Twistiness.VERY_TWISTY, d.twistiness);

        // S-bends: small alternating kinks don't add up to a hairpin.
        Builder s = new Builder().leg(2000, 0.07, 0).climb(0, 2000);
        for (int i = 0; i < 20; i++) s.leg(100, -0.06, i % 2 == 0 ? 20 : -20);
        DescentAnalyzer.Descent sd = DescentAnalyzer.analyze(s.build(), 0);
        assertEquals(0, sd.hairpins);
    }

    @Test
    public void noNotableDescent() {
        // Route ends at the top.
        assertNull(DescentAnalyzer.analyze(new Builder().leg(2000, 0.06, 0)
                .climb(0, 2000).build(), 0));
        // Only a 20 m dip before the road climbs again.
        assertNull(DescentAnalyzer.analyze(new Builder().leg(2000, 0.06, 0)
                .leg(400, -0.05, 0).leg(1000, 0.04, 0).climb(0, 2000).build(), 0));
        assertNull(DescentAnalyzer.analyze(null, 0));
        assertNull(DescentAnalyzer.analyze(new Builder().leg(2000, 0.06, 0)
                .climb(0, 2000).build(), 3));
    }
}
