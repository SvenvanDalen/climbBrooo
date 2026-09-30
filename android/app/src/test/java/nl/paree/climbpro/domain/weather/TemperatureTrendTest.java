package nl.paree.climbpro.domain.weather;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.route.StoredRoute;

import org.junit.Test;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

public class TemperatureTrendTest {

    private static final Instant T10 = Instant.parse("2026-09-24T10:00:00Z");

    /** Three locations, hours 10:00..13:00 UTC. */
    private static TemperatureGrid grid(String a, String b, String c) throws IOException {
        String times = "\"time\":[\"2026-09-24T10:00\",\"2026-09-24T11:00\","
                + "\"2026-09-24T12:00\",\"2026-09-24T13:00\"]";
        return TemperatureGrid.parse("["
                + "{\"hourly\":{" + times + ",\"temperature_2m\":[" + a + "]}},"
                + "{\"hourly\":{" + times + ",\"temperature_2m\":[" + b + "]}},"
                + "{\"hourly\":{" + times + ",\"temperature_2m\":[" + c + "]}}]", 3);
    }

    private static List<RouteSampler.Sample> samples(double... distances) {
        List<RouteSampler.Sample> out = new ArrayList<>();
        for (double d : distances) out.add(new RouteSampler.Sample(d, 50, 5));
        return out;
    }

    @Test public void eachSampleUsesItsOwnLocationAtItsOwnArrivalTime() throws IOException {
        // Location 0 at 10:00 (10°), location 1 at 11:00 (15°), location 2 at 12:00 (20°).
        TemperatureGrid g = grid("10,0,0,0", "0,15,0,0", "0,0,20,0");
        TemperatureTrend t = TemperatureTrend.compute(
                samples(0, 25_000, 50_000), g, T10, 7200);
        assertEquals(3, t.points.size());
        assertEquals(10.0, t.points.get(0).celsius, 1e-9);
        assertEquals(15.0, t.points.get(1).celsius, 1e-9);
        assertEquals(20.0, t.points.get(2).celsius, 1e-9);
        assertEquals(Instant.parse("2026-09-24T11:00:00Z"), t.points.get(1).eta);
        assertEquals(10.0, t.minCelsius, 1e-9);
        assertEquals(20.0, t.maxCelsius, 1e-9);
        assertFalse(t.isEmpty());
    }

    @Test public void arrivalTimeIsProportionalToDistanceFromFirstSample() throws IOException {
        TemperatureGrid g = grid("10,10,10,10", "10,10,10,10", "10,10,10,10");
        TemperatureTrend t = TemperatureTrend.compute(
                samples(1_000, 2_000, 5_000), g, T10, 4000);
        assertEquals(T10, t.points.get(0).eta);
        assertEquals(T10.plusSeconds(1000), t.points.get(1).eta);
        assertEquals(T10.plusSeconds(4000), t.points.get(2).eta);
    }

    @Test public void pointsOutsideForecastAreDroppedAndCounted() throws IOException {
        TemperatureGrid g = grid("10,11,12,13", "10,11,12,13", "10,11,12,13");
        // Ride takes 6 h: the last sample arrives at 16:00, beyond the 13:00 forecast.
        TemperatureTrend t = TemperatureTrend.compute(
                samples(0, 10_000, 60_000), g, T10, 6 * 3600);
        assertEquals(2, t.points.size());
        assertEquals(1, t.missing);
    }

    @Test public void emptyWhenNothingKnown() throws IOException {
        TemperatureGrid g = grid("null,null,null,null", "null,null,null,null",
                "null,null,null,null");
        TemperatureTrend t = TemperatureTrend.compute(samples(0, 1, 2), g, T10, 60);
        assertTrue(t.isEmpty());
        assertEquals("Geen temperatuurverwachting beschikbaar voor dit tijdstip.",
                t.describe(ZoneOffset.UTC));
    }

    @Test public void describeMentionsStartFinishAndExtremes() throws IOException {
        TemperatureGrid g = grid("8,0,0,0", "0,21,0,0", "0,0,14,0");
        TemperatureTrend t = TemperatureTrend.compute(
                samples(0, 25_000, 50_000), g, T10, 7200);
        String s = t.describe(ZoneOffset.UTC);
        assertTrue(s, s.contains("Start 10:00: 8 °C"));
        assertTrue(s, s.contains("Finish 12:00: 14 °C"));
        assertTrue(s, s.contains("Warmst: 21 °C rond km 25 (11:00)"));
        assertTrue(s, s.contains("Koudst: 8 °C rond km 0 (10:00)"));
        assertTrue(s, s.contains("verschil 13 °C"));
    }

    @Test public void describeWarnsWhenPartOfTheRideIsBeyondTheForecast() throws IOException {
        TemperatureGrid g = grid("10,11,12,13", "10,11,12,13", "10,11,12,13");
        TemperatureTrend t = TemperatureTrend.compute(
                samples(0, 10_000, 60_000), g, T10, 6 * 3600);
        assertTrue(t.describe(ZoneOffset.UTC).contains("buiten de verwachting"));
    }

    @Test public void rideSecondsPrefersPlanAndFallsBackToDefaultSpeed() {
        assertEquals(5400, TemperatureTrend.rideSeconds(5400, 50_000));
        // 50 km at 25 km/h = 2 h
        assertEquals(7200, TemperatureTrend.rideSeconds(-1, 50_000));
        assertEquals(0, TemperatureTrend.rideSeconds(-1, 0));
    }

    @Test public void nextStartIsTodayOrTomorrow() {
        ZoneId zone = ZoneOffset.UTC;
        Instant now = Instant.parse("2026-09-24T10:30:00Z");
        assertEquals(Instant.parse("2026-09-24T14:00:00Z"),
                TemperatureTrend.nextStart(now, 14, 0, zone));
        // A time already past today means tomorrow.
        assertEquals(Instant.parse("2026-09-25T08:00:00Z"),
                TemperatureTrend.nextStart(now, 8, 0, zone));
        // Within the current minute counts as "now", not tomorrow.
        assertEquals(Instant.parse("2026-09-24T10:30:00Z"),
                TemperatureTrend.nextStart(now, 10, 30, zone));
    }

    @Test public void elevationsAreInterpolatedAlongTheRoute() {
        StoredRoute r = new StoredRoute();
        r.distances = new double[]{0, 1000, 2000};
        r.elevations = new double[]{100, 200, 400};
        double[] e = TemperatureTrend.elevationsAt(r, samples(0, 500, 1500, 2000));
        assertEquals(100, e[0], 1e-9);
        assertEquals(150, e[1], 1e-9);
        assertEquals(300, e[2], 1e-9);
        assertEquals(400, e[3], 1e-9);
    }

    @Test public void elevationsAreNaNWithoutElevationData() {
        StoredRoute r = new StoredRoute();
        r.distances = new double[]{0, 1000};
        double[] e = TemperatureTrend.elevationsAt(r, samples(0, 500));
        assertTrue(Double.isNaN(e[0]));
        assertTrue(Double.isNaN(e[1]));
    }
}
