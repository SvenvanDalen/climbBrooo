package nl.paree.climbpro.domain.power;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.ride.StoredRideStreamStats;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FtpTestResultDetectorTest {

    private static final long DAY = 86_400L;
    private static final long NOW = 1_800_000_000L;

    private final List<StoredRide> rides = new ArrayList<>();
    private final Map<Long, StoredRideStreamStats> stats = new HashMap<>();

    private void ride(long id, String name, String type, long start, int best20) {
        StoredRide r = new StoredRide();
        r.activityId = id;
        r.name = name;
        r.type = type;
        r.startEpochSec = start;
        r.elapsedTimeSec = 3600;
        rides.add(r);
        StoredRideStreamStats s = new StoredRideStreamStats();
        s.activityId = id;
        s.hasStreams = true;
        s.powerCurve = best20 < 0 ? null : new int[]{900, 400, 320, best20, best20 - 20};
        stats.put(id, s);
    }

    private FtpTestResultDetector.Result detect(long exportedAt, int currentFtp, long handled) {
        return FtpTestResultDetector.detect(rides, stats, exportedAt, currentFtp, handled, NOW);
    }

    @Test
    public void nothingWithoutRides() {
        assertNull(detect(NOW - DAY, 250, 0));
    }

    @Test
    public void rideNamedFtpTestIsDetected() {
        ride(1, "MyWhoosh - FTP Test", "VirtualRide", NOW - 2 * DAY, 300);
        FtpTestResultDetector.Result r = detect(0, 250, 0);
        assertNotNull(r);
        assertEquals(1, r.ride.activityId);
        assertEquals(300, r.twentyMinuteWatts);
        assertEquals(285, r.ftpWatts);
        assertEquals(FtpTestResultDetector.Reason.NAMED, r.reason);
    }

    @Test
    public void namedRideTooLongAgoIsIgnored() {
        ride(1, "FTP-test", "VirtualRide",
                NOW - (FtpTestResultDetector.NAMED_LOOKBACK_DAYS + 1) * DAY, 300);
        assertNull(detect(0, 250, 0));
    }

    @Test
    public void unnamedRideAfterExportIsDetected() {
        ride(1, "MyWhoosh - Tuscany", "VirtualRide", NOW - DAY, 270);
        FtpTestResultDetector.Result r = detect(NOW - 3 * DAY, 250, 0);
        assertNotNull(r);
        assertEquals(FtpTestResultDetector.Reason.AFTER_EXPORT, r.reason);
        assertEquals(257, r.ftpWatts);
    }

    @Test
    public void unnamedRideBeforeExportIsIgnored() {
        ride(1, "Ochtendrit", "Ride", NOW - 5 * DAY, 270);
        assertNull(detect(NOW - 3 * DAY, 250, 0));
    }

    @Test
    public void unnamedRideAfterWindowIsIgnored() {
        long exported = NOW - (FtpTestResultDetector.EXPORT_WINDOW_DAYS + 5) * DAY;
        ride(1, "Ochtendrit", "Ride",
                exported + (FtpTestResultDetector.EXPORT_WINDOW_DAYS + 1) * DAY, 270);
        assertNull(detect(exported, 250, 0));
    }

    @Test
    public void easyRideAfterExportIsNotATest() {
        // 200 W for 20 min implies 190 W: far below the 250 W FTP, so not an all-out effort.
        ride(1, "Herstelrit", "VirtualRide", NOW - DAY, 200);
        assertNull(detect(NOW - 3 * DAY, 250, 0));
    }

    @Test
    public void namedTestMayShowADrop() {
        // An explicitly named test is trusted even when FTP fell (e.g. after illness).
        ride(1, "FTP test", "VirtualRide", NOW - DAY, 200);
        FtpTestResultDetector.Result r = detect(0, 250, 0);
        assertNotNull(r);
        assertEquals(190, r.ftpWatts);
    }

    @Test
    public void withoutFtpAnyRideAfterExportCounts() {
        ride(1, "Rit", "VirtualRide", NOW - DAY, 180);
        assertNotNull(detect(NOW - 3 * DAY, 0, 0));
    }

    @Test
    public void hardestRideInTheWindowIsTheTest() {
        ride(1, "Rit A", "VirtualRide", NOW - 2 * DAY, 262);
        ride(2, "Rit B", "VirtualRide", NOW - DAY, 280);
        ride(3, "Rit C", "VirtualRide", NOW - DAY / 2, 250);
        FtpTestResultDetector.Result r = detect(NOW - 3 * DAY, 250, 0);
        assertEquals(2, r.ride.activityId);
    }

    @Test
    public void latestNamedTestWins() {
        ride(1, "FTP test", "VirtualRide", NOW - 10 * DAY, 320);
        ride(2, "FTP test", "VirtualRide", NOW - 2 * DAY, 300);
        assertEquals(2, detect(0, 250, 0).ride.activityId);
    }

    @Test
    public void laterOfNamedAndWindowWins() {
        ride(1, "FTP test", "VirtualRide", NOW - 10 * DAY, 320);
        ride(2, "Rit", "VirtualRide", NOW - DAY, 300);
        assertEquals(2, detect(NOW - 3 * DAY, 250, 0).ride.activityId);
    }

    @Test
    public void handledRideIsNotOfferedAgain() {
        ride(1, "FTP test", "VirtualRide", NOW - DAY, 300);
        assertNull(detect(0, 250, 1));
    }

    @Test
    public void rideWithoutPowerOrShorterThanTwentyMinutesIsIgnored() {
        ride(1, "FTP test", "VirtualRide", NOW - DAY, -1);
        ride(2, "FTP test", "VirtualRide", NOW - DAY, 0);
        assertNull(detect(0, 250, 0));
    }

    @Test
    public void rideWithoutStreamStatsIsIgnored() {
        ride(1, "FTP test", "VirtualRide", NOW - DAY, 300);
        stats.clear();
        assertNull(detect(0, 250, 0));
    }

    @Test
    public void eBikeRideIsIgnored() {
        ride(1, "FTP test", "EBikeRide", NOW - DAY, 300);
        assertNull(detect(0, 250, 0));
    }

    @Test
    public void implausibleResultIsIgnored() {
        ride(1, "FTP test", "VirtualRide", NOW - DAY, 900);
        assertNull(detect(0, 250, 0));
    }

    @Test
    public void nameMatchIsCaseInsensitiveAndWholeWord() {
        assertEquals(true, FtpTestResultDetector.isNamedTest("MyWhoosh - ftp test"));
        assertEquals(true, FtpTestResultDetector.isNamedTest("20min FTP"));
        assertEquals(true, FtpTestResultDetector.isNamedTest("FTP-test 20 min (ClimbPro)"));
        assertEquals(false, FtpTestResultDetector.isNamedTest("Softpedal"));
        assertEquals(false, FtpTestResultDetector.isNamedTest(null));
    }
}
