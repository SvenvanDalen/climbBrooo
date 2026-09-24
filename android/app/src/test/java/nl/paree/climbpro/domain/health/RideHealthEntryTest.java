package nl.paree.climbpro.domain.health;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.strava.StravaActivityDto;

import org.junit.Test;

import java.time.Instant;
import java.time.ZoneOffset;

public class RideHealthEntryTest {

    private static StravaActivityDto ride(String type) {
        StravaActivityDto a = new StravaActivityDto();
        a.id = 123;
        a.name = "Rondje Limburg";
        a.type = type;
        a.startDate = "2026-07-04T06:30:00Z";
        a.startDateLocal = "2026-07-04T08:30:00Z";
        a.elapsedTime = 3 * 3600 + 600;
        a.movingTime = 3 * 3600;
        a.distance = 92_500f;
        a.totalElevationGain = 1_240f;
        a.kilojoules = 2_150.0;
        return a;
    }

    @Test
    public void mapsRideWithLocalOffsetElapsedTimeAndCalories() {
        RideHealthEntry e = RideHealthEntry.from(ride("Ride"));

        assertEquals("climbpro-strava-123", e.clientRecordId);
        assertEquals("Rondje Limburg", e.title);
        assertEquals(Instant.parse("2026-07-04T06:30:00Z"), e.start);
        assertEquals(Instant.parse("2026-07-04T09:40:00Z"), e.end);
        assertEquals(ZoneOffset.ofHours(2), e.offset);
        assertFalse(e.stationary);
        assertEquals(92_500, e.distanceM, 0.01);
        assertEquals(1_240, e.elevationM, 0.01);
        assertEquals(2_150.0, e.kcal, 0.01);
    }

    @Test
    public void virtualRideIsStationaryAndGravelCounts() {
        assertTrue(RideHealthEntry.from(ride("VirtualRide")).stationary);
        assertFalse(RideHealthEntry.from(ride("GravelRide")).stationary);
    }

    @Test
    public void nonCyclingAndBrokenActivitiesAreSkipped() {
        assertNull(RideHealthEntry.from(ride("Run")));
        assertNull(RideHealthEntry.from(ride(null)));
        StravaActivityDto noDate = ride("Ride");
        noDate.startDate = "gisteren";
        assertNull(RideHealthEntry.from(noDate));
        StravaActivityDto noTime = ride("Ride");
        noTime.elapsedTime = 0;
        noTime.movingTime = 0;
        assertNull(RideHealthEntry.from(noTime));
        assertNull(RideHealthEntry.from(null));
    }

    @Test
    public void missingOptionalsDegradeGracefully() {
        StravaActivityDto a = ride("Ride");
        a.kilojoules = null;
        a.startDateLocal = null;
        a.elapsedTime = 0;           // falls back to moving time
        a.name = "";

        RideHealthEntry e = RideHealthEntry.from(a);

        assertNull(e.kcal);
        assertNull(e.offset);
        assertEquals(Instant.parse("2026-07-04T09:30:00Z"), e.end);
        assertEquals("Fietsrit", e.title);
    }
}
