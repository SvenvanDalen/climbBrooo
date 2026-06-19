package nl.paree.climbpro.protocol;

import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import org.junit.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import nl.paree.climbpro.data.route.StoredCalibrationPoint;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.data.route.StoredSurfaceSection;
import nl.paree.climbpro.domain.segment.SurfaceType;
import nl.paree.climbpro.service.ClimbPayloadBuilder;

/**
 * Guards the real wire contract against drift. {@code protocol/schema.json} describes
 * the actual v3 packed wire format; this test asserts that
 *   1. every committed example in {@code protocol/examples/} validates against it, and
 *   2. the bytes {@link ClimbPayloadBuilder} actually emits validate against it too.
 *
 * (2) is the load-bearing check: if the builder's wire format drifts from the schema
 * — a new key, a changed packing, a version bump — this test fails, forcing the schema,
 * the examples, and (by the documented discipline) the hand-written Monkey C parser to
 * be updated together. The Monkey C side has no JVM test harness and stays review-only.
 */
public class ProtocolRoundTripTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String[] EXAMPLES = {
            "route_mode_short.json",
            "route_mode_full.json",
            "radius_mode.json",
            "route_mode_surface.json",
    };

    @Test
    public void examplesValidateAgainstSchema() throws Exception {
        for (String file : EXAMPLES) {
            try (InputStream in = resource("/examples/" + file)) {
                assertValid(MAPPER.readTree(in), file);
            }
        }
    }

    @Test
    public void builderRoutePayloadValidatesAgainstSchema() throws Exception {
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(MAPPER);
        int[][] targets = {{60, 62, 64, 66}}; // one per segment → emits 'tsec'
        JsonNode payload = MAPPER.readTree(b.buildRoutePayload(routeFixture(), targets));
        assertValid(payload, "route payload");
    }

    @Test
    public void builderRadiusPayloadValidatesAgainstSchema() throws Exception {
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(MAPPER);
        JsonNode payload = MAPPER.readTree(b.buildRadiusPayload(routeFixture().climbs));
        assertValid(payload, "radius payload");
    }

    @Test
    public void builderSurfacePayloadValidatesAgainstSchema() throws Exception {
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(MAPPER);
        JsonNode payload = MAPPER.readTree(b.buildSurfaceSectionPayload(surfaceFixture()));
        assertValid(payload, "surface payload");
    }

    // ---- fixtures --------------------------------------------------------

    private static StoredRoute routeFixture() {
        StoredRoute route = new StoredRoute();
        route.routeId = "fixture_route";
        route.name = "Fixture";

        StoredClimb c = new StoredClimb();
        c.startDistance = 1000;
        c.endDistance = 3000;
        c.length = 2000;
        c.elevationGain = 80;
        c.avgGradient = 0.04;
        c.startLat = 51.5;
        c.startLon = 5.1;
        c.name = "Fixture Climb";
        c.segments = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            StoredSegment s = new StoredSegment();
            s.distance = 500;
            s.elevationGain = 20;
            s.gradient = 0.04;
            s.colorIndex = 1;
            s.surfaceType = (i == 0) ? SurfaceType.GRAVEL : SurfaceType.UNKNOWN;
            c.segments.add(s);
        }
        c.calibrationPoints = new ArrayList<>();
        StoredCalibrationPoint cp = new StoredCalibrationPoint();
        cp.distanceFromClimbStart = 1000;
        cp.lat = 51.51;
        cp.lon = 5.11;
        c.calibrationPoints.add(cp);

        route.climbs = new ArrayList<>(Arrays.asList(c));
        return route;
    }

    private static StoredRoute surfaceFixture() {
        StoredRoute route = new StoredRoute();
        route.routeId = "fixture_surface";
        route.name = "Surface Fixture";
        route.distances = new double[]{0, 200, 1200, 3000, 4000};
        route.lats = new double[]{51.0, 51.001, 51.01, 51.03, 51.04};
        route.lons = new double[]{5.0, 5.001, 5.01, 5.03, 5.04};
        StoredSurfaceSection s = new StoredSurfaceSection();
        s.startDistance = 0;
        s.endDistance = 1200;
        s.surfaceType = SurfaceType.GRAVEL;
        s.name = "Gravel sector";
        route.surfaceSections = new ArrayList<>(Arrays.asList(s));
        return route;
    }

    // ---- helpers ---------------------------------------------------------

    private static void assertValid(JsonNode node, String what) {
        Set<ValidationMessage> errors = schema().validate(node);
        assertTrue(what + " must validate against schema.json but did not: " + errors,
                errors.isEmpty());
    }

    private static JsonSchema schema() {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        try (InputStream in = resource("/schema.json")) {
            return factory.getSchema(in);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load /schema.json from test classpath", e);
        }
    }

    private static InputStream resource(String path) {
        InputStream in = ProtocolRoundTripTest.class.getResourceAsStream(path);
        if (in == null) {
            throw new IllegalStateException("Missing test resource: " + path);
        }
        return in;
    }
}
