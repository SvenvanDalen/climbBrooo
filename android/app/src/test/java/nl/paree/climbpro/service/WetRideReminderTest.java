package nl.paree.climbpro.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.ride.WetRideCheck;
import nl.paree.climbpro.domain.weather.HourlyPrecipitation;

import org.junit.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class WetRideReminderTest {

    private static final long DAY = 24L * 3600L;
    /** 2026-09-20 00:00 UTC; weather below spans the 5 days before. */
    private static final long NOW = Instant.parse("2026-09-20T00:00:00Z").getEpochSecond();

    private static StoredRide ride(long id, long startSec, String type) {
        StoredRide r = new StoredRide();
        r.activityId = id;
        r.name = "Rit " + id;
        r.type = type;
        r.startEpochSec = startSec;
        r.elapsedTimeSec = 7200;
        r.startLat = 50.0;
        r.startLon = 5.0;
        return r;
    }

    /** Every hour of the 5 days before NOW has {@code mmPerHour}. */
    private static HourlyPrecipitation weather(double mmPerHour) {
        int n = 5 * 24;
        Instant[] t = new Instant[n];
        double[] mm = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = Instant.ofEpochSecond(NOW - 5 * DAY + 3600L * (i + 1));
            mm[i] = mmPerHour;
        }
        return new HourlyPrecipitation(t, mm);
    }

    /** Fake source counting calls; fails with IOException from call {@code failFrom} (1-based). */
    private static final class FakeSource implements WetRideReminder.PrecipitationSource {
        final HourlyPrecipitation answer;
        final int failFrom;
        int calls;

        FakeSource(HourlyPrecipitation answer, int failFrom) {
            this.answer = answer;
            this.failFrom = failFrom;
        }

        @Override public HourlyPrecipitation fetch(double lat, double lon) throws IOException {
            calls++;
            if (failFrom > 0 && calls >= failFrom) throw new IOException("offline");
            return answer;
        }
    }

    @Test public void wetRideIsRecordedAsWet() {
        FakeSource src = new FakeSource(weather(1.0), 0);
        List<StoredRide> rides = new ArrayList<>();
        rides.add(ride(1L, NOW - DAY, "Ride"));
        List<WetRideCheck> out = WetRideReminder.check(rides, new HashSet<Long>(), NOW, src);
        assertEquals(1, out.size());
        assertEquals(1L, out.get(0).activityId);
        assertTrue(out.get(0).wet);
        assertFalse(out.get(0).offroad);
        assertEquals(NOW, out.get(0).checkedAtSec);
        assertEquals(3.0, out.get(0).precipitationMm, 1e-9); // 1 h lead + 2 h ride
    }

    @Test public void dryRideIsRecordedSoItIsNotFetchedAgain() {
        FakeSource src = new FakeSource(weather(0.0), 0);
        List<StoredRide> rides = new ArrayList<>();
        rides.add(ride(1L, NOW - DAY, "Ride"));
        List<WetRideCheck> out = WetRideReminder.check(rides, new HashSet<Long>(), NOW, src);
        assertEquals(1, out.size());
        assertFalse(out.get(0).wet);
    }

    @Test public void alreadyCheckedRideIsNotFetchedAgain() {
        FakeSource src = new FakeSource(weather(1.0), 0);
        List<StoredRide> rides = new ArrayList<>();
        rides.add(ride(1L, NOW - DAY, "Ride"));
        Set<Long> checked = new HashSet<>();
        checked.add(1L);
        assertTrue(WetRideReminder.check(rides, checked, NOW, src).isEmpty());
        assertEquals(0, src.calls);
    }

    @Test public void oldAndIndoorRidesAreSkippedWithoutFetching() {
        FakeSource src = new FakeSource(weather(1.0), 0);
        List<StoredRide> rides = new ArrayList<>();
        rides.add(ride(1L, NOW - 30 * DAY, "Ride"));      // backfilled archive ride
        rides.add(ride(2L, NOW - DAY, "VirtualRide"));    // indoor
        StoredRide noGps = ride(3L, NOW - DAY, "Ride");
        noGps.startLat = null;
        rides.add(noGps);
        rides.add(null);
        assertTrue(WetRideReminder.check(rides, new HashSet<Long>(), NOW, src).isEmpty());
        assertEquals(0, src.calls);
    }

    @Test public void unknownWeatherIsNotRecorded() {
        FakeSource src = new FakeSource(weather(Double.NaN), 0);
        List<StoredRide> rides = new ArrayList<>();
        rides.add(ride(1L, NOW - DAY, "Ride"));
        assertTrue(WetRideReminder.check(rides, new HashSet<Long>(), NOW, src).isEmpty());
        assertEquals(1, src.calls);
    }

    @Test public void ioErrorStopsTheRunButKeepsEarlierResults() {
        FakeSource src = new FakeSource(weather(1.0), 2);
        List<StoredRide> rides = new ArrayList<>();
        rides.add(ride(1L, NOW - 2 * DAY, "Ride"));
        rides.add(ride(2L, NOW - DAY, "Ride"));
        rides.add(ride(3L, NOW - DAY / 2, "Ride"));
        List<WetRideCheck> out = WetRideReminder.check(rides, new HashSet<Long>(), NOW, src);
        assertEquals(1, out.size());
        assertEquals(3L, out.get(0).activityId); // newest first
        assertEquals(2, src.calls);              // stopped after the first failure
    }

    @Test public void fetchesAreCappedPerRun() {
        FakeSource src = new FakeSource(weather(0.0), 0);
        List<StoredRide> rides = new ArrayList<>();
        for (int i = 0; i < 15; i++) rides.add(ride(i, NOW - DAY - i * 600L, "Ride"));
        List<WetRideCheck> out = WetRideReminder.check(rides, new HashSet<Long>(), NOW, src);
        assertEquals(WetRideReminder.MAX_FETCHES_PER_RUN, src.calls);
        assertEquals(WetRideReminder.MAX_FETCHES_PER_RUN, out.size());
        assertEquals(0L, out.get(0).activityId); // newest (latest start) first
    }

    @Test public void offroadFlagIsStored() {
        FakeSource src = new FakeSource(weather(0.1), 0);
        List<StoredRide> rides = new ArrayList<>();
        rides.add(ride(1L, NOW - DAY, "GravelRide"));
        List<WetRideCheck> out = WetRideReminder.check(rides, new HashSet<Long>(), NOW, src);
        assertTrue(out.get(0).offroad);
        assertTrue(out.get(0).wet); // 26 h × 0.1 mm = 2.6 mm ≥ 0.5 mm
    }

    @Test public void nullInputsAreHarmless() {
        FakeSource src = new FakeSource(weather(1.0), 0);
        assertTrue(WetRideReminder.check(null, null, NOW, src).isEmpty());
        assertTrue(WetRideReminder.check(Collections.<StoredRide>emptyList(),
                new HashSet<Long>(), NOW, src).isEmpty());
    }
}
