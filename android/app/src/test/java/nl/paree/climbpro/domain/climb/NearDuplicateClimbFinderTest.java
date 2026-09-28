package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import nl.paree.climbpro.data.route.StoredClimb;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class NearDuplicateClimbFinderTest {

    private static StoredClimb climb(double lat, double lon, int length, double gradient) {
        StoredClimb c = new StoredClimb();
        c.startLat = lat;
        c.startLon = lon;
        c.startDistance = 0;
        c.endDistance = length;
        c.length = length;
        c.avgGradient = gradient;
        return c;
    }

    private static NearDuplicateClimbFinder.RouteClimbs route(String id, String name, StoredClimb... climbs) {
        return new NearDuplicateClimbFinder.RouteClimbs(id, name, new ArrayList<>(Arrays.asList(climbs)));
    }

    @Test
    public void flagsClimbsThatStraddleClimbIdentityBucketBoundary() {
        // Physically ~111m apart, close length/gradient, but different ClimbIdentity keys —
        // exactly the "Known limitation" bucket-boundary gap ClimbIdentity's javadoc documents.
        StoredClimb a = climb(45.0005, 6.0000, 2000, 0.050);
        StoredClimb b = climb(45.0015, 6.0000, 2050, 0.052);
        assertTrue("test setup: the two climbs must NOT already share a ClimbIdentity key",
                !ClimbIdentity.of(a.startLat, a.startLon, a.length)
                        .equals(ClimbIdentity.of(b.startLat, b.startLon, b.length)));

        List<NearDuplicateClimbFinder.Candidate> result = NearDuplicateClimbFinder.find(Arrays.asList(
                route("r1", "Route A", a),
                route("r2", "Route B", b)));

        assertEquals(1, result.size());
    }

    @Test
    public void skipsPairsFromTheSameRoute() {
        StoredClimb a = climb(45.0005, 6.0000, 2000, 0.050);
        StoredClimb b = climb(45.0015, 6.0000, 2050, 0.052);

        List<NearDuplicateClimbFinder.Candidate> result = NearDuplicateClimbFinder.find(
                Collections.singletonList(route("r1", "Route A", a, b)));

        assertEquals(0, result.size());
    }

    @Test
    public void skipsClimbsOutsideTheRadius() {
        StoredClimb a = climb(45.0000, 6.0000, 2000, 0.050);
        StoredClimb b = climb(45.0500, 6.0000, 2000, 0.050); // ~5.5 km away

        List<NearDuplicateClimbFinder.Candidate> result = NearDuplicateClimbFinder.find(Arrays.asList(
                route("r1", "Route A", a),
                route("r2", "Route B", b)));

        assertEquals(0, result.size());
    }

    @Test
    public void skipsClimbsWithVeryDifferentLength_evenIfStartIsClose() {
        // A short spur right next to the start of a much longer climb must not be flagged.
        StoredClimb a = climb(45.0000, 6.0000, 900, 0.040);
        StoredClimb b = climb(45.0005, 6.0000, 9000, 0.040);

        List<NearDuplicateClimbFinder.Candidate> result = NearDuplicateClimbFinder.find(Arrays.asList(
                route("r1", "Route A", a),
                route("r2", "Route B", b)));

        assertEquals(0, result.size());
    }

    @Test
    public void skipsClimbsWithVeryDifferentGradient() {
        StoredClimb a = climb(45.0005, 6.0000, 2000, 0.035);
        StoredClimb b = climb(45.0015, 6.0000, 2050, 0.090);

        List<NearDuplicateClimbFinder.Candidate> result = NearDuplicateClimbFinder.find(Arrays.asList(
                route("r1", "Route A", a),
                route("r2", "Route B", b)));

        assertEquals(0, result.size());
    }

    @Test
    public void skipsClimbsThatAlreadyShareAClimbIdentityKey() {
        // Well within one ClimbIdentity bucket — the logbook already collapses these, so
        // flagging them here would just be noise on top of an existing automatic merge.
        StoredClimb a = climb(45.0000, 6.0000, 2000, 0.050);
        StoredClimb b = climb(45.0002, 6.0000, 2010, 0.050);
        assertEquals(ClimbIdentity.of(a.startLat, a.startLon, a.length),
                ClimbIdentity.of(b.startLat, b.startLon, b.length));

        List<NearDuplicateClimbFinder.Candidate> result = NearDuplicateClimbFinder.find(Arrays.asList(
                route("r1", "Route A", a),
                route("r2", "Route B", b)));

        assertEquals(0, result.size());
    }
}
