package nl.paree.climbpro.domain.poi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OverpassQueryBuilderTest {

    @Test
    public void shortRoute_queriesBothTagFamiliesAroundTheLine() {
        double[][] route = PoiFixtures.northboundRoute();
        String q = OverpassQueryBuilder.build(route[0], route[1], 300);
        assertTrue(q, q.startsWith("[out:json][timeout:60];"));
        assertTrue(q, q.contains("nwr[\"tourism\"~\"^(viewpoint|artwork|attraction)$\"](around:325,"));
        assertTrue(q, q.contains("nwr[\"historic\"~\"^(castle|ruins|monument|memorial)$\"](around:325,"));
        // A straight line collapses to its two end points.
        assertTrue(q, q.contains("(around:325,50.80000,5.70000,50.85000,5.70000)"));
        assertTrue(q, q.contains("out tags center 1000;"));
    }

    @Test
    public void longWigglyRoute_isBoundedAndRadiusWidenedByTolerance() {
        // 2 000 points zig-zagging ~280 m east-west while heading north (~220 km).
        int n = 2_000;
        double[] lats = new double[n];
        double[] lons = new double[n];
        for (int i = 0; i < n; i++) {
            lats[i] = 45.0 + 0.001 * i;
            lons[i] = 5.0 + ((i % 2 == 0) ? 0 : 0.004);
        }
        OverpassQueryBuilder.Polyline line = OverpassQueryBuilder.simplify(lats, lons);
        assertTrue(line.points.size() <= OverpassQueryBuilder.MAX_VERTICES);
        assertTrue(line.epsilonM > 25);

        String q = OverpassQueryBuilder.build(lats, lons, 300);
        Matcher m = Pattern.compile("\\(around:(\\d+),").matcher(q);
        assertTrue(m.find());
        assertEquals(Math.round(300 + line.epsilonM), Long.parseLong(m.group(1)));
    }

    @Test
    public void noCoordinates_givesNoQuery() {
        assertNull(OverpassQueryBuilder.build(null, null, 300));
        assertNull(OverpassQueryBuilder.build(new double[0], new double[0], 300));
        assertNull(OverpassQueryBuilder.build(new double[]{1}, new double[]{1, 2}, 300));
        assertNull(OverpassQueryBuilder.build(new double[]{Double.NaN}, new double[]{1}, 300));
    }

    @Test
    public void singlePoint_isAPlainAroundCircle() {
        String q = OverpassQueryBuilder.build(new double[]{50.8}, new double[]{5.7}, 300);
        assertTrue(q, q.contains("(around:325,50.80000,5.70000)"));
    }
}
