package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;

/**
 * TDD for the single-climb GPX export (issue #79): a track covering the climb's geometry,
 * plus waypoints at every segment boundary and — when PR data is supplied — at the climb's
 * personal-record splits. Pure string generation, no Android/Robolectric dependency.
 */
public class ClimbGpxWriterTest {

    private static final Pattern WPT_PATTERN =
            Pattern.compile("<wpt lat=\"([-0-9.]+)\" lon=\"([-0-9.]+)\">");
    private static final Pattern TRKPT_PATTERN =
            Pattern.compile("<trkpt lat=\"([-0-9.]+)\" lon=\"([-0-9.]+)\">");

    /** A 5-point route climbing steadily from distance 0 to 1000 m. */
    private static StoredRoute route() {
        StoredRoute r = new StoredRoute();
        r.routeId = "r1";
        r.name = "Test route";
        r.lats = new double[] {50.000, 50.001, 50.002, 50.003, 50.004};
        r.lons = new double[] {5.000, 5.001, 5.002, 5.003, 5.004};
        r.elevations = new double[] {100, 130, 160, 190, 220};
        r.distances = new double[] {0, 250, 500, 750, 1000};
        return r;
    }

    /** A climb spanning the whole route, split into 4 equal segments. */
    private static StoredClimb climb() {
        StoredClimb c = new StoredClimb();
        c.startDistance = 0;
        c.endDistance = 1000;
        c.length = 1000;
        c.elevationGain = 120;
        c.avgGradient = 0.12;
        c.startLat = 50.000;
        c.startLon = 5.000;
        c.name = "Test climb";
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
    public void writesOneTrackpointPerRoutePoint() {
        String gpx = ClimbGpxWriter.toGpx(route(), climb(), 0, null, null);
        assertEquals(5, count(TRKPT_PATTERN, gpx));
    }

    @Test
    public void writesOneWaypointPerSegmentBoundaryWhenNoPrData() {
        String gpx = ClimbGpxWriter.toGpx(route(), climb(), 0, null, null);
        // 4 segments -> 4 boundary waypoints, no PR waypoint since no PR data was supplied.
        assertEquals(4, count(WPT_PATTERN, gpx));
        assertFalse(gpx.contains("PR split"));
        assertFalse(gpx.contains("<name>PR</name>"));
    }

    @Test
    public void segmentBoundaryWaypointsSitAtCorrectCoordinates() {
        String gpx = ClimbGpxWriter.toGpx(route(), climb(), 0, null, null);
        Matcher m = WPT_PATTERN.matcher(gpx);

        // Boundaries are at distance 250, 500, 750, 1000 -> route points 1, 2, 3, 4.
        assertTrue(m.find());
        assertEquals(50.001, Double.parseDouble(m.group(1)), 1e-6);
        assertEquals(5.001, Double.parseDouble(m.group(2)), 1e-6);

        assertTrue(m.find());
        assertEquals(50.002, Double.parseDouble(m.group(1)), 1e-6);

        assertTrue(m.find());
        assertEquals(50.003, Double.parseDouble(m.group(1)), 1e-6);

        assertTrue(m.find());
        assertEquals(50.004, Double.parseDouble(m.group(1)), 1e-6);
    }

    @Test
    public void lastSegmentBoundaryIsNamedTop() {
        String gpx = ClimbGpxWriter.toGpx(route(), climb(), 0, null, null);
        assertTrue(gpx.contains("<name>Top</name>"));
        assertTrue(gpx.contains("<name>Segment 1</name>"));
        assertTrue(gpx.contains("<name>Segment 2</name>"));
        assertTrue(gpx.contains("<name>Segment 3</name>"));
    }

    @Test
    public void includesPrSplitsWhenSegmentPrDataSupplied() {
        int[] bestSplitSec = {30, 35, 32, 40}; // per-segment fastest splits
        String gpx = ClimbGpxWriter.toGpx(route(), climb(), 0, bestSplitSec, null);

        // Cumulative PR time at each boundary: 0:30, 1:05, 1:37, 2:17.
        assertTrue(gpx.contains("PR split 0:30"));
        assertTrue(gpx.contains("PR split 1:05"));
        assertTrue(gpx.contains("PR split 1:37"));
        assertTrue(gpx.contains("PR split 2:17"));
    }

    @Test
    public void includesDedicatedPrWaypointWhenBestElapsedSupplied() {
        String gpx = ClimbGpxWriter.toGpx(route(), climb(), 0, null, 137);

        // 5 waypoints: the dedicated climb-PR marker plus the 4 segment boundaries.
        assertEquals(5, count(WPT_PATTERN, gpx));
        assertTrue(gpx.contains("<name>PR</name>"));
        assertTrue(gpx.contains("personal record 2:17"));
    }

    @Test
    public void omitsPrSplitsWhenArrayLengthMismatchesSegmentCount() {
        // Stale PR data from before a re-segmentation must not be misaligned onto segments.
        int[] wrongLength = {30, 35};
        String gpx = ClimbGpxWriter.toGpx(route(), climb(), 0, wrongLength, null);
        assertFalse(gpx.contains("PR split"));
        assertEquals(4, count(WPT_PATTERN, gpx));
    }

    @Test
    public void usesUserDisplayNameOverDetectedName() {
        StoredClimb c = climb();
        c.userDisplayName = "Mijn favoriete klim";
        String gpx = ClimbGpxWriter.toGpx(route(), c, 0, null, null);
        assertTrue(gpx.contains("Mijn favoriete klim"));
    }

    @Test
    public void escapesSpecialCharsInClimbName() {
        StoredClimb c = climb();
        c.name = "Col & <Steil>";
        String gpx = ClimbGpxWriter.toGpx(route(), c, 0, null, null);
        assertTrue(gpx.contains("Col &amp; &lt;Steil&gt;"));
        assertFalse(gpx.contains("Col & <Steil>"));
    }

    @Test
    public void isWellFormedXml() throws Exception {
        String gpx = ClimbGpxWriter.toGpx(route(), climb(), 0, new int[] {30, 35, 32, 40}, 137);
        javax.xml.parsers.DocumentBuilderFactory factory =
                javax.xml.parsers.DocumentBuilderFactory.newInstance();
        javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
        builder.parse(new org.xml.sax.InputSource(new java.io.StringReader(gpx)));
    }

    @Test
    public void startsWithXmlDeclaration() {
        assertTrue(ClimbGpxWriter.toGpx(route(), climb(), 0, null, null).startsWith("<?xml"));
    }

    @Test
    public void fallsBackToIndexBasedNameWhenClimbUnnamed() {
        StoredClimb c = climb();
        c.name = null;
        c.userDisplayName = null;
        String gpx = ClimbGpxWriter.toGpx(route(), c, 2, null, null);
        assertTrue(gpx.contains("Climb 3"));
    }

    @Test
    public void nullRouteThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> ClimbGpxWriter.toGpx(null, climb(), 0, null, null));
    }

    @Test
    public void nullClimbThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> ClimbGpxWriter.toGpx(route(), null, 0, null, null));
    }

    @Test
    public void emptyRouteGeometryThrows() {
        StoredRoute r = new StoredRoute();
        r.lats = new double[0];
        r.lons = new double[0];
        r.distances = new double[0];
        assertThrows(IllegalArgumentException.class,
                () -> ClimbGpxWriter.toGpx(r, climb(), 0, null, null));
    }

    @Test
    public void climbWithNoSegmentsWritesTrackButNoWaypoints() {
        StoredClimb c = climb();
        c.segments = new ArrayList<>();
        String gpx = ClimbGpxWriter.toGpx(route(), c, 0, null, null);
        assertEquals(0, count(WPT_PATTERN, gpx));
        assertEquals(5, count(TRKPT_PATTERN, gpx));
    }

    @Test
    public void negativeOrZeroBestElapsedOmitsPrWaypoint() {
        String gpx = ClimbGpxWriter.toGpx(route(), climb(), 0, null, 0);
        assertFalse(gpx.contains("<name>PR</name>"));
        assertEquals(4, count(WPT_PATTERN, gpx));
    }

    // -----------------------------------------------------------------------
    // Home-climb privacy fuzzing (issue #92)
    // -----------------------------------------------------------------------

    @Test
    public void nonHomeClimb_startCoordinateIsExactEvenWithPrivacyRadiusSet() {
        StoredClimb c = climb();
        c.isHome = false;
        String gpx = ClimbGpxWriter.toGpx(route(), c, 0, null, null, 300);
        Matcher m = TRKPT_PATTERN.matcher(gpx);
        assertTrue(m.find());
        assertEquals(50.000, Double.parseDouble(m.group(1)), 1e-6);
        assertEquals(5.000, Double.parseDouble(m.group(2)), 1e-6);
    }

    @Test
    public void homeClimb_startCoordinateDiffersFromExactWhenPrivacyRadiusSet() {
        StoredClimb c = climb();
        c.isHome = true;
        String gpxHome = ClimbGpxWriter.toGpx(route(), c, 0, null, null, 300);

        StoredClimb notHome = climb();
        notHome.isHome = false;
        String gpxPlain = ClimbGpxWriter.toGpx(route(), notHome, 0, null, null, 300);

        Matcher home = TRKPT_PATTERN.matcher(gpxHome);
        assertTrue(home.find());
        double homeLat = Double.parseDouble(home.group(1));
        double homeLon = Double.parseDouble(home.group(2));

        Matcher plain = TRKPT_PATTERN.matcher(gpxPlain);
        assertTrue(plain.find());
        double plainLat = Double.parseDouble(plain.group(1));
        double plainLon = Double.parseDouble(plain.group(2));

        assertTrue(homeLat != plainLat || homeLon != plainLon);
    }

    @Test
    public void homeClimb_withZeroPrivacyRadiusStaysExact() {
        StoredClimb c = climb();
        c.isHome = true;
        String gpx = ClimbGpxWriter.toGpx(route(), c, 0, null, null, 0);
        Matcher m = TRKPT_PATTERN.matcher(gpx);
        assertTrue(m.find());
        assertEquals(50.000, Double.parseDouble(m.group(1)), 1e-6);
        assertEquals(5.000, Double.parseDouble(m.group(2)), 1e-6);
    }

    @Test
    public void homeClimb_fuzzingIsDeterministicAcrossExports() {
        StoredClimb c1 = climb();
        c1.isHome = true;
        StoredClimb c2 = climb();
        c2.isHome = true;

        String gpxA = ClimbGpxWriter.toGpx(route(), c1, 0, null, null, 300);
        String gpxB = ClimbGpxWriter.toGpx(route(), c2, 0, null, null, 300);

        Matcher a = TRKPT_PATTERN.matcher(gpxA);
        Matcher b = TRKPT_PATTERN.matcher(gpxB);
        assertTrue(a.find());
        assertTrue(b.find());
        assertEquals(a.group(1), b.group(1));
        assertEquals(a.group(2), b.group(2));
    }

    @Test
    public void fiveArgOverload_stillWorksAndIsUnaffectedByHomeFlag() {
        StoredClimb c = climb();
        c.isHome = true;
        // Old call site (no privacy radius) must keep exporting exact coordinates: it's the
        // caller's job to pass the configured radius, so omitting it is equivalent to radius=0.
        String gpx = ClimbGpxWriter.toGpx(route(), c, 0, null, null);
        Matcher m = TRKPT_PATTERN.matcher(gpx);
        assertTrue(m.find());
        assertEquals(50.000, Double.parseDouble(m.group(1)), 1e-6);
    }

    // -----------------------------------------------------------------------
    // Whole climb inside the privacy zone (regression for the leak where a home
    // climb shorter than the configured radius still exposed its real end coordinate).
    // -----------------------------------------------------------------------

    /** Formats a coordinate exactly the way ClimbGpxWriter does, for exact-match checks. */
    private static String fmt(double coord) {
        return String.format(java.util.Locale.US, "%.7f", coord);
    }

    @Test
    public void homeClimb_entirelyInsidePrivacyRadius_leaksNoRealCoordinate() {
        // Climb spans distance 0..1000m; radius 1500m covers the entire climb, including its
        // endpoint (endIdx) — exactly the case that used to clamp trackStartIdx to endIdx and
        // then emit the real, unfuzzed endpoint as both a <trkpt> and the "Top" <wpt>.
        StoredRoute r = route();
        StoredClimb c = climb();
        c.isHome = true;
        double radius = 1500;

        String gpx = ClimbGpxWriter.toGpx(r, c, 0, null, null, radius);

        // Core privacy guarantee: none of the route's real (unfuzzed) coordinates — every one
        // of which sits within the privacy radius of the true start here — may appear anywhere
        // in the exported GPX, neither as a <trkpt> nor as a <wpt>.
        for (int i = 0; i < r.lats.length; i++) {
            String realLat = fmt(r.lats[i]);
            String realLon = fmt(r.lons[i]);
            assertFalse("real point " + i + " (lat) leaked into GPX", gpx.contains(realLat));
            assertFalse("real point " + i + " (lon) leaked into GPX", gpx.contains(realLon));
        }

        // Only the single fuzzed start point should be emitted as track geometry — no real
        // trackpoints, since every real point on this climb is inside the privacy zone.
        assertEquals(1, count(TRKPT_PATTERN, gpx));
        Matcher trk = TRKPT_PATTERN.matcher(gpx);
        assertTrue(trk.find());
        double[] fuzzedStart = CoordinateFuzzer.fuzz(c.startLat, c.startLon, radius);
        assertEquals(fuzzedStart[0], Double.parseDouble(trk.group(1)), 1e-6);
        assertEquals(fuzzedStart[1], Double.parseDouble(trk.group(2)), 1e-6);

        // No "Top" (or any other segment-boundary) waypoint: every boundary, including the
        // final one that used to leak, falls inside the privacy zone and must be omitted.
        assertEquals(0, count(WPT_PATTERN, gpx));
        assertFalse(gpx.contains("<name>Top</name>"));
    }

    @Test
    public void homeClimb_entirelyInsidePrivacyRadius_stillProducesWellFormedXml() throws Exception {
        StoredClimb c = climb();
        c.isHome = true;
        String gpx = ClimbGpxWriter.toGpx(route(), c, 0, null, null, 1500);
        javax.xml.parsers.DocumentBuilderFactory factory =
                javax.xml.parsers.DocumentBuilderFactory.newInstance();
        javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
        builder.parse(new org.xml.sax.InputSource(new java.io.StringReader(gpx)));
    }

    @Test
    public void homeClimb_partiallyInsidePrivacyRadius_keepsBoundaryExactlyAtZoneExitPrecise() {
        // Non-degenerate case: trackStartIdx (1) < endIdx (4). The segment-1 boundary sits
        // exactly at trackStartIdx, i.e. the first point confirmed to be outside the privacy
        // zone — this must stay precise (idx < trackStartIdx should still be false here),
        // unlike the whole-climb-in-zone case where idx == trackStartIdx is still in the zone.
        StoredRoute r = route();
        StoredClimb c = climb();
        c.isHome = true;
        double radius = 250; // exits the zone right at route point index 1 (distance 250m)

        String gpx = ClimbGpxWriter.toGpx(r, c, 0, null, null, radius);

        assertTrue(gpx.contains("<name>Segment 1</name>"));
        Matcher m = WPT_PATTERN.matcher(gpx);
        assertTrue(m.find());
        assertEquals(r.lats[1], Double.parseDouble(m.group(1)), 1e-6);
        assertEquals(r.lons[1], Double.parseDouble(m.group(2)), 1e-6);

        // Remaining 3 boundaries (segments 2, 3, Top) also stay precise; all 4 are present.
        assertEquals(4, count(WPT_PATTERN, gpx));
        assertTrue(gpx.contains("<name>Top</name>"));
    }
}
