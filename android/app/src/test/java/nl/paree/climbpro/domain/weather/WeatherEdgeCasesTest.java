package nl.paree.climbpro.domain.weather;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import nl.paree.climbpro.domain.weather.AirQualityAdvisor.Level;
import nl.paree.climbpro.domain.weather.AirQualityForecast.Pollen;
import nl.paree.climbpro.domain.weather.ClimateNormals.DayPart;

import org.junit.Test;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Malformed weather answers and the less common advice branches. */
public class WeatherEdgeCasesTest {

    // ---- malformed service answers ----

    @Test
    public void grids_emptyAnswer_isRejected() {
        try {
            TemperatureGrid.parse("", 1);
            fail("expected IOException");
        } catch (IOException e) {
            assertEquals("Onverwacht antwoord van de weerdienst", e.getMessage());
        }
        try {
            PrecipitationGrid.parse("", 1);
            fail("expected IOException");
        } catch (IOException e) {
            assertEquals("Onverwacht antwoord van de weerdienst", e.getMessage());
        }
    }

    @Test
    public void radar_frameWithoutTime_isRejected_andNotJsonIsWrapped() {
        try {
            RainRadarFrame.parseLatest("{\"host\":\"h\",\"radar\":{\"past\":[{\"path\":\"/x\"}]}}");
            fail("expected IOException");
        } catch (IOException e) {
            assertEquals("Onverwacht antwoord van RainViewer", e.getMessage());
        }
        try {
            RainRadarFrame.parseLatest("<html>");
            fail("expected IOException");
        } catch (IOException e) {
            assertNotNull(e.getCause());
        }
    }

    @Test
    public void daily_badDate_isRejectedWithCause_andGapsBecomeNullOrNaN() throws Exception {
        try {
            DailyForecast.parse("{\"daily\":{\"time\":[\"morgen\"]}}");
            fail("expected IOException");
        } catch (IOException e) {
            assertEquals("Onverwachte datum van de weerdienst", e.getMessage());
            assertNotNull(e.getCause());
        }
        List<DailyForecast.Day> days = DailyForecast.parse("{\"daily\":{\"time\":[\"2026-10-06\"],"
                + "\"precipitation_probability_max\":[null],\"wind_speed_10m_max\":[\"x\"]}}");
        assertNull(days.get(0).rainPct);
        assertTrue(Double.isNaN(days.get(0).windMaxKmh));
        assertTrue(Double.isNaN(days.get(0).tempMaxC));
    }

    @Test(expected = IOException.class)
    public void daily_withoutDailyBlock_isRejected() throws Exception {
        DailyForecast.parse("{\"hourly\":{}}");
    }

    @Test
    public void climateArchive_skipsUnparseableTimestamps() throws Exception {
        ClimateNormals n = ClimateNormals.fromArchive("{\"hourly\":{"
                + "\"time\":[\"2025-xx-01T08:00\",\"kort\",\"2025-07-01T08:00\"],"
                + "\"temperature_2m\":[99,99,18],\"wind_speed_10m\":[5,5,5],"
                + "\"wind_direction_10m\":[0,0,0],\"precipitation\":[0,0,0]}}");
        assertEquals(18, n.cell(7, DayPart.OCHTEND).meanTempC, 1e-9);
    }

    @Test
    public void temperatureTrend_missingInputs_isEmpty() {
        assertTrue(TemperatureTrend.compute(null, null, Instant.EPOCH, 3600).isEmpty());
        assertTrue(TemperatureTrend.compute(Collections.<RouteSampler.Sample>emptyList(),
                null, Instant.EPOCH, 3600).isEmpty());
    }

    // ---- loop wind ----

    @Test
    public void loopWindAdvice_bestAndOtherFollowTheVerdict() {
        LoopWindAdvice reverse = new LoopWindAdvice(LoopWindAdvice.Verdict.REVERSE, 12, -4, 270, 20, 100);
        assertEquals(-4, reverse.bestHomeHeadwindKmh(), 0);
        assertEquals(12, reverse.otherHomeHeadwindKmh(), 0);
        LoopWindAdvice forward = new LoopWindAdvice(LoopWindAdvice.Verdict.FORWARD, -3, 9, 90, 20, 100);
        assertEquals(-3, forward.bestHomeHeadwindKmh(), 0);
        assertEquals(9, forward.otherHomeHeadwindKmh(), 0);
    }

    // ---- sunscreen ----

    @Test
    public void spf_perUvLevel() {
        assertNull(SunscreenAdvisor.spf(SunscreenAdvisor.Level.LOW));
        assertEquals("factor 30", SunscreenAdvisor.spf(SunscreenAdvisor.Level.MODERATE));
        assertEquals("factor 50", SunscreenAdvisor.spf(SunscreenAdvisor.Level.HIGH));
        assertEquals("factor 50+", SunscreenAdvisor.spf(SunscreenAdvisor.Level.VERY_HIGH));
        assertEquals("factor 50+", SunscreenAdvisor.spf(SunscreenAdvisor.Level.EXTREME));
    }

    // ---- air quality ----

    @Test
    public void aqiLabelsAndLevels_atTheirBoundaries() {
        assertEquals("goede", AirQualityAdvisor.aqiLabel(19.9));
        assertEquals("redelijke", AirQualityAdvisor.aqiLabel(20));
        assertEquals("matige", AirQualityAdvisor.aqiLabel(40));
        assertEquals("slechte", AirQualityAdvisor.aqiLabel(60));
        assertEquals("zeer slechte", AirQualityAdvisor.aqiLabel(80));
        assertEquals("extreem slechte", AirQualityAdvisor.aqiLabel(100));
        assertEquals(Level.GOOD, AirQualityAdvisor.aqiLevel(Double.NaN));
        assertEquals(Level.GOOD, AirQualityAdvisor.aqiLevel(39.9));
        assertEquals(Level.MODERATE, AirQualityAdvisor.aqiLevel(40));
        assertEquals(Level.HIGH, AirQualityAdvisor.aqiLevel(60));
    }

    @Test
    public void moderateAir_lowPollen() {
        AirQualityAdvisor.Advice a = new AirQualityAdvisor.Advice(true, 45, null, Double.NaN, 12,
                Collections.<AirQualityAdvisor.PollenPeak>emptyList(), true);
        assertEquals("Matig: matige lucht tijdens je rit", AirQualityAdvisor.headline(a));
        String detail = AirQualityAdvisor.detail(a, ZoneOffset.UTC);
        assertTrue(detail, detail.startsWith("Luchtkwaliteit: matige (Europese AQI tot 45)"));
        assertTrue(detail, detail.contains("PM2,5 tot ?, PM10 tot 12"));
        assertTrue(detail, detail.contains("\nPollen: weinig"));
        assertTrue(detail, detail.endsWith("Houd de intensiteit wat lager."));
    }

    @Test
    public void moderatePollen_onlyAddsTheGlassesTip_andPeaksSortWorstFirst() {
        List<AirQualityAdvisor.PollenPeak> peaks = Arrays.asList(
                new AirQualityAdvisor.PollenPeak(Pollen.GRASS, 20, Level.MODERATE));
        AirQualityAdvisor.Advice a = new AirQualityAdvisor.Advice(true, 10, null, 3, 4, peaks, true);
        assertEquals("Matig: grassenpollen tijdens je rit", AirQualityAdvisor.headline(a));
        assertEquals("Hooikoorts? Een bril helpt tegen pollen in je ogen.", AirQualityAdvisor.tips(a));
    }

    @Test
    public void advise_sortsPollenPeaksByLevelThenRelativeLoad() throws Exception {
        StringBuilder json = new StringBuilder("{\"hourly\":{\"time\":[\"2026-04-01T10:00\"],"
                + "\"pm10\":[5],\"pm2_5\":[3],\"european_aqi\":[10]");
        double[] grains = {20, 60, 30, 0, 0, 0}; // all moderate: 0.4, 0.6 and 0.3 of "high"
        Pollen[] all = Pollen.values();
        for (int i = 0; i < all.length; i++) {
            json.append(",\"").append(all[i].field).append("\":[").append(grains[i]).append(']');
        }
        json.append("}}");

        AirQualityAdvisor.Advice a = AirQualityAdvisor.advise(AirQualityForecast.parse(json.toString()),
                Instant.parse("2026-04-01T10:00:00Z"), 3600);

        assertEquals(3, a.pollen.size());
        // All moderate: highest share of its own "high" threshold first (birch 60/100).
        assertEquals(Pollen.BIRCH, a.pollen.get(0).type);
        assertEquals(Pollen.GRASS, a.pollen.get(1).type);
        assertEquals(Pollen.ALDER, a.pollen.get(2).type);
    }

    // ---- best time to ride ----

    @Test
    public void monthTable_marksMonthsWithoutData() {
        ClimateNormals.Cell[][] g = new ClimateNormals.Cell[12][DayPart.values().length];
        for (ClimateNormals.Cell[] row : g) {
            Arrays.fill(row, ClimateNormals.Cell.of(17, Double.NaN, Double.NaN, 0, 0, 100));
        }
        Arrays.fill(g[1], ClimateNormals.Cell.EMPTY);

        String[] lines = BestTimeScorer.evaluate(new ClimateNormals(g), Double.NaN)
                .monthTable().split("\n");

        assertEquals("  feb  –", lines[1]);
        assertTrue(lines[0], lines[0].contains(" 0 km/u  regen  0%"));
    }
}
