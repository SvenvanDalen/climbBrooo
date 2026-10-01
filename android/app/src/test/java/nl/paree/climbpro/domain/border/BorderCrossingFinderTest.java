package nl.paree.climbpro.domain.border;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.FileInputStream;
import java.io.InputStream;

public class BorderCrossingFinderTest {

    /** Countries split on longitude: lon < 1 = AA, 1 <= lon < 2 = BB, else CC. */
    private static final BorderCrossingFinder.CountryLookup STRIPES = (lat, lon) ->
            lon < 1.0 ? "AA" : lon < 2.0 ? "BB" : "CC";

    /** Straight west-east line along the equator, one point per 0.1 degree. */
    private static double[][] line(double fromLon, double toLon) {
        int n = (int) Math.round((toLon - fromLon) / 0.1) + 1;
        double[] lats = new double[n], lons = new double[n], dist = new double[n];
        for (int i = 0; i < n; i++) {
            lats[i] = 0;
            lons[i] = fromLon + i * 0.1;
            // ~111.195 km per degree of longitude at the equator
            dist[i] = (lons[i] - fromLon) * 111_195.0;
        }
        return new double[][] {lats, lons, dist};
    }

    @Test
    public void findsCrossingsWithMetreAccuracy() {
        double[][] r = line(0.5, 2.5);
        BorderCrossingFinder.Result res = BorderCrossingFinder.find(r[0], r[1], r[2], STRIPES);
        assertEquals("AA", res.startCountry);
        assertEquals(2, res.crossings.size());
        BorderCrossing first = res.crossings.get(0);
        assertEquals("AA", first.fromCountry);
        assertEquals("BB", first.toCountry);
        assertEquals(0.5 * 111_195.0, first.distanceM, 20);
        assertEquals(1.0, first.lon, 0.001);
        BorderCrossing second = res.crossings.get(1);
        assertEquals("BB", second.fromCountry);
        assertEquals("CC", second.toCountry);
        assertEquals(1.5 * 111_195.0, second.distanceM, 20);
    }

    @Test
    public void noCrossingInsideOneCountry() {
        double[][] r = line(0.1, 0.9);
        BorderCrossingFinder.Result res = BorderCrossingFinder.find(r[0], r[1], r[2], STRIPES);
        assertEquals("AA", res.startCountry);
        assertTrue(res.crossings.isEmpty());
    }

    @Test
    public void shortExcursionAlongBorderIsSuppressed() {
        // BB only in a 300 m wide strip: lon in [1.0, 1.0027)
        BorderCrossingFinder.CountryLookup lookup = (lat, lon) ->
                lon >= 1.0 && lon < 1.0027 ? "BB" : "AA";
        double[][] r = line(0.5, 1.5);
        BorderCrossingFinder.Result res = BorderCrossingFinder.find(r[0], r[1], r[2], lookup);
        assertTrue(res.crossings.isEmpty());
    }

    @Test
    public void unknownStretchLikeSeaDoesNotCreateCrossings() {
        // Sea (null) between two parts of AA, then BB
        BorderCrossingFinder.CountryLookup lookup = (lat, lon) ->
                lon > 1.0 && lon < 1.3 ? null : lon >= 2.0 ? "BB" : "AA";
        double[][] r = line(0.5, 2.5);
        BorderCrossingFinder.Result res = BorderCrossingFinder.find(r[0], r[1], r[2], lookup);
        assertEquals(1, res.crossings.size());
        assertEquals("BB", res.crossings.get(0).toCountry);
        assertEquals(1.5 * 111_195.0, res.crossings.get(0).distanceM, 20);
    }

    @Test
    public void startInSeaTakesFirstKnownCountry() {
        BorderCrossingFinder.CountryLookup lookup = (lat, lon) -> lon < 1.0 ? null : "AA";
        double[][] r = line(0.5, 1.5);
        BorderCrossingFinder.Result res = BorderCrossingFinder.find(r[0], r[1], r[2], lookup);
        assertEquals("AA", res.startCountry);
        assertTrue(res.crossings.isEmpty());
    }

    @Test
    public void emptyOrMismatchedInputIsSafe() {
        BorderCrossingFinder.Result res = BorderCrossingFinder.find(null, null, null, STRIPES);
        assertNull(res.startCountry);
        assertTrue(res.crossings.isEmpty());
        res = BorderCrossingFinder.find(new double[] {0}, new double[] {0, 1}, new double[] {0},
                STRIPES);
        assertTrue(res.crossings.isEmpty());
    }

    @Test
    public void realAssetMaastrichtToLiege() throws Exception {
        CountryPolygons polygons;
        try (InputStream in = new FileInputStream(CountryPolygonsTest.asset())) {
            polygons = CountryPolygons.parse(in);
        }
        // Maastricht (NL) -> Luik (BE), straight line in 60 points
        int n = 60;
        double[] lats = new double[n], lons = new double[n], dist = new double[n];
        double aLat = 50.85, aLon = 5.69, bLat = 50.63, bLon = 5.57;
        for (int i = 0; i < n; i++) {
            double f = i / (double) (n - 1);
            lats[i] = aLat + f * (bLat - aLat);
            lons[i] = aLon + f * (bLon - aLon);
            dist[i] = i == 0 ? 0 : dist[i - 1] + nl.paree.climbpro.domain.route.CumulativeDistance.haversine(
                    lats[i - 1], lons[i - 1], lats[i], lons[i]);
        }
        BorderCrossingFinder.Result res =
                BorderCrossingFinder.find(lats, lons, dist, polygons::countryAt);
        assertEquals("NL", res.startCountry);
        assertEquals(1, res.crossings.size());
        assertEquals("BE", res.crossings.get(0).toCountry);
        // The border lies roughly 6-12 km south of Maastricht centre on this line
        assertTrue(res.crossings.get(0).distanceM > 4_000);
        assertTrue(res.crossings.get(0).distanceM < 14_000);
    }
}
