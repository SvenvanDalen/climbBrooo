package nl.paree.climbpro.domain.weather;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.domain.power.WindImpactEstimator;

import org.junit.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;

public class ClimbWindImpactTest {

    private static final Instant NOON = Instant.parse("2026-09-24T12:20:00Z");

    /** Straight north, 2 km. */
    private static StoredRoute northRoute() {
        StoredRoute r = new StoredRoute();
        r.lats = new double[]{50.0, 50.009, 50.018};
        r.lons = new double[]{5.0, 5.0, 5.0};
        r.distances = new double[]{0, 1_000, 2_000};
        return r;
    }

    private static StoredClimb climb() {
        StoredClimb c = new StoredClimb();
        c.startDistance = 0;
        c.endDistance = 2_000;
        c.segments = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            StoredSegment s = new StoredSegment();
            s.distance = 1_000;
            s.gradient = 0.07;
            c.segments.add(s);
        }
        return c;
    }

    private static HourlyForecast forecast(String dirAtNoon) throws IOException {
        return HourlyForecast.parse("{\"hourly\":{"
                + "\"time\":[\"2026-09-24T11:00\",\"2026-09-24T12:00\"],"
                + "\"wind_speed_10m\":[5.0,25.0],"
                + "\"wind_direction_10m\":[90," + dirAtNoon + "]}}");
    }

    @Test public void usesTheForecastHourContainingTheInstant() throws IOException {
        WindImpactEstimator.Result r = ClimbWindImpact.compute(
                northRoute(), climb(), forecast("0"), NOON, 80, 250);
        assertNotNull(r);
        assertEquals(25, r.windKmh, 1e-9);
        assertEquals(0, r.windFromDeg, 1e-9);
        assertEquals(25, r.meanHeadwindKmh, 0.1);
        assertTrue(r.deltaSeconds > 0);
    }

    @Test public void northerlyTailwindForASouthboundClimb() throws IOException {
        WindImpactEstimator.Result r = ClimbWindImpact.compute(
                northRoute(), climb(), forecast("180"), NOON, 80, 250);
        assertTrue(r.deltaSeconds < 0);
    }

    @Test public void outsideTheForecastGivesNull() throws IOException {
        assertNull(ClimbWindImpact.compute(northRoute(), climb(), forecast("0"),
                Instant.parse("2026-09-25T12:00:00Z"), 80, 250));
    }

    @Test public void missingDirectionGivesNull() throws IOException {
        assertNull(ClimbWindImpact.compute(northRoute(), climb(), forecast("null"), NOON, 80, 250));
    }

    @Test public void missingForecastOrSegmentsGivesNull() throws IOException {
        assertNull(ClimbWindImpact.compute(northRoute(), climb(), null, NOON, 80, 250));
        StoredClimb empty = climb();
        empty.segments = null;
        assertNull(ClimbWindImpact.compute(northRoute(), empty, forecast("0"), NOON, 80, 250));
    }

    @Test public void routeWithoutGeometryGivesNull() throws IOException {
        assertNull(ClimbWindImpact.compute(new StoredRoute(), climb(), forecast("0"), NOON, 80, 250));
    }
}
