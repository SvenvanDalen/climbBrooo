package nl.paree.climbpro.domain.weather;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

/** Pure JUnit test for the Open-Meteo daily forecast parser (issue #40). */
public class DailyForecastTest {

    @Test
    public void parsesDailyArrays() throws IOException {
        String json = "{\"daily\":{"
                + "\"time\":[\"2026-09-29\",\"2026-09-30\"],"
                + "\"precipitation_probability_max\":[10,80],"
                + "\"wind_speed_10m_max\":[12.5,40.0],"
                + "\"temperature_2m_max\":[18.2,11.0]}}";
        List<DailyForecast.Day> days = DailyForecast.parse(json);
        assertEquals(2, days.size());
        assertEquals(LocalDate.of(2026, 9, 29), days.get(0).date);
        assertEquals(Integer.valueOf(10), days.get(0).rainPct);
        assertEquals(12.5, days.get(0).windMaxKmh, 1e-9);
        assertEquals(18.2, days.get(0).tempMaxC, 1e-9);
        assertEquals(Integer.valueOf(80), days.get(1).rainPct);
    }

    @Test
    public void missingValuesBecomeNullOrNaN() throws IOException {
        String json = "{\"daily\":{\"time\":[\"2026-09-29\"],"
                + "\"precipitation_probability_max\":[null]}}";
        DailyForecast.Day d = DailyForecast.parse(json).get(0);
        assertNull(d.rainPct);
        assertTrue(Double.isNaN(d.windMaxKmh));
        assertTrue(Double.isNaN(d.tempMaxC));
    }

    @Test
    public void rejectsUnexpectedResponse() {
        try {
            DailyForecast.parse("{\"error\":true}");
            fail("expected IOException");
        } catch (IOException expected) {
            // ok
        }
    }

    @Test
    public void urlAsksForAWeekOfDailyValues() {
        String url = DailyForecast.url(52.1, 5.2);
        assertTrue(url.contains("latitude=52.10000"));
        assertTrue(url.contains("longitude=5.20000"));
        assertTrue(url.contains("daily=precipitation_probability_max,wind_speed_10m_max,temperature_2m_max"));
        assertTrue(url.contains("forecast_days=7"));
        assertTrue(url.contains("timezone=auto"));
    }
}
