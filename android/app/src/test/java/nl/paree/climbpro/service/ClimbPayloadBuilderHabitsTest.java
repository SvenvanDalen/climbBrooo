package nl.paree.climbpro.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.ride.StoredRideStreamStats;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.domain.climb.ClimbIdentity;
import nl.paree.climbpro.domain.segment.GradientColor;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Issues #27 ('nw'), #18 ('cg'), #24 ('hg') and #9 ('ord'): the rider-history keys and the
 * ordered day-trip flag, plus {@link WatchHabits}.
 */
public class ClimbPayloadBuilderHabitsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static StoredClimb climb(double lat) {
        StoredClimb c = new StoredClimb();
        c.startDistance = 1000;
        c.endDistance = 2000;
        c.length = 1000;
        c.elevationGain = 70;
        c.avgGradient = 0.07;
        c.startLat = lat;
        c.startLon = 5.83;
        c.name = "Klim";
        c.segments = new ArrayList<>();
        StoredSegment s = new StoredSegment();
        s.distance = 1000;
        s.elevationGain = 70;
        s.gradient = 0.07;
        s.colorIndex = GradientColor.forGradient(0.07);
        c.segments.add(s);
        return c;
    }

    private static StoredRoute route(StoredClimb... climbs) {
        StoredRoute r = new StoredRoute();
        r.routeId = "r1";
        r.name = "R1";
        r.climbs = new ArrayList<>(Arrays.asList(climbs));
        return r;
    }

    private static JsonNode json(byte[] bytes) throws Exception {
        return MAPPER.readTree(bytes);
    }

    @Test
    public void newClimbFlaggedOnlyWhenNeverRidden() throws Exception {
        StoredClimb ridden = climb(50.85);
        StoredClimb fresh = climb(50.95);
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(MAPPER)
                .withRiddenClimbIds(new HashSet<>(Collections.singletonList(ClimbIdentity.of(ridden))));
        JsonNode climbs = json(b.buildRoutePayload(route(ridden, fresh))).get("climbs");
        assertFalse(climbs.get(0).has("nw"));
        assertEquals(1, climbs.get(1).get("nw").asInt());
    }

    @Test
    public void noHistoryMeansNoNewFlags() throws Exception {
        byte[] plain = new ClimbPayloadBuilder(MAPPER).buildRoutePayload(route(climb(50.85)));
        byte[] empty = new ClimbPayloadBuilder(MAPPER).withRiddenClimbIds(new HashSet<>())
                .buildRoutePayload(route(climb(50.85)));
        assertArrayEquals(plain, empty);
        assertFalse(json(plain).get("climbs").get(0).has("nw"));
    }

    @Test
    public void gradeHabitsOnRouteAndRadiusPayloads() throws Exception {
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(MAPPER)
                .withGradeHabits(new int[]{92, 88, 0, 80, 76, 300}, new int[]{2, 3, 3, 4, 5, 0});
        JsonNode r = json(b.buildRoutePayload(route(climb(50.85))));
        assertEquals("[92,88,0,80,76,250]", r.get("cg").toString());   // clamped to 250
        assertEquals("[2,3,3,4,5,0]", r.get("hg").toString());
        JsonNode radius = json(b.buildRadiusPayload(Collections.singletonList(climb(50.85))));
        assertTrue(radius.has("cg"));
        assertTrue(radius.has("hg"));
    }

    @Test
    public void gradeHabitsOmittedWhenUnknown() throws Exception {
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(MAPPER)
                .withGradeHabits(new int[]{0, 0, 0, 0, 0, 0}, new int[]{1, 2});
        JsonNode r = json(b.buildRoutePayload(route(climb(50.85))));
        assertFalse(r.has("cg"));
        assertFalse(r.has("hg"));
        assertNull(ClimbPayloadBuilder.gradeClasses(null, 5));
    }

    @Test
    public void orderedRadiusPayloadCarriesOrd() throws Exception {
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(MAPPER);
        List<StoredClimb> climbs = Arrays.asList(climb(50.85), climb(50.95));
        assertEquals(1, json(b.buildRadiusPayload(climbs, true)).get("ord").asInt());
        assertFalse(json(b.buildRadiusPayload(climbs)).has("ord"));
    }

    @Test
    public void watchHabits_computeAndSignature() {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = "1:2:3";
        StoredRide ride = new StoredRide();
        ride.activityId = 7L;
        StoredRideStreamStats st = new StoredRideStreamStats();
        st.activityId = 7L;
        st.cadenceGradeSec = new int[]{600, 0, 0, 0, 0, 0};
        st.cadenceGradeRevs = new int[]{900, 0, 0, 0, 0, 0};
        st.hrGradeSec = new int[]{600, 0, 0, 0, 0, 0};
        st.hrGradeBeats = new int[]{1500, 0, 0, 0, 0, 0};
        Map<Long, StoredRideStreamStats> stats = new HashMap<>();
        stats.put(7L, st);

        WatchHabits h = WatchHabits.compute(Collections.singletonList(a),
                Collections.singletonList(ride), stats, 200);
        assertTrue(h.riddenClimbIds.contains("1:2:3"));
        assertEquals(90, h.cadenceByGrade[0]);
        assertEquals(3, h.hrZoneByGrade[0]);   // 150 bpm of 200 = 75 % -> zone 3
        assertNotEquals("", h.signature());
        assertEquals("", WatchHabits.NONE.signature());
        assertEquals("", WatchHabits.compute(null, null, null, 0).signature());
    }
}
