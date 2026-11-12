package nl.paree.climbpro.domain.mywhoosh;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.ride.StoredRideStreamStats;

import org.junit.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** MyWhoosh dashboard totals and buckets (issue #383). */
public class MyWhooshDashboardTest {

    private static final long NOW =
            LocalDate.of(2026, 3, 4).atTime(12, 0).toEpochSecond(ZoneOffset.UTC);

    private static StoredRide ride(long id, String name, String type, long start, float km) {
        StoredRide r = new StoredRide();
        r.activityId = id;
        r.name = name;
        r.type = type;
        r.startEpochSec = start;
        r.distanceM = km * 1000;
        r.elevationGainM = 300;
        r.movingTimeSec = 3600;
        return r;
    }

    @Test
    public void totalsLatestAndBuckets() {
        StoredRide a = ride(1, "MyWhoosh - A", "VirtualRide", NOW - 3600, 30);
        StoredRide b = ride(2, "MyWhoosh - B", "VirtualRide", NOW - 8 * 86_400L, 20);
        StoredRide old = ride(3, "MyWhoosh - C", "VirtualRide", NOW - 400 * 86_400L, 10);
        StoredRide outside = ride(4, "Ochtendrit", "Ride", NOW, 80);
        Map<Long, StoredRideStreamStats> st = new HashMap<>();
        StoredRideStreamStats s = new StoredRideStreamStats();
        s.activityId = 1;
        s.workKj = 700;
        s.normalizedPower = 230;
        st.put(1L, s);

        MyWhooshDashboard.Result r = MyWhooshDashboard.compute(
                Arrays.asList(b, a, old, outside), st, NOW, ZoneOffset.UTC);
        assertEquals(3, r.totals.rides);
        assertEquals(60_000, r.totals.distanceM, 1e-6);
        assertEquals(900, r.totals.elevationM, 1e-6);
        assertEquals(700, r.totals.workKj);
        assertEquals(1, r.totals.ridesWithWork);
        assertSame(a, r.latest);
        assertEquals(Integer.valueOf(700), r.latestWorkKj);
        assertEquals(Integer.valueOf(230), r.latestNormalizedPower);

        assertEquals(MyWhooshDashboard.BUCKETS, r.weeks.size());
        assertEquals(30_000, r.weeks.get(11).distanceM, 1e-6);
        assertEquals(20_000, r.weeks.get(10).distanceM, 1e-6);
        // The 400-day-old ride is outside the 12 months.
        double inMonths = 0;
        for (MyWhooshDashboard.Bucket m : r.months) inMonths += m.distanceM;
        assertEquals(50_000, inMonths, 1e-6);
    }

    @Test
    public void emptyArchive() {
        MyWhooshDashboard.Result r = MyWhooshDashboard.compute(null, null, NOW, ZoneOffset.UTC);
        assertNull(r.latest);
        assertEquals(0, r.totals.rides);
        assertEquals(MyWhooshDashboard.BUCKETS, r.months.size());
    }
}
