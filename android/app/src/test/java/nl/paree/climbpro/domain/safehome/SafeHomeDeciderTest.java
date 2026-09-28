package nl.paree.climbpro.domain.safehome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.safehome.SafeHomeSettings;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class SafeHomeDeciderTest {

    private static final long NOW = 1_700_000_000L;
    private static final long HOUR = 3600L;

    private static SafeHomeSettings on() {
        SafeHomeSettings s = new SafeHomeSettings();
        s.enabled = true;
        s.phoneNumber = "+31612345678";
        s.armedSinceEpochSec = NOW - 10 * 24 * HOUR;
        return s;
    }

    private static StoredRide ride(long id, long endAgoSec, int elapsedSec, String type) {
        StoredRide r = new StoredRide();
        r.activityId = id;
        r.type = type;
        r.elapsedTimeSec = elapsedSec;
        r.startEpochSec = NOW - endAgoSec - elapsedSec;
        r.distanceM = 84_400f;
        r.name = "Rondje Veluwe";
        return r;
    }

    private static List<Long> ids(List<StoredRide> rides) {
        Long[] out = new Long[rides.size()];
        for (int i = 0; i < out.length; i++) out[i] = rides.get(i).activityId;
        return Arrays.asList(out);
    }

    @Test
    public void recentOutdoorRide_qualifies_sortedByEnd() {
        List<StoredRide> out = SafeHomeDecider.qualifying(Arrays.asList(
                ride(1, 10 * 60, 3 * (int) HOUR, "Ride"),
                ride(2, 2 * HOUR, (int) HOUR, "GravelRide")), on(), NOW);
        assertEquals(Arrays.asList(2L, 1L), ids(out));
    }

    @Test
    public void oldVirtualReportedOrPreArmRides_areSkipped() {
        SafeHomeSettings s = on();
        s.reportedActivityIds.add(3L);
        s.armedSinceEpochSec = NOW - HOUR;
        List<StoredRide> out = SafeHomeDecider.qualifying(Arrays.asList(
                ride(1, 4 * HOUR, (int) HOUR, "Ride"),         // too old
                ride(2, 5 * 60, (int) HOUR, "VirtualRide"),    // indoor
                ride(3, 5 * 60, (int) HOUR, "Ride"),           // already reported
                ride(4, 2 * HOUR, (int) HOUR, "Ride"),         // ended before arming
                ride(5, 30 * 60, (int) HOUR, "Ride")), s, NOW);
        assertEquals(Arrays.asList(5L), ids(out));
    }

    @Test
    public void disabledOrWithoutNumber_nothingQualifies() {
        List<StoredRide> rides = Arrays.asList(ride(1, 60, 3600, "Ride"));
        SafeHomeSettings off = on();
        off.enabled = false;
        assertTrue(SafeHomeDecider.qualifying(rides, off, NOW).isEmpty());
        SafeHomeSettings noNumber = on();
        noNumber.phoneNumber = null;
        assertTrue(SafeHomeDecider.qualifying(rides, noNumber, NOW).isEmpty());
        assertTrue(SafeHomeDecider.qualifying(null, on(), NOW).isEmpty());
    }

    @Test
    public void endFallsBackToMovingTime() {
        StoredRide r = ride(1, 0, 0, "Ride");
        r.movingTimeSec = 1200;
        assertEquals(r.startEpochSec + 1200, SafeHomeDecider.endEpochSec(r));
    }

    @Test
    public void formatMessage_fillsPlaceholders() {
        StoredRide r = ride(1, 0, 3600, "Ride");
        assertEquals("Ik ben veilig thuis na mijn fietsrit van 84 km.",
                SafeHomeDecider.formatMessage(null, r));
        assertEquals("Thuis na Rondje Veluwe (84 km)",
                SafeHomeDecider.formatMessage("Thuis na {naam} ({km} km)", r));
    }
}
