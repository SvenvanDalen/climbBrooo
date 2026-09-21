package nl.paree.climbpro.domain.matching;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.domain.matching.ClimbAttemptMatcher.TrackSample;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class ClimbEntryOnlyDetectorTest {

    /** ~111.32 m per 0.001 deg latitude near the equator; good enough for synthetic tracks. */
    private static List<TrackSample> straightNorthTrack(int samples, double startLat,
                                                        double lon, double dLatPerStep,
                                                        long startTimeSec, long stepSec) {
        List<TrackSample> t = new ArrayList<>();
        for (int i = 0; i < samples; i++) {
            t.add(new TrackSample(startLat + i * dLatPerStep, lon,
                    startTimeSec + i * stepSec));
        }
        return t;
    }

    @Test
    public void enteredButNeverExited_appClosedMidClimb_isFlagged() {
        // Climb 45.000 -> 45.009 (~1000 m); track enters but stops at 45.003 (~330 m in).
        List<TrackSample> track = straightNorthTrack(4, 45.000, 6.0, 0.001, 0, 60);

        int distance = ClimbEntryOnlyDetector.detectIncomplete(
                track, 45.000, 6.0, 45.009, 6.0, 1000);

        assertTrue("expected a flagged incomplete pass, got " + distance, distance >= 0);
        assertTrue(distance > 0 && distance < 1000);
    }

    @Test
    public void enteredButNeverExited_turnedBackPastCutoff_isFlagged() {
        // Track enters, rides well past 1.5x the climb length without ever hitting the
        // exit gate (simulates riding straight past / turning back on a parallel road).
        List<TrackSample> track = straightNorthTrack(20, 45.000, 6.0, 0.001, 0, 60);

        int distance = ClimbEntryOnlyDetector.detectIncomplete(
                track, 45.000, 6.0, 999.0, 999.0, 1000); // end coord unreachable

        assertTrue("expected a flagged incomplete pass, got " + distance, distance >= 0);
    }

    @Test
    public void completedClimb_reachesExitGate_isNotFlagged() {
        // Same track as ClimbAttemptMatcher's fullPass_returnsElapsedSeconds test.
        List<TrackSample> track = straightNorthTrack(11, 45.000, 6.0, 0.001, 1_000, 60);

        int distance = ClimbEntryOnlyDetector.detectIncomplete(
                track, 45.000, 6.0, 45.009, 6.0, 1000);

        assertEquals(-1, distance);
    }

    @Test
    public void neverEntered_isNotFlagged() {
        // Track nowhere near the climb start.
        List<TrackSample> track = straightNorthTrack(11, 48.000, 9.0, 0.001, 0, 60);

        int distance = ClimbEntryOnlyDetector.detectIncomplete(
                track, 45.000, 6.0, 45.009, 6.0, 1000);

        assertEquals(-1, distance);
    }

    @Test
    public void nullOrTinyTrack_isNotFlagged() {
        assertEquals(-1, ClimbEntryOnlyDetector.detectIncomplete(
                null, 45.000, 6.0, 45.009, 6.0, 1000));
        assertEquals(-1, ClimbEntryOnlyDetector.detectIncomplete(
                new ArrayList<>(), 45.000, 6.0, 45.009, 6.0, 1000));
    }
}
