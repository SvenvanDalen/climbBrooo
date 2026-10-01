package nl.paree.climbpro.domain.route;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.List;

public class KomootTourConverterTest {

    private static final String COORDS = "{\"items\":["
            + "{\"lat\":47.514395,\"lng\":10.285864,\"alt\":798.4,\"t\":0},"
            + "{\"lat\":47.514305,\"lng\":10.287012,\"alt\":801.0,\"t\":13000},"
            + "{\"lat\":47.514289,\"lng\":10.287316,\"t\":16000},"
            + "{\"lat\":\"x\",\"lng\":10.2},"
            + "{\"lat\":95.0,\"lng\":10.2,\"alt\":1}"
            + "]}";

    @Test
    public void parsesCoordinatesAndSkipsInvalidItems() throws IOException {
        List<RoutePoint> pts = KomootTourConverter.parseCoordinates(COORDS);
        assertEquals(3, pts.size());
        assertEquals(47.514395, pts.get(0).lat, 1e-9);
        assertEquals(10.285864, pts.get(0).lon, 1e-9);
        assertEquals(798.4, pts.get(0).elevation, 1e-9);
        assertTrue(Double.isNaN(pts.get(2).elevation));
        assertEquals(0.0, pts.get(1).distance, 0.0);
    }

    @Test(expected = IOException.class)
    public void missingItemsIsAnError() throws IOException {
        KomootTourConverter.parseCoordinates("{\"status\":403}");
    }

    @Test(expected = IOException.class)
    public void tooFewPointsIsAnError() throws IOException {
        KomootTourConverter.parseCoordinates("{\"items\":[{\"lat\":1,\"lng\":2}]}");
    }

    @Test(expected = IOException.class)
    public void malformedJsonIsAnError() throws IOException {
        KomootTourConverter.parseCoordinates("<html>");
    }

    @Test
    public void parsesTourName() {
        assertEquals("Rondje Vaals",
                KomootTourConverter.parseName("{\"id\":1,\"name\":\"  Rondje Vaals \"}"));
        assertNull(KomootTourConverter.parseName("{\"id\":1}"));
        assertNull(KomootTourConverter.parseName("{\"name\":\"\"}"));
        assertNull(KomootTourConverter.parseName("not json"));
    }

    @Test
    public void gpxContainsEscapedNameAndPoints() throws IOException {
        List<RoutePoint> pts = KomootTourConverter.parseCoordinates(COORDS);
        String gpx = KomootTourConverter.toGpx("Col <du> \"Tour\" & co", pts);
        assertTrue(gpx.contains("<name>Col &lt;du&gt; &quot;Tour&quot; &amp; co</name>"));
        assertTrue(gpx.contains("<trkpt lat=\"47.514395\" lon=\"10.285864\"><ele>798.4</ele></trkpt>"));
        assertTrue(gpx.contains("<trkpt lat=\"47.514289\" lon=\"10.287316\"></trkpt>"));
        assertFalse(gpx.contains("NaN"));
        assertEquals("Col <du> \"Tour\" & co", KomootTourConverter.gpxName(gpx));
    }

    @Test
    public void gpxNameFromRideWithGpsExport() {
        String gpx = "<?xml version=\"1.0\"?><gpx><metadata><name>Amstel Gold</name></metadata>"
                + "<trk><name>Other</name></trk></gpx>";
        assertEquals("Amstel Gold", KomootTourConverter.gpxName(gpx));
        assertEquals("CData", KomootTourConverter.gpxName("<gpx><name><![CDATA[CData]]></name></gpx>"));
        assertNull(KomootTourConverter.gpxName("<gpx><trk></trk></gpx>"));
        assertNull(KomootTourConverter.gpxName(null));
    }
}
