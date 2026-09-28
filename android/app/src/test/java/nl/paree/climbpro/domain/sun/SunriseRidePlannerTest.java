package nl.paree.climbpro.domain.sun;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Instant;

public class SunriseRidePlannerTest {

    private static final Instant SUNRISE = Instant.parse("2026-06-21T03:18:00Z");

    @Test public void worksBackFromSunrise() {
        SunriseRidePlanner.Plan p = SunriseRidePlanner.plan(SUNRISE, 25_000, 25.0, 1_800, 5_000, 10);
        assertEquals(Instant.parse("2026-06-21T03:08:00Z"), p.arrivalTop);
        assertEquals(3_600, p.approachSec);
        assertEquals(Instant.parse("2026-06-21T01:38:00Z"), p.departure);
    }

    @Test public void climbAtRouteStartHasNoApproach() {
        SunriseRidePlanner.Plan p = SunriseRidePlanner.plan(SUNRISE, 0, 25.0, 1_200, 4_000, 0);
        assertEquals(0, p.approachSec);
        assertEquals(SUNRISE.minusSeconds(1_200), p.departure);
    }

    @Test public void missingClimbEstimateFallsBackToTenKmh() {
        SunriseRidePlanner.Plan p = SunriseRidePlanner.plan(SUNRISE, 0, 25.0, 0, 5_000, 0);
        assertEquals(1_800, p.climbSec); // 5 km at 10 km/h
    }

    @Test public void invalidSpeedUsesDefaultAndDepartureMayBePreviousDay() {
        SunriseRidePlanner.Plan p = SunriseRidePlanner.plan(SUNRISE, 100_000, 0, 3_600, 10_000, 10);
        assertEquals(14_400, p.approachSec); // 100 km at 25 km/h
        assertEquals(Instant.parse("2026-06-20T22:08:00Z"), p.departure);
    }

    @Test public void pastDepartureIsDetected() {
        SunriseRidePlanner.Plan p = SunriseRidePlanner.plan(SUNRISE, 0, 25.0, 600, 0, 0);
        assertTrue(SunriseRidePlanner.isInPast(p, SUNRISE));
        assertFalse(SunriseRidePlanner.isInPast(p, SUNRISE.minusSeconds(3_600)));
    }
}
