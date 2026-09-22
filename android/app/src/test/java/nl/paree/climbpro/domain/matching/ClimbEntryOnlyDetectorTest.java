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
        // Track enters and keeps heading toward the climb's end (straight north, same
        // direction as start->end), covering well past 1.5x the climb length without ever
        // getting within the exit gate (simulates riding straight past on a parallel road
        // slightly to the side, or a route whose real end is a bit further than climbLengthM
        // suggests). Genuine progress toward the end, so it should still be flagged.
        List<TrackSample> track = straightNorthTrack(20, 45.000, 6.0, 0.001, 0, 60);

        int distance = ClimbEntryOnlyDetector.detectIncomplete(
                track, 45.000, 6.0, 45.020, 6.0, 1000); // end further north than the track reaches

        assertTrue("expected a flagged incomplete pass, got " + distance, distance >= 0);
    }

    @Test
    public void enteredNearStart_thenDivergedAwayFromEnd_isNotFlagged() {
        // Track enters within GATE_M of the climb start (an incidental pass near a shared
        // junction), but then heads due EAST for the rest of the ride instead of continuing
        // toward the climb's end (due north of the start). It covers well past 1.5x the
        // climb length, but never gets meaningfully closer to the end — this is not a genuine
        // (even if unfinished) attempt at the climb and must not be flagged.
        List<TrackSample> track = new ArrayList<>();
        long t = 0;
        for (int i = 0; i < 20; i++) {
            track.add(new TrackSample(45.000, 6.0 + i * 0.001, t));
            t += 60;
        }

        int distance = ClimbEntryOnlyDetector.detectIncomplete(
                track, 45.000, 6.0, 45.020, 6.0, 1000); // end is north; track goes east

        assertEquals(-1, distance);
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
