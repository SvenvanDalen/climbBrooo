package nl.paree.climbpro.domain.weather;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

public class SunscreenAdvisorTest {

    /** 08:00–15:00 UTC with UV 1,2,4,6,7,5,3,1. */
    static HourlyForecast forecast(String uvArray) throws IOException {
        return HourlyForecast.parse("{\"hourly\":{"
                + "\"time\":[\"2026-07-01T08:00\",\"2026-07-01T09:00\",\"2026-07-01T10:00\","
                + "\"2026-07-01T11:00\",\"2026-07-01T12:00\",\"2026-07-01T13:00\","
                + "\"2026-07-01T14:00\",\"2026-07-01T15:00\"],"
                + "\"uv_index\":" + uvArray + "}}");
    }

    private static Instant at(String hhmm) {
        return Instant.parse("2026-07-01T" + hhmm + ":00Z");
    }

    @Test
    public void parsesUvIndex_nullAsNaN() throws IOException {
        HourlyForecast f = forecast("[1.5,null,4,6,7,5,3,1]");
        assertEquals(1.5, f.uvIndex[0], 1e-9);
        assertTrue(Double.isNaN(f.uvIndex[1]));
    }

    @Test
    public void morningRide_peakAndReapplyWhileUvHigh() throws IOException {
        HourlyForecast f = forecast("[1,2,4,6,7,5,3,1]");
        SunscreenAdvisor.Advice a = SunscreenAdvisor.advise(f, at("09:00"), 5 * 3600);
        assertEquals(7.0, a.peakUv, 1e-9);
        assertEquals(SunscreenAdvisor.Level.HIGH, a.level);
        assertEquals(4, a.protectHours); // 10,11,12,13
        // Every 2 h from the 09:00 start, before the 14:00 end: 11:00 (UV 6) and 13:00 (UV 5).
        assertEquals(Arrays.asList(at("11:00"), at("13:00")), a.reapplyAt);
        assertEquals("UV-index tot 7 (hoog) — smeer factor 50 vóór vertrek.",
                SunscreenAdvisor.headline(a));
        assertEquals("4 uur van je rit is de UV-index 3 of hoger. Opnieuw smeren om 11:00 en "
                + "13:00. Vergeet nek, oren en onderarmen niet, en neem lippenbalsem met SPF mee.",
                SunscreenAdvisor.detail(a, ZoneOffset.UTC));
    }

    @Test
    public void reapplySkippedWhenUvHasDropped() throws IOException {
        HourlyForecast f = forecast("[1,2,4,6,7,5,3,1]");
        SunscreenAdvisor.Advice a = SunscreenAdvisor.advise(f, at("13:30"), 3 * 3600);
        // 15:30 falls in the 15:00 hour with UV 1: no re-apply needed.
        assertTrue(a.reapplyAt.isEmpty());
        assertEquals(SunscreenAdvisor.Level.MODERATE, a.level);
        assertTrue(SunscreenAdvisor.detail(a, ZoneOffset.UTC).contains("Eén keer smeren is genoeg."));
    }

    @Test
    public void lowUv_noAdviceNoReminders() throws IOException {
        HourlyForecast f = forecast("[1,2,2,2,2,2,2,1]");
        SunscreenAdvisor.Advice a = SunscreenAdvisor.advise(f, at("08:00"), 8 * 3600);
        assertFalse(a.needed());
        assertEquals("UV-index tot 2 (laag) — geen zonnebrand nodig.", SunscreenAdvisor.headline(a));
        assertEquals("", SunscreenAdvisor.detail(a, ZoneOffset.UTC));
        assertTrue(SunscreenAdvisor.reminders(a, at("08:00"), at("07:00")).isEmpty());
    }

    @Test
    public void outsideForecast_unknown() throws IOException {
        SunscreenAdvisor.Advice a = SunscreenAdvisor.advise(
                forecast("[1,2,4,6,7,5,3,1]"), at("20:00"), 3600);
        assertFalse(a.known());
        assertFalse(a.needed());
        assertEquals("Geen UV-verwachting beschikbaar voor dit tijdstip.",
                SunscreenAdvisor.headline(a));
    }

    @Test
    public void reminders_beforeStartOnlyWhenAheadAndPastOnesDropped() throws IOException {
        HourlyForecast f = forecast("[1,2,4,6,7,5,3,1]");
        SunscreenAdvisor.Advice a = SunscreenAdvisor.advise(f, at("09:00"), 5 * 3600);

        List<SunscreenAdvisor.Reminder> planned = SunscreenAdvisor.reminders(a, at("09:00"), at("07:00"));
        assertEquals(3, planned.size());
        assertEquals(at("08:45"), planned.get(0).at);
        assertEquals("Smeer factor 50 in voor je rit (UV-index tot 7).", planned.get(0).text);
        assertEquals(at("11:00"), planned.get(1).at);

        // Checked right at the start: no "before" reminder, only future re-applies.
        List<SunscreenAdvisor.Reminder> now = SunscreenAdvisor.reminders(a, at("09:00"), at("11:30"));
        assertEquals(1, now.size());
        assertEquals(at("13:00"), now.get(0).at);
    }

    @Test
    public void levels_followWhoBands() {
        assertEquals(SunscreenAdvisor.Level.LOW, SunscreenAdvisor.Level.of(2.9));
        assertEquals(SunscreenAdvisor.Level.MODERATE, SunscreenAdvisor.Level.of(3));
        assertEquals(SunscreenAdvisor.Level.HIGH, SunscreenAdvisor.Level.of(6));
        assertEquals(SunscreenAdvisor.Level.VERY_HIGH, SunscreenAdvisor.Level.of(8));
        assertEquals(SunscreenAdvisor.Level.EXTREME, SunscreenAdvisor.Level.of(11));
    }
}
