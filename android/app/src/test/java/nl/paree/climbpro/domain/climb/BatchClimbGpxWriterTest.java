package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;

/**
 * TDD for the batch/season GPX export (issue #91): one GPX document covering several
 * climbs, each as its own {@code <trk>} + waypoint set, built by reusing {@link
 * ClimbGpxWriter#appendClimb}. Mirrors {@link ClimbGpxWriterTest}'s fixture conventions.
 */
public class BatchClimbGpxWriterTest {

    private static final Pattern TRK_NAME_PATTERN = Pattern.compile("<trk>\\s*<name>");
    private static final Pattern TRKPT_PATTERN =
            Pattern.compile("<trkpt lat=\"([-0-9.]+)\" lon=\"([-0-9.]+)\">");
    private static final Pattern WPT_PATTERN =
            Pattern.compile("<wpt lat=\"([-0-9.]+)\" lon=\"([-0-9.]+)\">");

    private static StoredRoute route(String routeId, double latOffset) {
        StoredRoute r = new StoredRoute();
        r.routeId = routeId;
        r.name = "Test route " + routeId;
        r.lats = new double[] {50.000 + latOffset, 50.001 + latOffset, 50.002 + latOffset,
                50.003 + latOffset, 50.004 + latOffset};
        r.lons = new double[] {5.000, 5.001, 5.002, 5.003, 5.004};
        r.elevations = new double[] {100, 130, 160, 190, 220};
        r.distances = new double[] {0, 250, 500, 750, 1000};
        return r;
    }

    private static StoredClimb climb(String name) {
        StoredClimb c = new StoredClimb();
        c.startDistance = 0;
        c.endDistance = 1000;
        c.length = 1000;
        c.elevationGain = 120;
        c.avgGradient = 0.12;
        c.startLat = 50.000;
        c.startLon = 5.000;
        c.name = name;
        c.segments = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            StoredSegment s = new StoredSegment();
            s.distance = 250;
            s.elevationGain = 30;
            s.gradient = 0.12;
            s.colorIndex = 3;
            c.segments.add(s);
        }
        return c;
    }

    private static int count(Pattern p, String text) {
        Matcher m = p.matcher(text);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    @Test
    public void writesOneTrackPerClimb() {
        List<BatchClimbGpxWriter.Entry> entries = new ArrayList<>();
        entries.add(new BatchClimbGpxWriter.Entry(route("r1", 0), climb("Climb A"), 0, null, null));
        entries.add(new BatchClimbGpxWriter.Entry(route("r2", 1), climb("Climb B"), 0, null, null));
        entries.add(new BatchClimbGpxWriter.Entry(route("r3", 2), climb("Climb C"), 0, null, null));

        String gpx = BatchClimbGpxWriter.toGpx(entries);

        assertEquals(3, count(TRK_NAME_PATTERN, gpx.replace("\n", "")));
        assertTrue(gpx.contains("Climb A"));
        assertTrue(gpx.contains("Climb B"));
        assertTrue(gpx.contains("Climb C"));
    }

    @Test
    public void includesEveryClimbsTrackpointsAndWaypoints() {
        List<BatchClimbGpxWriter.Entry> entries = new ArrayList<>();
        entries.add(new BatchClimbGpxWriter.Entry(route("r1", 0), climb("Climb A"), 0, null, null));
        entries.add(new BatchClimbGpxWriter.Entry(route("r2", 1), climb("Climb B"), 0, null, null));

        String gpx = BatchClimbGpxWriter.toGpx(entries);

        // 5 route points per climb -> 10 trkpts; 4 segment-boundary waypoints per climb -> 8 wpts.
        assertEquals(10, count(TRKPT_PATTERN, gpx));
        assertEquals(8, count(WPT_PATTERN, gpx));
    }

    @Test
    public void singleEntryRoundTripsLikeSingleClimbExport() {
        StoredRoute r = route("r1", 0);
        StoredClimb c = climb("Solo climb");
        List<BatchClimbGpxWriter.Entry> entries =
                Collections.singletonList(new BatchClimbGpxWriter.Entry(r, c, 0, null, null));

        String batchGpx = BatchClimbGpxWriter.toGpx(entries);
        String singleGpx = ClimbGpxWriter.toGpx(r, c, 0, null, null);

        assertEquals(count(TRKPT_PATTERN, singleGpx), count(TRKPT_PATTERN, batchGpx));
        assertEquals(count(WPT_PATTERN, singleGpx), count(WPT_PATTERN, batchGpx));
        assertTrue(batchGpx.contains("Solo climb"));
    }

    @Test
    public void skipsClimbsWithUnusableGeometryButKeepsTheRest() {
        StoredRoute emptyRoute = new StoredRoute();
        emptyRoute.lats = new double[0];
        emptyRoute.lons = new double[0];
        emptyRoute.distances = new double[0];

        List<BatchClimbGpxWriter.Entry> entries = new ArrayList<>();
        entries.add(new BatchClimbGpxWriter.Entry(emptyRoute, climb("Broken"), 0, null, null));
        entries.add(new BatchClimbGpxWriter.Entry(route("r2", 1), climb("Good climb"), 0, null, null));

        String gpx = BatchClimbGpxWriter.toGpx(entries);

        assertTrue(gpx.contains("Good climb"));
        assertEquals(1, count(TRK_NAME_PATTERN, gpx.replace("\n", "")));
    }

    @Test
    public void isWellFormedXmlWithMultipleTracks() throws Exception {
        List<BatchClimbGpxWriter.Entry> entries = new ArrayList<>();
        entries.add(new BatchClimbGpxWriter.Entry(
                route("r1", 0), climb("Climb A"), 0, new int[] {30, 35, 32, 40}, 137));
        entries.add(new BatchClimbGpxWriter.Entry(route("r2", 1), climb("Climb B"), 0, null, null));

        String gpx = BatchClimbGpxWriter.toGpx(entries);

        javax.xml.parsers.DocumentBuilderFactory factory =
                javax.xml.parsers.DocumentBuilderFactory.newInstance();
        javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
        builder.parse(new org.xml.sax.InputSource(new java.io.StringReader(gpx)));
    }

    @Test
    public void allWaypointsPrecedeAllTracksPerGpx11Schema() {
        // GPX 1.1 xsd is a sequence: every <wpt>, then <rte>, then <trk>. Interleaving
        // wpt/trk per climb makes strict importers reject the file or drop late waypoints.
        List<BatchClimbGpxWriter.Entry> entries = new ArrayList<>();
        entries.add(new BatchClimbGpxWriter.Entry(route("r1", 0), climb("Climb A"), 0, null, 137));
        entries.add(new BatchClimbGpxWriter.Entry(route("r2", 1), climb("Climb B"), 0, null, null));

        String gpx = BatchClimbGpxWriter.toGpx(entries);

        assertTrue("last <wpt> must come before first <trk>",
                gpx.lastIndexOf("<wpt ") < gpx.indexOf("<trk>"));
    }

    @Test
    public void waypointNamesArePrefixedWithClimbNameInBatch() {
        List<BatchClimbGpxWriter.Entry> entries = new ArrayList<>();
        entries.add(new BatchClimbGpxWriter.Entry(route("r1", 0), climb("Climb A"), 0, null, 137));
        entries.add(new BatchClimbGpxWriter.Entry(route("r2", 1), climb("Climb B"), 0, null, null));

        String gpx = BatchClimbGpxWriter.toGpx(entries);

        assertTrue(gpx.contains("<name>Climb A — PR</name>"));
        assertTrue(gpx.contains("<name>Climb A — Segment 1</name>"));
        assertTrue(gpx.contains("<name>Climb A — Top</name>"));
        assertTrue(gpx.contains("<name>Climb B — Top</name>"));
        assertTrue("no unprefixed waypoint names in a batch", !gpx.contains("<name>Top</name>"));
    }

    @Test
    public void singleClimbExportKeepsShortWaypointNames() {
        String gpx = ClimbGpxWriter.toGpx(route("r1", 0), climb("Solo climb"), 0, null, null);

        assertTrue(gpx.contains("<name>Top</name>"));
        assertTrue(gpx.contains("<name>Segment 1</name>"));
    }

    @Test
    public void nullOrEmptyEntriesThrows() {
        assertThrows(IllegalArgumentException.class, () -> BatchClimbGpxWriter.toGpx(null));
        assertThrows(IllegalArgumentException.class,
                () -> BatchClimbGpxWriter.toGpx(Collections.emptyList()));
    }

    @Test
    public void allUnusableEntriesThrows() {
        StoredRoute emptyRoute = new StoredRoute();
        emptyRoute.lats = new double[0];
        emptyRoute.lons = new double[0];
        emptyRoute.distances = new double[0];
        List<BatchClimbGpxWriter.Entry> entries = Collections.singletonList(
                new BatchClimbGpxWriter.Entry(emptyRoute, climb("Broken"), 0, null, null));

        assertThrows(IllegalArgumentException.class, () -> BatchClimbGpxWriter.toGpx(entries));
    }
}
