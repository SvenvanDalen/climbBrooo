package nl.paree.climbpro.domain.weather;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.IOException;
import java.time.Instant;

public class HourlyForecastTest {

    static final String JSON = "{\"latitude\":50.0,\"elevation\":380.0,\"hourly\":{"
            + "\"time\":[\"2026-09-24T10:00\",\"2026-09-24T11:00\",\"2026-09-24T12:00\"],"
            + "\"temperature_2m\":[12.4,13.0,13.9],"
            + "\"apparent_temperature\":[9.8,10.5,11.6],"
            + "\"wind_speed_10m\":[22.0,31.5,18.0],"
            + "\"precipitation_probability\":[10,null,40]}}";

    @Test public void parsesArraysIncludingNullRainChance() throws IOException {
        HourlyForecast f = HourlyForecast.parse(JSON);
        assertEquals(3, f.times.length);
        assertEquals(Instant.parse("2026-09-24T11:00:00Z"), f.times[1]);
        assertEquals(13.0, f.temperature[1], 1e-9);
        assertEquals(9.8, f.apparent[0], 1e-9);
        assertEquals(31.5, f.windKmh[1], 1e-9);
        assertEquals(Integer.valueOf(10), f.rainPct[0]);
        assertNull(f.rainPct[1]);
    }

    @Test public void indexAtUsesTheHourContainingTheInstant() throws IOException {
        HourlyForecast f = HourlyForecast.parse(JSON);
        assertEquals(0, f.indexAt(Instant.parse("2026-09-24T10:59:59Z")));
        assertEquals(2, f.indexAt(Instant.parse("2026-09-24T12:30:00Z")));
        assertEquals(-1, f.indexAt(Instant.parse("2026-09-24T09:59:00Z")));
        assertEquals(-1, f.indexAt(Instant.parse("2026-09-24T13:00:00Z")));
    }

    @Test(expected = IOException.class)
    public void missingHourlyBlockIsAnError() throws IOException {
        HourlyForecast.parse("{\"error\":true,\"reason\":\"bad\"}");
    }
}
