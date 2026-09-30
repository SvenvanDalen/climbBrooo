package nl.paree.climbpro.domain.poi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class RoutePoiLocatorTest {

    /** Metres per degree of latitude with the haversine radius used by the app. */
    private static final double M_PER_DEG_LAT = Math.toRadians(1) * 6_371_000.0;

    @Test
    public void fixture_placedOnRoute_filteredDedupedAndSortedByKm() throws IOException {
        double[][] route = PoiFixtures.northboundRoute();
        List<RoutePoi> pois = RoutePoiLocator.locate(
                OverpassResponseParser.parse(PoiFixtures.overpassResponse()),
                route[0], route[1], 300);

        // "Ver weg" (~1.4 km off) dropped; node/11 merged into way/10 (same castle).
        assertEquals(5, pois.size());
        assertEquals("relation/7", pois.get(0).osmRef);
        assertEquals("way/10", pois.get(1).osmRef);
        assertEquals("node/1", pois.get(2).osmRef);
        assertEquals("node/2", pois.get(3).osmRef);
        assertEquals("node/5", pois.get(4).osmRef);

        RoutePoi view = pois.get(2);
        assertEquals(0.02 * M_PER_DEG_LAT, view.distanceAlongM, 1.0);
        double expectedOffset = Math.toRadians(0.001) * Math.cos(Math.toRadians(50.82))
                * 6_371_000.0;
        assertEquals(expectedOffset, view.offsetM, 0.5);

        RoutePoi ruins = pois.get(0);
        assertEquals(0.005 * M_PER_DEG_LAT, ruins.distanceAlongM, 1.0);
        assertEquals(211, ruins.offsetM, 1.5);

        RoutePoi artwork = pois.get(4);
        assertEquals(0, artwork.offsetM, 0.01);
        assertEquals("km 5,0 · op de route", artwork.positionText());
        assertEquals("Uitzichtpunt", pois.get(3).displayName());
    }

    @Test
    public void project_pointBeyondRouteEnd_clampsToLastPoint() {
        double[][] route = PoiFixtures.northboundRoute();
        double[] cum = RoutePoiLocator.cumulative(route[0], route[1]);
        double[] pr = RoutePoiLocator.project(route[0], route[1], cum, 50.851, 5.70);
        assertEquals(cum[cum.length - 1], pr[0], 0.01);
        assertEquals(0.001 * M_PER_DEG_LAT, pr[1], 0.5);
    }

    @Test
    public void outAndBack_showsPoiAtFirstPass() {
        // North 0.02° then back south over the same line.
        double[] lats = {50.80, 50.81, 50.82, 50.81, 50.80};
        double[] lons = {5.70, 5.70, 5.70, 5.70, 5.70};
        List<PoiCandidate> c = new ArrayList<>();
        c.add(new PoiCandidate("node/1", "Toren", PoiType.MONUMENT, 50.805, 5.7005));
        List<RoutePoi> pois = RoutePoiLocator.locate(c, lats, lons, 300);
        assertEquals(1, pois.size());
        assertEquals(0.005 * M_PER_DEG_LAT, pois.get(0).distanceAlongM, 1.0);
    }

    @Test
    public void dedupe_keepsDistinctNamesAndFarApartTwins() {
        double[][] route = PoiFixtures.northboundRoute();
        List<PoiCandidate> c = new ArrayList<>();
        c.add(new PoiCandidate("node/1", "Kerk", PoiType.MONUMENT, 50.81, 5.7001));
        c.add(new PoiCandidate("node/2", "Molen", PoiType.MONUMENT, 50.8101, 5.7001));
        c.add(new PoiCandidate("node/3", "Kerk", PoiType.MONUMENT, 50.83, 5.7001));
        c.add(new PoiCandidate("node/4", null, PoiType.VIEWPOINT, 50.84, 5.7001));
        c.add(new PoiCandidate("node/5", null, PoiType.VIEWPOINT, 50.8401, 5.7002));
        c.add(new PoiCandidate("node/4", null, PoiType.VIEWPOINT, 50.84, 5.7001));
        List<RoutePoi> pois = RoutePoiLocator.locate(c, route[0], route[1], 300);
        assertEquals(4, pois.size());
        assertEquals("node/4", pois.get(3).osmRef);
    }

    @Test
    public void emptyOrBrokenRoute_givesNothing() {
        List<PoiCandidate> c = new ArrayList<>();
        c.add(new PoiCandidate("node/1", "X", PoiType.MONUMENT, 50.8, 5.7));
        assertTrue(RoutePoiLocator.locate(c, new double[0], new double[0], 300).isEmpty());
        assertTrue(RoutePoiLocator.locate(c, null, null, 300).isEmpty());
        assertTrue(RoutePoiLocator.locate(c, new double[]{1, 2}, new double[]{1}, 300).isEmpty());
        assertEquals(1, RoutePoiLocator.locate(c, new double[]{50.8}, new double[]{5.7}, 300)
                .size());
    }
}
