package nl.paree.climbpro.domain.weather;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Instant;

public class ClothingAdvisorTest {

    private static final Instant TEN = Instant.parse("2026-04-01T10:00:00Z");

    /** Three hours from 10:00 UTC with the given temperature, wind and rain per hour. */
    private static HourlyForecast forecast(double[] temp, double[] wind, int[] rain)
            throws Exception {
        StringBuilder t = new StringBuilder(), w = new StringBuilder(), r = new StringBuilder();
        for (int i = 0; i < temp.length; i++) {
            if (i > 0) { t.append(','); w.append(','); r.append(','); }
            t.append(temp[i]);
            w.append(wind[i]);
            r.append(rain[i]);
        }
        return HourlyForecast.parse("{\"hourly\":{"
                + "\"time\":[\"2026-04-01T10:00\",\"2026-04-01T11:00\",\"2026-04-01T12:00\"],"
                + "\"temperature_2m\":[" + t + "],\"apparent_temperature\":[" + t + "],"
                + "\"wind_speed_10m\":[" + w + "],\"precipitation_probability\":[" + r + "],"
                + "\"uv_index\":[1,1,1]}}");
    }

    @Test
    public void warmCalmDay_summerKitNoNotes() throws Exception {
        ClothingAdvisor.Advice a = ClothingAdvisor.advise(
                forecast(new double[]{24, 25, 26}, new double[]{5, 5, 5}, new int[]{0, 0, 0}),
                TEN, 3 * 3600);
        assertTrue(a.covered);
        assertEquals(24, a.coldestFeelC, 0.01);
        assertTrue(a.kit.get(0).contains("korte broek"));
        assertTrue(a.notes.isEmpty());
    }

    @Test
    public void windChillWithRidingSpeed_makesColdMorningWinterKit() {
        // 4 °C with 20 km/h wind: riding at 25 km/h feels well below freezing-ish.
        double feel = ClothingAdvisor.feelsOnBike(4, 20);
        assertTrue("feel " + feel, feel < 0);
        assertEquals(15, ClothingAdvisor.feelsOnBike(15, 40), 0.001);
        assertTrue(ClothingAdvisor.kitFor(feel).contains("Winterjack"));
    }

    @Test
    public void rainAndBigSwing_addNotes() throws Exception {
        ClothingAdvisor.Advice a = ClothingAdvisor.advise(
                forecast(new double[]{12, 16, 20}, new double[]{5, 5, 5}, new int[]{10, 60, 20}),
                TEN, 3 * 3600);
        assertEquals(12, a.coldestFeelC, 0.01);
        assertEquals(20, a.warmestFeelC, 0.01);
        assertEquals(60, a.maxRainPct);
        assertEquals(2, a.notes.size());
        assertTrue(a.notes.get(0).contains("uit kunt doen"));
        assertTrue(a.notes.get(1).contains("regenjack aan"));
        assertEquals("Op de fiets voelt het als 12 tot 20 °C", ClothingAdvisor.headline(a));
    }

    @Test
    public void onlyTheRideWindowCounts() throws Exception {
        ClothingAdvisor.Advice a = ClothingAdvisor.advise(
                forecast(new double[]{18, 18, 30}, new double[]{5, 5, 5}, new int[]{0, 0, 90}),
                TEN, 2 * 3600);
        assertEquals(18, a.warmestFeelC, 0.01);
        assertEquals(0, a.maxRainPct);
        assertEquals("Op de fiets voelt het als 18 °C", ClothingAdvisor.headline(a));
    }

    @Test
    public void windowOutsideForecast_isNotCovered() throws Exception {
        ClothingAdvisor.Advice a = ClothingAdvisor.advise(
                forecast(new double[]{18, 18, 18}, new double[]{5, 5, 5}, new int[]{0, 0, 0}),
                Instant.parse("2026-04-05T10:00:00Z"), 3600);
        assertFalse(a.covered);
        assertEquals("", ClothingAdvisor.detail(a));
    }
}
