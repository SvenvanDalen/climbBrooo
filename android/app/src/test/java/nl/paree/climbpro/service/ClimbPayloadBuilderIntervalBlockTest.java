package nl.paree.climbpro.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredIntervalBlock;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.domain.power.IntervalBlock;
import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.*;

/** Issue #180: optional per-climb 'ib' = [targetW, lowW, highW]. */
public class ClimbPayloadBuilderIntervalBlockTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static StoredRoute route(StoredIntervalBlock block) {
        StoredRoute route = new StoredRoute();
        route.routeId = "r1";
        route.name = "R1";
        route.climbs = new ArrayList<>();
        StoredClimb c = new StoredClimb();
        c.startDistance = 1000;
        c.endDistance = 3000;
        c.length = 2000;
        c.elevationGain = 80;
        c.avgGradient = 0.04;
        c.segments = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            StoredSegment s = new StoredSegment();
            s.distance = 500;
            s.elevationGain = 20;
            s.gradient = 0.04;
            s.colorIndex = 2;
            c.segments.add(s);
        }
        c.intervalBlock = block;
        route.climbs.add(c);
        return route;
    }

    private JsonNode climb(ClimbPayloadBuilder b, StoredRoute r) throws Exception {
        return mapper.readTree(b.buildRoutePayload(r)).get("climbs").get(0);
    }

    @Test
    public void ibEmittedAsWattsWhenBlockAndFtpSet() throws Exception {
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper).withFtpWatts(280);
        JsonNode ib = climb(b, route(IntervalBlock.of(IntervalBlock.Preset.DREMPEL).toStored()))
                .get("ib");
        assertNotNull(ib);
        assertEquals(3, ib.size());
        assertEquals(273, ib.get(0).asInt());
        assertEquals(266, ib.get(1).asInt());
        assertEquals(280, ib.get(2).asInt());
    }

    @Test
    public void ibOmittedWithoutBlock() throws Exception {
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper).withFtpWatts(280);
        assertFalse(climb(b, route(null)).has("ib"));
    }

    @Test
    public void ibOmittedWithoutFtp() throws Exception {
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        assertFalse(climb(b, route(IntervalBlock.of(IntervalBlock.Preset.TEMPO).toStored()))
                .has("ib"));
    }

    @Test
    public void ibOmittedForInvalidStoredBlock() throws Exception {
        StoredIntervalBlock broken = new StoredIntervalBlock();
        broken.lowPct = 120;
        broken.highPct = 90;
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper).withFtpWatts(280);
        assertFalse(climb(b, route(broken)).has("ib"));
    }

    @Test
    public void ibAlsoInSingleClimbAndRadiusPayloads() throws Exception {
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper).withFtpWatts(250);
        StoredRoute r = route(IntervalBlock.custom(100).toStored());
        assertTrue(mapper.readTree(b.buildSingleClimbPayload(r, 0))
                .get("climbs").get(0).has("ib"));
        assertTrue(mapper.readTree(b.buildRadiusPayload(r.climbs))
                .get("climbs").get(0).has("ib"));
    }
}
