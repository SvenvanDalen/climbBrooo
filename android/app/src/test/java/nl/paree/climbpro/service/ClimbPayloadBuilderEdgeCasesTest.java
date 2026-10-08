package nl.paree.climbpro.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import nl.paree.climbpro.data.route.StoredCalibrationPoint;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.data.route.StoredStarredSegment;
import nl.paree.climbpro.data.route.StoredTunnel;
import nl.paree.climbpro.domain.segment.SurfaceType;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

/** Name limits, negative gradients, starred-segment filtering, checkpoints and budget fallbacks. */
public class ClimbPayloadBuilderEdgeCasesTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ClimbPayloadBuilder builder = new ClimbPayloadBuilder(mapper);

    private static final String LONG_NAME = "Een veel te lange naam voor het horloge display";

    private static StoredClimb climb(double avgGradient) {
        StoredClimb c = new StoredClimb();
        c.startDistance = 1000;
        c.endDistance = 2000;
        c.length = 1000;
        c.elevationGain = 50;
        c.avgGradient = avgGradient;
        c.startLat = 50.1;
        c.startLon = 5.1;
        c.segments = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            StoredSegment s = new StoredSegment();
            s.distance = 80;
            s.elevationGain = 4;
            s.gradient = 0.05;
            s.colorIndex = 2;
            c.segments.add(s);
        }
        return c;
    }

    private static StoredRoute route(StoredClimb... climbs) {
        StoredRoute r = new StoredRoute();
        r.routeId = "r1";
        r.name = "Rondje";
        r.climbs = new ArrayList<>(Arrays.asList(climbs));
        return r;
    }

    private static StoredStarredSegment starred(int s, int e, int surface, String name) {
        StoredStarredSegment st = new StoredStarredSegment();
        st.startDistance = s;
        st.endDistance = e;
        st.surfaceType = surface;
        st.name = name;
        return st;
    }

    @Test
    public void userDisplayNameWinsAndOverlongNamesAreOmitted() throws Exception {
        StoredClimb c = climb(0.05);
        c.name = "Strava naam";
        c.userDisplayName = "Mijn klim";
        StoredRoute r = route(c);
        r.userDisplayName = "Mijn route";
        JsonNode root = mapper.readTree(builder.buildRoutePayload(r));
        assertEquals("Mijn route", root.get("name").asText());
        assertEquals("Mijn klim", root.get("climbs").get(0).get("n").asText());

        c.userDisplayName = LONG_NAME;
        r.userDisplayName = LONG_NAME;
        root = mapper.readTree(builder.buildRoutePayload(r));
        assertFalse(root.has("name"));
        assertFalse(root.get("climbs").get(0).has("n"));
        assertFalse(mapper.readTree(builder.buildSurfaceSectionPayload(r)).has("name"));
    }

    @Test
    public void negativeGradientRoundsAwayFromZero() throws Exception {
        JsonNode c = mapper.readTree(builder.buildRoutePayload(route(climb(-0.0345))))
                .get("climbs").get(0);
        assertEquals(-35, c.get("ag").asInt());
        JsonNode p = mapper.readTree(builder.buildRoutePayload(route(climb(0.0345))))
                .get("climbs").get(0);
        assertEquals(35, p.get("ag").asInt());
    }

    @Test
    public void calibrationIsOnlySentWhenPresent() throws Exception {
        StoredClimb c = climb(0.05);
        c.calibrationPoints = new ArrayList<>();
        assertFalse(mapper.readTree(builder.buildRoutePayload(route(c)))
                .get("climbs").get(0).has("calib"));
        StoredCalibrationPoint p = new StoredCalibrationPoint();
        p.distanceFromClimbStart = 80;
        p.lat = 50.2;
        p.lon = 5.2;
        c.calibrationPoints.add(p);
        JsonNode calib = mapper.readTree(builder.buildRoutePayload(route(c)))
                .get("climbs").get(0).get("calib");
        assertEquals(3, calib.size());
        assertEquals(80, calib.get(0).asInt());
    }

    @Test
    public void flatStarredSectionsSkipUnknownSurfaceAndLongNames() throws Exception {
        StoredRoute r = route(climb(0.05));
        StoredStarredSegment named = starred(100, 300, SurfaceType.GRAVEL, "Grindpad");
        named.userDisplayName = "Mijn grind";
        r.starredSegments = Arrays.asList(
                starred(0, 50, SurfaceType.UNKNOWN, "onbekend"),
                named,
                starred(400, 600, SurfaceType.COBBLESTONE, LONG_NAME));
        JsonNode fss = mapper.readTree(builder.buildRoutePayload(r)).get("fss");
        assertEquals(2, fss.size());
        assertEquals("Mijn grind", fss.get(0).get("n").asText());
        assertFalse(fss.get(1).has("n"));

        r.starredSegments = Collections.singletonList(starred(0, 50, SurfaceType.UNKNOWN, "x"));
        assertFalse(mapper.readTree(builder.buildRoutePayload(r)).has("fss"));
        r.starredSegments = new ArrayList<>();
        assertFalse(mapper.readTree(builder.buildRoutePayload(r)).has("fss"));
    }

    @Test
    public void surfaceSectionPayloadIncludesSurfacedStarredSegmentsSorted() throws Exception {
        StoredRoute r = route();
        r.distances = new double[]{0, 500, 1000};
        r.lats = new double[]{50.0, 50.0045, 50.009};
        r.lons = new double[]{5.0, 5.0, 5.0};
        r.starredSegments = Arrays.asList(
                starred(600, 900, SurfaceType.GRAVEL, "Laat"),
                starred(100, 200, SurfaceType.COBBLESTONE, "Vroeg"),
                starred(0, 50, SurfaceType.UNKNOWN, "weg"));
        JsonNode sec = mapper.readTree(builder.buildSurfaceSectionPayload(r)).get("surfSec");
        assertEquals(2, sec.size());
        assertEquals("Vroeg", sec.get(0).get("n").asText());
        assertEquals(100, sec.get(0).get("s").asInt());
        assertEquals("Laat", sec.get(1).get("n").asText());
    }

    @Test
    public void checkpointsWithoutGeometryAreEmpty() {
        assertEquals(0, ClimbPayloadBuilder.buildCheckpoints(null, null, null, 0, 100).length);
        assertEquals(0, ClimbPayloadBuilder.buildCheckpoints(new double[0], new double[0],
                new double[0], 0, 100).length);
        assertEquals(0, ClimbPayloadBuilder.buildCheckpoints(new double[]{0, 1}, null,
                new double[]{5, 5}, 0, 1).length);
    }

    @Test
    public void longSectionIsThinnedToTheCapKeepingStartAndEnd() {
        int n = 101;
        double[] d = new double[n];
        double[] lat = new double[n];
        double[] lon = new double[n];
        for (int i = 0; i < n; i++) {
            d[i] = i * 100.0;
            lat[i] = 50 + i * 0.0009;
            lon[i] = 5;
        }
        int[] cp = ClimbPayloadBuilder.buildCheckpoints(d, lat, lon, 0, 10_000);
        assertEquals(ClimbPayloadBuilder.MAX_CHECKPOINTS_PER_SECTION * 3, cp.length);
        assertEquals(0, cp[0]);
        assertEquals(10_000, cp[cp.length - 3]);
        for (int i = 3; i < cp.length; i += 3) assertTrue(cp[i] > cp[i - 3]);
    }

    @Test
    public void sectionBeyondTheRouteIsClampedToItsEnd() {
        int[] cp = ClimbPayloadBuilder.buildCheckpoints(new double[]{0, 300},
                new double[]{50.0, 50.003}, new double[]{5, 5}, 500, 900);
        assertEquals(3, cp.length);
        assertEquals(300, cp[0]);
        assertEquals(5_000_300, cp[1]);
    }

    @Test
    public void hazardsAreDroppedBeforeAnOverBudgetPayloadIsReturned() throws Exception {
        StoredClimb[] many = new StoredClimb[40];
        for (int i = 0; i < many.length; i++) many[i] = climb(0.05);
        StoredRoute r = route(many);
        r.tunnels = Collections.singletonList(new StoredTunnel(100, 300));
        byte[] bytes = builder.buildRoutePayload(r);
        assertFalse(mapper.readTree(bytes).has(ClimbPayloadBuilder.KEY_HAZARDS));
        // Still over budget: the caller (RouteSyncWorker) decides not to send it.
        assertTrue(bytes.length > PayloadBudget.MAX_BYTES);

        // Within budget the tunnel ships as a hazard marker.
        StoredRoute small = route(climb(0.05));
        small.tunnels = r.tunnels;
        assertTrue(mapper.readTree(builder.buildRoutePayload(small))
                .has(ClimbPayloadBuilder.KEY_HAZARDS));
    }
}
