package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.route.RouteCatalogEntry;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class DuplicateClimbMatcherTest {

    private static final double BASE_LAT = 45.83210;
    private static final double BASE_LON = 6.86420;
    private static final double METERS_PER_DEG_LAT = 111_320.0;

    /** Approximate north offset by {@code meters}, accurate enough for small test distances. */
    private static double northOf(double lat, double meters) {
        return lat + meters / METERS_PER_DEG_LAT;
    }

    private static Climb climbAt(double lat, double lon) {
        return Climb.builder()
                .startDistance(0).endDistance(1000).length(1000)
                .elevationGain(50).avgGradient(0.05)
                .startLat(lat).startLon(lon)
                .build();
    }

    private static RouteCatalogEntry entryWithClimb(String routeId, String name, double lat, double lon) {
        RouteCatalogEntry e = new RouteCatalogEntry();
        e.routeId = routeId;
        e.name = name;
        e.climbStartCoords = new double[]{lat, lon};
        return e;
    }

    @Test
    public void emptyCatalog_noMatches() {
        List<Climb> climbs = Collections.singletonList(climbAt(BASE_LAT, BASE_LON));
        List<DuplicateClimbMatcher.Match> matches =
                DuplicateClimbMatcher.findDuplicates(climbs, Collections.emptyList(), 150.0);
        assertTrue(matches.isEmpty());
    }

    @Test
    public void nullInputs_returnEmptyList_notNull() {
        assertTrue(DuplicateClimbMatcher.findDuplicates(null, Collections.emptyList(), 150.0).isEmpty());
        assertTrue(DuplicateClimbMatcher.findDuplicates(Collections.emptyList(), null, 150.0).isEmpty());
    }

    @Test
    public void climbWithoutCoordinates_isSkipped() {
        Climb noCoords = Climb.builder().startDistance(0).endDistance(1000).length(1000).build();
        List<RouteCatalogEntry> catalog = Collections.singletonList(
                entryWithClimb("r1", "Route 1", BASE_LAT, BASE_LON));

        List<DuplicateClimbMatcher.Match> matches =
                DuplicateClimbMatcher.findDuplicates(Collections.singletonList(noCoords), catalog, 150.0);
        assertTrue(matches.isEmpty());
    }

    @Test
    public void climbWithinRadius_matches() {
        double lat = northOf(BASE_LAT, 100); // 100 m north — inside a 150 m radius
        Climb climb = climbAt(lat, BASE_LON);
        List<RouteCatalogEntry> catalog = Collections.singletonList(
                entryWithClimb("r1", "Alpe d'Huez", BASE_LAT, BASE_LON));

        List<DuplicateClimbMatcher.Match> matches =
                DuplicateClimbMatcher.findDuplicates(Collections.singletonList(climb), catalog, 150.0);

        assertEquals(1, matches.size());
        assertEquals("r1", matches.get(0).existingRoute.routeId);
        assertTrue(matches.get(0).distanceM <= 150.0);
    }

    @Test
    public void climbJustOutsideRadius_noMatch() {
        double lat = northOf(BASE_LAT, 160); // 160 m north — outside a 150 m radius
        Climb climb = climbAt(lat, BASE_LON);
        List<RouteCatalogEntry> catalog = Collections.singletonList(
                entryWithClimb("r1", "Alpe d'Huez", BASE_LAT, BASE_LON));

        List<DuplicateClimbMatcher.Match> matches =
                DuplicateClimbMatcher.findDuplicates(Collections.singletonList(climb), catalog, 150.0);

        assertTrue(matches.isEmpty());
    }

    @Test
    public void climbExactlyAtRadiusBoundary_matchesInclusive() {
        // findDuplicates uses <= radiusM, so a point placed (approximately) exactly on the
        // boundary must still match.
        double lat = northOf(BASE_LAT, 150);
        Climb climb = climbAt(lat, BASE_LON);
        List<RouteCatalogEntry> catalog = Collections.singletonList(
                entryWithClimb("r1", "Alpe d'Huez", BASE_LAT, BASE_LON));

        List<DuplicateClimbMatcher.Match> matches =
                DuplicateClimbMatcher.findDuplicates(Collections.singletonList(climb), catalog, 150.0);

        assertEquals(1, matches.size());
    }

    @Test
    public void multipleCandidates_picksNearest() {
        Climb climb = climbAt(BASE_LAT, BASE_LON);
        RouteCatalogEntry far  = entryWithClimb("far",  "Far Route",  northOf(BASE_LAT, 120), BASE_LON);
        RouteCatalogEntry near = entryWithClimb("near", "Near Route", northOf(BASE_LAT, 50),  BASE_LON);
        List<RouteCatalogEntry> catalog = Arrays.asList(far, near);

        List<DuplicateClimbMatcher.Match> matches =
                DuplicateClimbMatcher.findDuplicates(Collections.singletonList(climb), catalog, 150.0);

        assertEquals(1, matches.size());
        assertEquals("near", matches.get(0).existingRoute.routeId);
    }

    @Test
    public void multipleCoordsInSingleEntry_matchesOnAnyOne() {
        RouteCatalogEntry entry = new RouteCatalogEntry();
        entry.routeId = "r1";
        entry.name = "Multi-climb route";
        // Two climbs in one route: one far away, one right next to the new climb.
        entry.climbStartCoords = new double[]{
                northOf(BASE_LAT, 5000), BASE_LON,
                northOf(BASE_LAT, 30),   BASE_LON
        };
        Climb climb = climbAt(BASE_LAT, BASE_LON);

        List<DuplicateClimbMatcher.Match> matches = DuplicateClimbMatcher.findDuplicates(
                Collections.singletonList(climb), Collections.singletonList(entry), 150.0);

        assertEquals(1, matches.size());
        assertTrue(matches.get(0).distanceM < 100);
    }

    @Test
    public void multipleNewClimbs_eachMatchedIndependently() {
        Climb dup    = climbAt(northOf(BASE_LAT, 20), BASE_LON);   // close to r1
        Climb unique = climbAt(46.5, 7.9);                          // far from everything

        List<RouteCatalogEntry> catalog = Collections.singletonList(
                entryWithClimb("r1", "Route 1", BASE_LAT, BASE_LON));

        List<DuplicateClimbMatcher.Match> matches = DuplicateClimbMatcher.findDuplicates(
                new ArrayList<>(Arrays.asList(dup, unique)), catalog, 150.0);

        assertEquals(1, matches.size());
        assertEquals(dup, matches.get(0).newClimb);
    }

    @Test
    public void zeroRadius_requiresExactMatch() {
        Climb exact = climbAt(BASE_LAT, BASE_LON);
        Climb near  = climbAt(northOf(BASE_LAT, 1), BASE_LON);
        List<RouteCatalogEntry> catalog = Collections.singletonList(
                entryWithClimb("r1", "Route 1", BASE_LAT, BASE_LON));

        assertEquals(1, DuplicateClimbMatcher.findDuplicates(
                Collections.singletonList(exact), catalog, 0.0).size());
        assertTrue(DuplicateClimbMatcher.findDuplicates(
                Collections.singletonList(near), catalog, 0.0).isEmpty());
    }
}
