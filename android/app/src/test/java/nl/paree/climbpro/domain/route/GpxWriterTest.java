package nl.paree.climbpro.domain.route;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import nl.paree.climbpro.data.route.StoredRoute;

/**
 * TDD for the navigation-handoff GPX serializer. A route (Strava-sourced or GPX-imported)
 * must be writable to a GPX that Garmin Connect can import as a navigable course. The
 * strongest guarantee is a round-trip: what we write, {@link GpxParser} must read back
 * unchanged.
 */
@RunWith(RobolectricTestRunner.class)
public class GpxWriterTest {

    private static StoredRoute route(double[] lats, double[] lons, double[] eles, String name) {
        StoredRoute r = new StoredRoute();
        r.routeId = "r1";
        r.name = name;
        r.lats = lats;
        r.lons = lons;
        r.elevations = eles;
        return r;
    }

    private static List<RoutePoint> reparse(String gpx) {
        try {
            return GpxParser.parse(new ByteArrayInputStream(gpx.getBytes(StandardCharsets.UTF_8)));
        } catch (GpxParseException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void roundTripsGeometryThroughParser() {
        StoredRoute r = route(
                new double[] {50.5001, 50.5010, 50.5025},
                new double[] {5.8001, 5.8009, 5.8020},
                new double[] {120.0, 155.5, 190.0},
                "La Redoute");

        List<RoutePoint> back = reparse(GpxWriter.toGpx(r));

        assertEquals(3, back.size());
        assertEquals(50.5001, back.get(0).lat, 1e-6);
        assertEquals(5.8001, back.get(0).lon, 1e-6);
        assertEquals(120.0, back.get(0).elevation, 1e-3);
        assertEquals(190.0, back.get(2).elevation, 1e-3);
    }

    @Test
    public void writesOneTrackpointPerCoordinate() {
        StoredRoute r = route(
                new double[] {50.0, 50.1, 50.2, 50.3},
                new double[] {5.0, 5.1, 5.2, 5.3},
                new double[] {10, 20, 30, 40},
                "Vier punten");
        assertEquals(4, reparse(GpxWriter.toGpx(r)).size());
    }

    @Test
    public void startsWithXmlDeclaration() {
        StoredRoute r = route(new double[] {50.0}, new double[] {5.0}, new double[] {10.0}, "x");
        assertTrue(GpxWriter.toGpx(r).startsWith("<?xml"));
    }

    @Test
    public void includesRouteNameEscapingXml() {
        StoredRoute r = route(new double[] {50.0, 50.1}, new double[] {5.0, 5.1},
                new double[] {10, 20}, "Col & <Steil> \"Deuxième\"");
        String gpx = GpxWriter.toGpx(r);

        // Raw special characters must be escaped, and the document must stay parseable.
        assertTrue(gpx.contains("Col &amp; &lt;Steil&gt;"));
        assertFalse(gpx.contains("Col & <Steil>"));
        assertEquals(2, reparse(gpx).size());
    }

    @Test
    public void prefersUserDisplayNameOverImportedName() {
        StoredRoute r = route(new double[] {50.0}, new double[] {5.0}, new double[] {10.0}, "Strava naam");
        r.userDisplayName = "Mijn Ardennen-rit";
        assertTrue(GpxWriter.toGpx(r).contains("Mijn Ardennen-rit"));
    }

    @Test
    public void omitsElevationWhenNaN() {
        StoredRoute r = route(
                new double[] {50.0, 50.1, 50.2},
                new double[] {5.0, 5.1, 5.2},
                new double[] {10.0, Double.NaN, 30.0},
                "Gat in hoogte");
        String gpx = GpxWriter.toGpx(r);

        // No empty/garbage <ele> for the NaN sample; the parser reads it back as NaN.
        assertFalse(gpx.contains("NaN"));
        List<RoutePoint> back = reparse(gpx);
        assertEquals(3, back.size());
        assertEquals(10.0, back.get(0).elevation, 1e-3);
        assertTrue(Double.isNaN(back.get(1).elevation));
        assertEquals(30.0, back.get(2).elevation, 1e-3);
    }

    @Test
    public void nullElevationsArrayStillWritesTrackpoints() {
        StoredRoute r = route(new double[] {50.0, 50.1}, new double[] {5.0, 5.1}, null, "Geen hoogte");
        List<RoutePoint> back = reparse(GpxWriter.toGpx(r));
        assertEquals(2, back.size());
        assertTrue(Double.isNaN(back.get(0).elevation));
    }

    @Test
    public void nullRouteThrows() {
        assertThrows(IllegalArgumentException.class, () -> GpxWriter.toGpx(null));
    }

    @Test
    public void nullGeometryThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> GpxWriter.toGpx(route(null, null, null, "leeg")));
    }

    @Test
    public void emptyGeometryThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> GpxWriter.toGpx(route(new double[0], new double[0], new double[0], "leeg")));
    }
}
