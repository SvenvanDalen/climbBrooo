package nl.paree.climbpro.domain.poi;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Vertex budget of the POI query for long and pathological routes. */
public class OverpassQueryBuilderLongRouteTest {

    @Test
    public void zigZagRoute_isThinnedToTheVertexBudget_keepingBothEnds() {
        int n = 3_000;
        double[] lats = new double[n];
        double[] lons = new double[n];
        for (int i = 0; i < n; i++) {
            lats[i] = 45 + i * 0.001;
            lons[i] = 6 + (i % 2 == 0 ? 0.1 : -0.1); // ~16 km swings: survive every epsilon
        }

        OverpassQueryBuilder.Polyline p = OverpassQueryBuilder.simplify(lats, lons);

        assertEquals(OverpassQueryBuilder.MAX_VERTICES, p.points.size());
        assertEquals(1600, p.epsilonM, 0);
        assertArrayEquals(new double[]{lats[0], lons[0]}, p.points.get(0), 0);
        assertArrayEquals(new double[]{lats[n - 1], lons[n - 1]},
                p.points.get(p.points.size() - 1), 0);
    }

    @Test
    public void unusableInput_givesNoPolyline() {
        assertNull(OverpassQueryBuilder.simplify(null, new double[0]));
        assertNull(OverpassQueryBuilder.simplify(new double[]{1}, new double[]{1, 2}));
        assertNull(OverpassQueryBuilder.simplify(new double[]{Double.NaN}, new double[]{5}));
    }
}
