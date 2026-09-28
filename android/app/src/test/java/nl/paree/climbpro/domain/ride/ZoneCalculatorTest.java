package nl.paree.climbpro.domain.ride;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.ride.StoredRideStreamStats;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class ZoneCalculatorTest {

    private static final long NOW = 1_800_000_000L;
    private static final long DAY = 86_400L;

    private static StoredRide ride(long id, String type, long daysAgo) {
        StoredRide r = new StoredRide();
        r.activityId = id;
        r.type = type;
        r.startEpochSec = NOW - daysAgo * DAY;
        return r;
    }

    /** Heart-rate histogram with {@code seconds} at each given bpm. */
    private static int[] hr(int seconds, int... bpms) {
        int[] h = new int[ZoneHistogramAnalyzer.HR_MAX_BPM - ZoneHistogramAnalyzer.HR_MIN_BPM + 1];
        for (int b : bpms) h[b - ZoneHistogramAnalyzer.HR_MIN_BPM] += seconds;
        return h;
    }

    /** Power histogram with {@code seconds} in the 10 W bin of each given wattage. */
    private static int[] power(int seconds, int... watts) {
        int[] p = new int[ZoneHistogramAnalyzer.POWER_MAX_BIN + 1];
        for (int w : watts) p[w / ZoneHistogramAnalyzer.POWER_BIN_WATTS] += seconds;
        return p;
    }

    private static StoredRideStreamStats stats(long id, int[] hr, int[] power) {
        StoredRideStreamStats s = new StoredRideStreamStats();
        s.activityId = id;
        s.hasStreams = true;
        s.hrSecondsPerBpm = hr;
        s.powerSecondsPer10W = power;
        return s;
    }

    @Test
    public void heartRateZonesAreShareOfMaxHr() {
        // Max 200: zone bounds at 120, 140, 160, 180 bpm.
        int[] z = ZoneCalculator.heartRateZones(hr(60, 100, 119, 120, 150, 179, 180, 195), 200);
        assertArrayEquals(new int[]{120, 60, 60, 60, 120}, z);
    }

    @Test
    public void powerZonesAreCogganSharesOfFtp() {
        // FTP 200: bounds at 110, 150, 180, 210, 240, 300 W; each bin is placed by its middle.
        int[] z = ZoneCalculator.powerZones(power(10, 0, 100, 140, 170, 200, 230, 290, 300, 1500),
                200);
        assertArrayEquals(new int[]{20, 10, 10, 10, 10, 10, 20}, z);
    }

    @Test
    public void unknownThresholdsGiveNoZones() {
        assertNull(ZoneCalculator.heartRateZones(hr(60, 150), 0));
        assertNull(ZoneCalculator.powerZones(power(60, 200), 0));
        assertNull(ZoneCalculator.powerZones(null, 250));
    }

    @Test
    public void computeListsNewestFirstAndSumsRecentWindow() {
        StoredRide old = ride(1, "Ride", 40);
        StoredRide recent = ride(2, "Ride", 3);
        StoredRide ebike = ride(3, "EBikeRide", 1);
        ZoneCalculator.Result r = ZoneCalculator.compute(Arrays.asList(old, recent, ebike),
                Arrays.asList(stats(1, hr(100, 130), power(100, 150)),
                        stats(2, hr(50, 130), power(50, 150)),
                        stats(3, hr(10, 190), power(999, 400))),
                200, 200, NOW);

        assertEquals(3, r.entries.size());
        assertEquals(3L, r.entries.get(0).ride.activityId);
        assertNull(r.entries.get(0).powerZones); // e-bike: heart rate only
        assertArrayEquals(new int[]{0, 50, 0, 0, 10}, r.recentHrZones);
        assertArrayEquals(new int[]{0, 0, 50, 0, 0, 0, 0}, r.recentPowerZones); // 155 W: zone 3
    }

    @Test
    public void ridesWithoutAnyDistributionAreSkipped() {
        ZoneCalculator.Result r = ZoneCalculator.compute(
                Collections.singletonList(ride(1, "Ride", 1)),
                Collections.singletonList(stats(1, null, null)), 190, 250, NOW);
        assertEquals(0, r.entries.size());
        assertNull(r.recentHrZones);
    }

    @Test
    public void observedMaxHrIgnoresShortSpikes() {
        int[] h = hr(20, 150);
        h[185 - ZoneHistogramAnalyzer.HR_MIN_BPM] = 20;
        h[210 - ZoneHistogramAnalyzer.HR_MIN_BPM] = 3; // strap glitch
        // 3 s at 210 + 20 s at 185 = 23 s: not enough; add 150's 20 s -> 43 s at >= 150.
        assertEquals(150, ZoneCalculator.observedMaxHr(
                Collections.singletonList(stats(1, h, null))));
        h[184 - ZoneHistogramAnalyzer.HR_MIN_BPM] = 10;
        assertEquals(184, ZoneCalculator.observedMaxHr(
                Collections.singletonList(stats(1, h, null))));
        assertEquals(0, ZoneCalculator.observedMaxHr(null));
    }
}
