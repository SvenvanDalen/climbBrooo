package nl.paree.climbpro.domain.weather;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Instant;
import java.util.List;

/** Kit bands at their boundaries, every note, and the text rendering. */
public class ClothingAdvisorBandsTest {

    private static final Instant TEN = Instant.parse("2026-04-01T10:00:00Z");

    private static HourlyForecast forecast(String temps, String winds, String rain) throws Exception {
        return HourlyForecast.parse("{\"hourly\":{"
                + "\"time\":[\"2026-04-01T10:00\",\"2026-04-01T11:00\",\"2026-04-01T12:00\"],"
                + "\"temperature_2m\":[" + temps + "],\"apparent_temperature\":[" + temps + "],"
                + "\"wind_speed_10m\":[" + winds + "],\"precipitation_probability\":[" + rain + "],"
                + "\"uv_index\":[1,1,1]}}");
    }

    private static ClothingAdvisor.Advice advise(String temps, String winds, String rain)
            throws Exception {
        return ClothingAdvisor.advise(forecast(temps, winds, rain), TEN, 3 * 3600);
    }

    private static boolean anyNoteContains(ClothingAdvisor.Advice a, String text) {
        for (String n : a.notes) if (n.contains(text)) return true;
        return false;
    }

    @Test
    public void kitBands_switchAtTheirLowerBound() {
        assertTrue(ClothingAdvisor.kitFor(22).get(0).startsWith("Zomershirt"));
        assertEquals("Dun onderhemd", ClothingAdvisor.kitFor(21.9).get(0));
        assertEquals("Dun onderhemd", ClothingAdvisor.kitFor(17).get(0));
        assertEquals("Onderhemd", ClothingAdvisor.kitFor(16.9).get(0));
        assertEquals("Onderhemd", ClothingAdvisor.kitFor(12).get(0));

        List<String> cool = ClothingAdvisor.kitFor(11.9);
        assertEquals(4, cool.size());
        assertEquals("Onderhemd met lange of korte mouwen", cool.get(0));
        assertEquals("Dunne lange handschoenen", cool.get(3));
        assertEquals(cool, ClothingAdvisor.kitFor(8));

        List<String> cold = ClothingAdvisor.kitFor(7.9);
        assertEquals(5, cold.size());
        assertEquals("Thermisch onderhemd met lange mouwen", cold.get(0));
        assertEquals("Dunne muts of buff onder de helm", cold.get(4));
        assertEquals(cold, ClothingAdvisor.kitFor(3));

        assertEquals("Winterbroek met fleece", ClothingAdvisor.kitFor(2.9).get(1));
    }

    @Test
    public void feelsOnBike_onlyAppliesWindChillAtOrBelowTenDegrees() {
        assertEquals(10.5, ClothingAdvisor.feelsOnBike(10.5, 40), 0);
        assertTrue(ClothingAdvisor.feelsOnBike(10, 0) < 10);
        assertTrue(ClothingAdvisor.feelsOnBike(5, 30) < ClothingAdvisor.feelsOnBike(5, 0));
    }

    @Test
    public void rainChance_betweenPocketAndWearThreshold_isAPocketJacket() throws Exception {
        ClothingAdvisor.Advice a = advise("18,18,18", "5,5,5", "0,30,10");
        assertEquals(30, a.maxRainPct);
        assertTrue(anyNoteContains(a, "stop een regenjack in je achterzak"));
        assertFalse(anyNoteContains(a, "trek een regenjack aan"));
    }

    @Test
    public void rainChanceBelowPocketThreshold_noRainNote() throws Exception {
        assertTrue(advise("18,18,18", "5,5,5", "0,29,10").notes.isEmpty());
    }

    @Test
    public void heavyRainOnAWarmDay_needsNoOvershoes() throws Exception {
        ClothingAdvisor.Advice a = advise("20,20,20", "5,5,5", "0,50,10");
        assertTrue(anyNoteContains(a, "trek een regenjack aan."));
        assertFalse(anyNoteContains(a, "overschoenen"));
    }

    @Test
    public void strongWindOnAMildDay_suggestsAWindVest() throws Exception {
        ClothingAdvisor.Advice a = advise("15,15,15", "10,30,10", "0,0,0");
        assertEquals(30, a.maxWindKmh, 0);
        assertTrue(anyNoteContains(a, "Stevige wind (tot 30 km/u)"));
    }

    @Test
    public void strongWindWhenCold_isAlreadyCoveredByTheKit() throws Exception {
        assertFalse(anyNoteContains(advise("5,5,5", "35,35,35", "0,0,0"), "Stevige wind"));
    }

    @Test
    public void nearFreezing_warnsForIce() throws Exception {
        ClothingAdvisor.Advice a = advise("2,2,2", "0,0,0", "0,0,0");
        assertTrue(a.coldestFeelC < 1);
        assertTrue(anyNoteContains(a, "gladheid"));
    }

    @Test
    public void missingWindAndTemperatureHours_areHandled() throws Exception {
        ClothingAdvisor.Advice a = advise("null,18,null", "null,null,null", "null,null,null");
        assertTrue(a.covered);
        assertEquals(18, a.coldestFeelC, 0);
        assertEquals(0, a.maxWindKmh, 0);
        assertEquals(0, a.maxRainPct);
    }

    @Test
    public void headlineAndDetail_renderRangeKitAndNotes() throws Exception {
        ClothingAdvisor.Advice range = advise("12,16,20", "5,5,5", "0,60,0");
        assertEquals("Op de fiets voelt het als 12 tot 20 °C", ClothingAdvisor.headline(range));
        String detail = ClothingAdvisor.detail(range);
        assertTrue(detail.startsWith("• Onderhemd\n• "));
        assertTrue(detail.contains("\n\nHet verschil is groot"));

        ClothingAdvisor.Advice flat = advise("18,18,18", "5,5,5", "0,0,0");
        assertEquals("Op de fiets voelt het als 18 °C", ClothingAdvisor.headline(flat));
        assertFalse(ClothingAdvisor.detail(flat).contains("\n\n"));

        ClothingAdvisor.Advice none = ClothingAdvisor.advise(
                forecast("18,18,18", "5,5,5", "0,0,0"), TEN.plusSeconds(86_400), 3600);
        assertEquals("Geen weersverwachting voor dit tijdstip", ClothingAdvisor.headline(none));
        assertEquals("", ClothingAdvisor.detail(none));
    }
}
