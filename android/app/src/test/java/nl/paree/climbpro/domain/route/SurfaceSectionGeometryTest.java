package nl.paree.climbpro.domain.route;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SurfaceSectionGeometryTest {

    // Rechte as: 0..4000 m, lat 51.0..51.4, lon 5.0..5.4 (vertex elke 1000 m).
    private static final double[] DIST = {0, 1000, 2000, 3000, 4000};
    private static final double[] LAT  = {51.0, 51.1, 51.2, 51.3, 51.4};
    private static final double[] LON  = {5.0, 5.1, 5.2, 5.3, 5.4};

    @Test
    public void returnsVerticesInRange_inOrder() {
        List<double[]> pts = SurfaceSectionGeometry.pointsBetween(DIST, LAT, LON, 1000, 3000);
        assertEquals(3, pts.size());
        assertEquals(51.1, pts.get(0)[0], 1e-9);
        assertEquals(5.1,  pts.get(0)[1], 1e-9);
        assertEquals(51.3, pts.get(2)[0], 1e-9);
        assertEquals(5.3,  pts.get(2)[1], 1e-9);
    }

    @Test
    public void clampsToRouteBounds() {
        List<double[]> pts = SurfaceSectionGeometry.pointsBetween(DIST, LAT, LON, -500, 9000);
        assertEquals("alle vertices, geclamped op 0..4000", 5, pts.size());
    }

    @Test
    public void nullArrays_returnEmpty() {
        assertTrue(SurfaceSectionGeometry.pointsBetween(null, LAT, LON, 0, 1000).isEmpty());
        assertTrue(SurfaceSectionGeometry.pointsBetween(DIST, null, LON, 0, 1000).isEmpty());
        assertTrue(SurfaceSectionGeometry.pointsBetween(DIST, LAT, null, 0, 1000).isEmpty());
    }

    @Test
    public void inconsistentLengths_returnEmpty() {
        double[] shortLat = {51.0, 51.1};
        assertTrue(SurfaceSectionGeometry.pointsBetween(DIST, shortLat, LON, 0, 1000).isEmpty());
    }

    @Test
    public void emptyArrays_returnEmpty() {
        assertTrue(SurfaceSectionGeometry.pointsBetween(
                new double[0], new double[0], new double[0], 0, 1000).isEmpty());
    }

    @Test
    public void shortSectionBetweenVertices_fallsBackToTwoNearest() {
        // 1200..1800 valt tussen vertices 1000 en 2000: geen vertex in bereik.
        List<double[]> pts = SurfaceSectionGeometry.pointsBetween(DIST, LAT, LON, 1200, 1800);
        assertEquals("fallback geeft de twee dichtstbijzijnde vertices", 2, pts.size());
        assertEquals(51.1, pts.get(0)[0], 1e-9); // dichtst bij 1200
        assertEquals(51.2, pts.get(1)[0], 1e-9); // dichtst bij 1800
    }

    @Test
    public void degenerateZeroLength_returnsAtMostOnePoint() {
        // start == end op een vertex: één punt, niet tekenbaar -> caller slaat over.
        List<double[]> pts = SurfaceSectionGeometry.pointsBetween(DIST, LAT, LON, 2000, 2000);
        assertTrue(pts.size() <= 1);
    }
}
