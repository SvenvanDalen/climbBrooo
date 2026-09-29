package nl.paree.climbpro.domain.weather;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Instant;
import java.time.ZoneOffset;

public class AirQualityAdvisorTest {

    /** Three hours from 10:00 UTC; the ride covers 10:00-12:00. */
    private static final String JSON = "{\"hourly\":{"
            + "\"time\":[\"2026-05-01T10:00\",\"2026-05-01T11:00\",\"2026-05-01T12:00\"],"
            + "\"pm2_5\":[8,12,40],"
            + "\"pm10\":[15,20,70],"
            + "\"european_aqi\":[25,45,90],"
            + "\"grass_pollen\":[20,60,300],"
            + "\"birch_pollen\":[1,2,null],"
            + "\"alder_pollen\":[null,null,null],"
            + "\"mugwort_pollen\":[null,null,null],"
            + "\"olive_pollen\":[null,null,null],"
            + "\"ragweed_pollen\":[null,null,null]}}";

    private static final Instant TEN = Instant.parse("2026-05-01T10:00:00Z");

    @Test
    public void worstHourInsideTheRideWindow() throws Exception {
        AirQualityForecast f = AirQualityForecast.parse(JSON);
        AirQualityAdvisor.Advice a = AirQualityAdvisor.advise(f, TEN, 2 * 3600);

        assertTrue(a.covered);
        assertEquals(45, a.maxAqi, 0.01); // 12:00 (AQI 90) is after the ride
        assertEquals(Instant.parse("2026-05-01T11:00:00Z"), a.maxAqiAt);
        assertEquals(12, a.maxPm25, 0.01);
        assertEquals(AirQualityAdvisor.Level.MODERATE, a.airLevel);
        assertEquals(1, a.pollen.size());
        assertEquals(AirQualityForecast.Pollen.GRASS, a.pollen.get(0).type);
        assertEquals(AirQualityAdvisor.Level.HIGH, a.pollen.get(0).level);
        assertEquals(AirQualityAdvisor.Level.HIGH, a.overall());
        assertEquals("Let op: grassenpollen tijdens je rit", AirQualityAdvisor.headline(a));
        assertTrue(AirQualityAdvisor.detail(a, ZoneOffset.UTC).contains("om 11:00"));
    }

    @Test
    public void poorAirAlone_warnsWithAsthmaTip() throws Exception {
        AirQualityForecast f = AirQualityForecast.parse(JSON.replace("[20,60,300]", "[0,0,0]"));
        AirQualityAdvisor.Advice a = AirQualityAdvisor.advise(
                f, Instant.parse("2026-05-01T12:00:00Z"), 3600);
        assertEquals(AirQualityAdvisor.Level.HIGH, a.airLevel);
        assertTrue(a.pollen.isEmpty());
        assertEquals("Let op: zeer slechte lucht tijdens je rit", AirQualityAdvisor.headline(a));
        assertTrue(AirQualityAdvisor.tips(a).contains("inhaler"));
    }

    @Test
    public void noPollenData_isSaidExplicitly() throws Exception {
        String json = "{\"hourly\":{\"time\":[\"2026-01-10T10:00\"],\"pm2_5\":[3],"
                + "\"pm10\":[5],\"european_aqi\":[10]}}";
        AirQualityAdvisor.Advice a = AirQualityAdvisor.advise(
                AirQualityForecast.parse(json), Instant.parse("2026-01-10T10:30:00Z"), 3600);
        assertFalse(a.pollenKnown);
        assertEquals(AirQualityAdvisor.Level.GOOD, a.overall());
        assertEquals("Lucht en pollen zijn prima voor je rit", AirQualityAdvisor.headline(a));
        assertTrue(AirQualityAdvisor.detail(a, ZoneOffset.UTC).contains("geen verwachting"));
    }

    @Test
    public void windowOutsideForecast_isNotCovered() throws Exception {
        AirQualityAdvisor.Advice a = AirQualityAdvisor.advise(AirQualityForecast.parse(JSON),
                Instant.parse("2026-05-03T10:00:00Z"), 3600);
        assertFalse(a.covered);
    }

    @Test(expected = java.io.IOException.class)
    public void malformedResponse_throws() throws Exception {
        AirQualityForecast.parse("{\"error\":true}");
    }
}
