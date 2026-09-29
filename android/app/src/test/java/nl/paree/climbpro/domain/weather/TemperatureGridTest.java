package nl.paree.climbpro.domain.weather;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.time.Instant;

public class TemperatureGridTest {

    static String location(String temps) {
        return "{\"latitude\":50.0,\"longitude\":5.0,\"hourly\":{"
                + "\"time\":[\"2026-09-24T10:00\",\"2026-09-24T11:00\",\"2026-09-24T12:00\"],"
                + "\"temperature_2m\":[" + temps + "]}}";
    }

    @Test public void parsesArrayOfLocationsWithNulls() throws IOException {
        TemperatureGrid g = TemperatureGrid.parse(
                "[" + location("10,12,null") + "," + location("8,9,10") + "]", 2);
        assertEquals(3, g.times.length);
        assertEquals(Instant.parse("2026-09-24T11:00:00Z"), g.times[1]);
        assertEquals(12.0, g.celsius[0][1], 1e-9);
        assertEquals(9.0, g.celsius[1][1], 1e-9);
        assertTrue(Double.isNaN(g.celsius[0][2]));
    }

    @Test public void singleLocationIsAPlainObject() throws IOException {
        TemperatureGrid g = TemperatureGrid.parse(location("1,2,3"), 1);
        assertEquals(3.0, g.celsius[0][2], 1e-9);
    }

    @Test(expected = IOException.class)
    public void wrongLocationCountIsAnError() throws IOException {
        TemperatureGrid.parse(location("1,2,3"), 2);
    }

    @Test(expected = IOException.class)
    public void errorAnswerIsAnError() throws IOException {
        TemperatureGrid.parse("{\"error\":true,\"reason\":\"bad\"}", 1);
    }

    @Test public void interpolatesBetweenHours() throws IOException {
        TemperatureGrid g = TemperatureGrid.parse(location("10,12,null"), 1);
        assertEquals(10.0, g.at(0, Instant.parse("2026-09-24T10:00:00Z")), 1e-9);
        assertEquals(11.0, g.at(0, Instant.parse("2026-09-24T10:30:00Z")), 1e-9);
        assertEquals(12.0, g.at(0, Instant.parse("2026-09-24T11:00:00Z")), 1e-9);
    }

    @Test public void unknownNeighbourFallsBackToKnownHour() throws IOException {
        TemperatureGrid g = TemperatureGrid.parse(location("10,12,null"), 1);
        // 11:30 lies between 12 °C and an unknown value: use the known one, not NaN.
        assertEquals(12.0, g.at(0, Instant.parse("2026-09-24T11:30:00Z")), 1e-9);
    }

    @Test public void outsideForecastIsNaN() throws IOException {
        TemperatureGrid g = TemperatureGrid.parse(location("10,12,14"), 1);
        assertTrue(Double.isNaN(g.at(0, Instant.parse("2026-09-24T09:00:00Z"))));
        assertTrue(Double.isNaN(g.at(0, Instant.parse("2026-09-24T12:30:00Z"))));
        assertTrue(Double.isNaN(g.at(5, Instant.parse("2026-09-24T10:00:00Z"))));
    }
}
