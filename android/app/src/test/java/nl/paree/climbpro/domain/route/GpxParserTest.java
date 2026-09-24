package nl.paree.climbpro.domain.route;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class GpxParserTest {

    private static List<RoutePoint> parse(String gpx) throws GpxParseException {
        return GpxParser.parse(new ByteArrayInputStream(gpx.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void parsesTrackPointsWithElevation() throws Exception {
        String gpx = "<gpx><trk><trkseg>"
                + "<trkpt lat='51.0' lon='5.0'><ele>100.5</ele></trkpt>"
                + "<trkpt lat='51.1' lon='5.1'><ele>120.0</ele></trkpt>"
                + "</trkseg></trk></gpx>";
        List<RoutePoint> pts = parse(gpx);
        assertEquals(2, pts.size());
        assertEquals(51.0, pts.get(0).lat, 1e-9);
        assertEquals(5.0, pts.get(0).lon, 1e-9);
        assertEquals(100.5, pts.get(0).elevation, 1e-9);
        assertEquals(120.0, pts.get(1).elevation, 1e-9);
    }

    @Test
    public void parsesRoutePoints() throws Exception {
        String gpx = "<gpx><rte>"
                + "<rtept lat='51.0' lon='5.0'><ele>10</ele></rtept>"
                + "<rtept lat='51.1' lon='5.1'><ele>20</ele></rtept>"
                + "</rte></gpx>";
        assertEquals(2, parse(gpx).size());
    }

    @Test
    public void missingElevationBecomesNaN() throws Exception {
        String gpx = "<gpx><trkseg>"
                + "<trkpt lat='51.0' lon='5.0'></trkpt>"
                + "</trkseg></gpx>";
        List<RoutePoint> pts = parse(gpx);
        assertEquals(1, pts.size());
        assertTrue("missing <ele> must be NaN", Double.isNaN(pts.get(0).elevation));
    }

    @Test
    public void malformedElevationBecomesNaNButPointIsKept() throws Exception {
        String gpx = "<gpx><trkseg>"
                + "<trkpt lat='51.0' lon='5.0'><ele>not-a-number</ele></trkpt>"
                + "</trkseg></gpx>";
        List<RoutePoint> pts = parse(gpx);
        assertEquals(1, pts.size());
        assertTrue(Double.isNaN(pts.get(0).elevation));
    }

    @Test
    public void pointMissingLatOrLonIsSkipped() throws Exception {
        String gpx = "<gpx><trkseg>"
                + "<trkpt lon='5.0'><ele>100</ele></trkpt>"          // no lat → skipped
                + "<trkpt lat='51.0' lon='5.0'><ele>100</ele></trkpt>" // valid
                + "</trkseg></gpx>";
        List<RoutePoint> pts = parse(gpx);
        assertEquals(1, pts.size());
        assertEquals(51.0, pts.get(0).lat, 1e-9);
    }

    @Test
    public void parsesWaypoints() throws Exception {
        String gpx = "<gpx>"
                + "<wpt lat='51.0' lon='5.0'><ele>100</ele></wpt>"
                + "</gpx>";
        assertEquals(1, parse(gpx).size());
    }

    @Test
    public void waypointsNextToATrackAreNotRouteGeometry() throws Exception {
        String gpx = "<gpx>"
                + "<wpt lat='52.0' lon='6.0'><name>Top</name></wpt>"
                + "<trk><trkseg>"
                + "<trkpt lat='51.0' lon='5.0'/><trkpt lat='51.1' lon='5.0'/>"
                + "</trkseg></trk>"
                + "<wpt lat='53.0' lon='7.0'/>"
                + "</gpx>";
        List<RoutePoint> pts = parse(gpx);
        assertEquals(2, pts.size());
        assertEquals(51.0, pts.get(0).lat, 1e-9);
        assertEquals(51.1, pts.get(1).lat, 1e-9);
    }

    @Test
    public void noPointsThrows() {
        assertThrows(GpxParseException.class,
                () -> parse("<gpx><trk><trkseg></trkseg></trk></gpx>"));
    }

    @Test
    public void malformedXmlThrows() {
        assertThrows(GpxParseException.class,
                () -> parse("<gpx><trkpt lat='51.0' lon='5.0'>")); // unclosed
    }
}
