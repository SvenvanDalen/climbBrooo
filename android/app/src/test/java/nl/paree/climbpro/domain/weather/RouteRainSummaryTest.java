package nl.paree.climbpro.domain.weather;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

public class RouteRainSummaryTest {

    private static List<RouteSampler.Sample> samples() {
        List<RouteSampler.Sample> s = new ArrayList<>();
        s.add(new RouteSampler.Sample(0, 50.00, 5.0));
        s.add(new RouteSampler.Sample(4_000, 50.04, 5.0));
        s.add(new RouteSampler.Sample(8_000, 50.08, 5.0));
        return s;
    }

    private static PrecipitationGrid grid(String a, String b, String c) throws IOException {
        return PrecipitationGrid.parse(PrecipitationGridTest.json3(a, b, c), 3);
    }

    private static Instant at(String hhmm) { return Instant.parse("2026-09-24T" + hhmm + ":00Z"); }

    @Test public void listsDryWetAndUnknownHoursFromTheCurrentHour() throws IOException {
        PrecipitationGrid g = grid("0.0,0.5,null", "0.0,1.2,null", "0.0,0.0,null");
        assertEquals("Regen langs de route (per uur):\n"
                        + "10:00  droog\n"
                        + "11:00  regen bij km 0–4 (1,2 mm)\n"
                        + "12:00  geen gegevens",
                RouteRainSummary.describe(samples(), g, at("10:20"), 6, ZoneOffset.UTC));
    }

    @Test public void separateWetStretchesEachGetTheirOwnMaximum() throws IOException {
        PrecipitationGrid g = grid("0.3,0,0", "0.0,0,0", "2.0,0,0");
        assertEquals("Regen langs de route (per uur):\n10:00  regen bij km 0 (0,3 mm), km 8 (2,0 mm)",
                RouteRainSummary.describe(samples(), g, at("10:00"), 1, ZoneOffset.UTC));
    }

    @Test public void allDryIsOneSentence() throws IOException {
        PrecipitationGrid g = grid("0,0,0", "0,0,0", "0,0,0");
        assertEquals("Geen regen verwacht langs de route in de komende 2 uur.",
                RouteRainSummary.describe(samples(), g, at("10:00"), 2, ZoneOffset.UTC));
    }

    @Test public void drizzleBelowThresholdCountsAsDry() throws IOException {
        PrecipitationGrid g = grid("0.05,0,0", "0,0,0", "0,0,0");
        assertEquals("Geen regen verwacht langs de route in de komende 1 uur.",
                RouteRainSummary.describe(samples(), g, at("10:00"), 1, ZoneOffset.UTC));
    }

    @Test public void nowBeforeTheForecastStartsAtTheFirstHour() throws IOException {
        PrecipitationGrid g = grid("1.0,0,0", "0,0,0", "0,0,0");
        assertEquals("Regen langs de route (per uur):\n10:00  regen bij km 0 (1,0 mm)",
                RouteRainSummary.describe(samples(), g, at("09:30"), 1, ZoneOffset.UTC));
    }

    @Test public void nowAfterTheForecastHasNoForecast() throws IOException {
        PrecipitationGrid g = grid("0,0,0", "0,0,0", "0,0,0");
        assertEquals("Geen neerslagverwachting beschikbaar.",
                RouteRainSummary.describe(samples(), g, at("13:00"), 6, ZoneOffset.UTC));
    }

    @Test public void hoursAreShownInTheGivenZone() throws IOException {
        PrecipitationGrid g = grid("0,1.5,0", "0,0,0", "0,0,0");
        assertEquals("Regen langs de route (per uur):\n12:00  droog\n13:00  regen bij km 0 (1,5 mm)",
                RouteRainSummary.describe(samples(), g, at("10:00"), 2,
                        ZoneId.of("Europe/Amsterdam")));
    }
}
