package nl.paree.climbpro.domain.sun;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

public class SunriseCalculatorTest {

    private static void assertNear(String expectedUtc, Instant actual) {
        long diff = Math.abs(Duration.between(Instant.parse(expectedUtc), actual).getSeconds());
        assertTrue("expected ~" + expectedUtc + " but was " + actual, diff <= 180);
    }

    @Test public void amsterdamSummerSolstice() {   // 05:18 CEST
        assertNear("2026-06-21T03:18:00Z", SunriseCalculator.sunrise(LocalDate.of(2026, 6, 21), 52.37, 4.90));
    }

    @Test public void amsterdamWinterSolstice() {   // 08:48 CET
        assertNear("2026-12-21T07:48:00Z", SunriseCalculator.sunrise(LocalDate.of(2026, 12, 21), 52.37, 4.90));
    }

    @Test public void westernLongitudeRisesLater() {
        Instant ams = SunriseCalculator.sunrise(LocalDate.of(2026, 3, 20), 52.37, 4.90);
        Instant dublin = SunriseCalculator.sunrise(LocalDate.of(2026, 3, 20), 53.35, -6.26);
        assertTrue(dublin.isAfter(ams.plusSeconds(40 * 60)));
    }

    @Test public void polarDayAndNightHaveNoSunrise() {
        assertNull(SunriseCalculator.sunrise(LocalDate.of(2026, 6, 21), 69.65, 18.96));
        assertNull(SunriseCalculator.sunrise(LocalDate.of(2026, 12, 21), 69.65, 18.96));
    }
}
