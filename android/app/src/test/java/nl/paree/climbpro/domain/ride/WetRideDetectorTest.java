package nl.paree.climbpro.domain.ride;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.domain.weather.HourlyPrecipitation;

import org.junit.Test;

import java.time.Instant;

public class WetRideDetectorTest {

    /** 2026-09-20 10:00 UTC. */
    private static final long START = Instant.parse("2026-09-20T10:00:00Z").getEpochSecond();

    private static StoredRide ride(String type, int elapsedSec) {
        StoredRide r = new StoredRide();
        r.activityId = 42L;
        r.name = "Ochtendrit";
        r.type = type;
        r.startEpochSec = START;
        r.elapsedTimeSec = elapsedSec;
        r.movingTimeSec = elapsedSec;
        r.startLat = 50.85;
        r.startLon = 5.69;
        return r;
    }

    /** Hour ends 2026-09-19T00:00 .. 2026-09-20T23:00 UTC, every hour {@code base} mm, overrides applied. */
    private static HourlyPrecipitation weather(double base, String[] hourEnds, double[] values) {
        Instant first = Instant.parse("2026-09-19T00:00:00Z");
        Instant[] t = new Instant[48];
        double[] mm = new double[48];
        for (int i = 0; i < 48; i++) {
            t[i] = first.plusSeconds(3600L * i);
            mm[i] = base;
        }
        for (int k = 0; k < hourEnds.length; k++) {
            Instant at = Instant.parse(hourEnds[k]);
            for (int i = 0; i < 48; i++) if (t[i].equals(at)) mm[i] = values[k];
        }
        return new HourlyPrecipitation(t, mm);
    }

    @Test public void offroadTypes() {
        assertTrue(WetRideDetector.isOffroad("GravelRide"));
        assertTrue(WetRideDetector.isOffroad("MountainBikeRide"));
        assertTrue(WetRideDetector.isOffroad("EMountainBikeRide"));
        assertFalse(WetRideDetector.isOffroad("Ride"));
        assertFalse(WetRideDetector.isOffroad(null));
    }

    @Test public void endFallsBackToMovingTimeThenOneHour() {
        StoredRide r = ride("Ride", 7200);
        assertEquals(START + 7200, WetRideDetector.endSec(r));
        r.elapsedTimeSec = 0;
        r.movingTimeSec = 1800;
        assertEquals(START + 1800, WetRideDetector.endSec(r));
        r.movingTimeSec = 0;
        assertEquals(START + 3600, WetRideDetector.endSec(r));
    }

    @Test public void recentOutdoorRideIsACandidate() {
        assertTrue(WetRideDetector.isCandidate(ride("Ride", 7200), START + 7200 + 3600));
    }

    @Test public void oldRideIsNotACandidate() {
        StoredRide r = ride("Ride", 7200);
        long end = START + 7200;
        assertTrue(WetRideDetector.isCandidate(r, end + WetRideDetector.MAX_AGE_SEC));
        assertFalse(WetRideDetector.isCandidate(r, end + WetRideDetector.MAX_AGE_SEC + 1));
    }

    @Test public void unfinishedRideIsNotACandidate() {
        assertFalse(WetRideDetector.isCandidate(ride("Ride", 7200), START + 3600));
    }

    @Test public void virtualAndGpslessRidesAreNotCandidates() {
        long now = START + 7200 + 60;
        assertFalse(WetRideDetector.isCandidate(ride("VirtualRide", 7200), now));
        StoredRide noGps = ride("Ride", 7200);
        noGps.startLat = null;
        assertFalse(WetRideDetector.isCandidate(noGps, now));
        StoredRide noDate = ride("Ride", 7200);
        noDate.startEpochSec = 0;
        assertFalse(WetRideDetector.isCandidate(noDate, now));
        assertFalse(WetRideDetector.isCandidate(null, now));
    }

    @Test public void roadRideInRainIsWet() {
        // Ride 10:00–12:00: hours ending 11:00 and 12:00 have 0.6 mm each → 1.2 mm.
        HourlyPrecipitation p = weather(0.0,
                new String[]{"2026-09-20T11:00:00Z", "2026-09-20T12:00:00Z"}, new double[]{0.6, 0.6});
        WetRideDetector.Verdict v = WetRideDetector.judge(ride("Ride", 7200), p);
        assertTrue(v.wet);
        assertFalse(v.offroad);
        assertEquals(1.2, v.mm, 1e-9);
    }

    @Test public void roadRideCountsTheHourBeforeStart() {
        // Rain 09:00–10:00 (stamped 10:00) wets the road for a 10:00 start.
        HourlyPrecipitation p = weather(0.0, new String[]{"2026-09-20T10:00:00Z"}, new double[]{1.0});
        assertTrue(WetRideDetector.judge(ride("Ride", 7200), p).wet);
    }

    @Test public void roadRideIgnoresRainFromTheNightBefore() {
        HourlyPrecipitation p = weather(0.0, new String[]{"2026-09-20T03:00:00Z"}, new double[]{5.0});
        WetRideDetector.Verdict v = WetRideDetector.judge(ride("Ride", 7200), p);
        assertFalse(v.wet);
        assertEquals(0.0, v.mm, 1e-9);
    }

    @Test public void drizzleBelowRoadThresholdIsDry() {
        HourlyPrecipitation p = weather(0.0,
                new String[]{"2026-09-20T11:00:00Z", "2026-09-20T12:00:00Z"}, new double[]{0.4, 0.5});
        assertFalse(WetRideDetector.judge(ride("Ride", 7200), p).wet);
    }

    @Test public void gravelRideAfterNightRainIsMuddy() {
        // Same night rain that a road ride ignores: within 24 h before a gravel ride → modder.
        HourlyPrecipitation p = weather(0.0, new String[]{"2026-09-20T03:00:00Z"}, new double[]{0.5});
        WetRideDetector.Verdict v = WetRideDetector.judge(ride("GravelRide", 7200), p);
        assertTrue(v.wet);
        assertTrue(v.offroad);
    }

    @Test public void allNullHoursMeansUnknown() {
        HourlyPrecipitation p = weather(Double.NaN, new String[0], new double[0]);
        assertNull(WetRideDetector.judge(ride("Ride", 7200), p));
    }

    @Test public void dutchTexts() {
        assertEquals("Natte rit: Ochtendrit", WetRideDetector.title("Ochtendrit"));
        assertEquals("Natte rit: fiets schoonmaken", WetRideDetector.title("  "));
        assertEquals("Natte rit: fiets schoonmaken", WetRideDetector.title(null));
        assertTrue(WetRideDetector.message(false, 1.25).startsWith("Het regende tijdens je rit (1,3 mm)."));
        assertTrue(WetRideDetector.message(true, 0.5).startsWith("Er viel 0,5 mm regen rond je rit: kans op modder."));
    }
}
