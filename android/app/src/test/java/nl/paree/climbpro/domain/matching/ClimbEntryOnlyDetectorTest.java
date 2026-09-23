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
    public void wanderedPastCutoff_butFinalSampleReachesExitGate_isNotFlagged() {
        // Regression for the off-by-one where the `covered >= cutoff` early-return only ever
        // checked sample `a` (the point BEFORE the segment that pushed `covered` past cutoff),
        // never `b` (the point that actually crossed it) — so a track that detoured east away
        // from the climb before straightening out and landing exactly on the climb's end
        // coordinate on its very last processed sample got misflagged as "incomplete" even
        // though it genuinely finished the climb (just via an untidy, longer-than-tolerance
        // route). Climb: (45.000,6.000) -> (45.009,6.000), length 1000 m, cutoff = 1500 m.
        List<TrackSample> track = new ArrayList<>();
        track.add(new TrackSample(45.000, 6.000, 0));   // entry: exactly at climb start
        track.add(new TrackSample(45.000, 6.012, 60));  // detour east; covered so far < cutoff
        track.add(new TrackSample(45.009, 6.000, 120)); // final sample: exactly at climb end —
                                                          // this segment is what pushes covered
                                                          // past the 1500 m cutoff.

        int distance = ClimbEntryOnlyDetector.detectIncomplete(
                track, 45.000, 6.000, 45.009, 6.000, 1000);

        assertEquals("a track that actually finished the climb on its final sample must not "
                + "be flagged as incomplete just because the cutoff also fired on that segment",
                -1, distance);
    }

    @Test
    public void spuriousEarlyGateHit_thenGenuineLaterAttempt_detectsTheRealOne() {
        // Climb: (45.000,6.000) -> (45.009,6.000), length 1000 m.
        // Track: passes within GATE_M of the climb start early (an incidental junction hit),
        // heads off in an unrelated direction for a while (never getting closer to the climb's
        // end — so the spurious entry alone would not be flagged), returns to the start, and
        // THEN genuinely climbs partway before the track ends. Without entry-gate retry, the
        // detector would lock onto the spurious first entry and report garbage/no progress;
        // with retry, it must find the genuine later attempt.
        List<TrackSample> track = new ArrayList<>();
        long t = 0;
        // Spurious hit at climb start, then diverge east (away from the end, which is north).
        track.add(new TrackSample(45.000, 6.000, t)); t += 60;
        track.add(new TrackSample(45.000, 6.010, t)); t += 60;
        track.add(new TrackSample(45.000, 6.020, t)); t += 60;
        // Return to the start and make a genuine attempt: climb ~330 m north before stopping.
        track.add(new TrackSample(45.000, 6.000, t)); t += 60;
        track.add(new TrackSample(45.001, 6.000, t)); t += 60;
        track.add(new TrackSample(45.002, 6.000, t)); t += 60;
        track.add(new TrackSample(45.003, 6.000, t));

        int distance = ClimbEntryOnlyDetector.detectIncomplete(
                track, 45.000, 6.000, 45.009, 6.000, 1000);

        assertTrue("expected the genuine later attempt to be flagged, got " + distance,
                distance > 0 && distance < 1000);
    }

    @Test
    public void backtracking_reportsFarthestProgressNotRawPathLength() {
        // Climb: (45.000,6.000) -> (45.009,6.000), length ~1000 m. Rider climbs ~300 m up
        // (45.000 -> 45.0027), then rides back down ~300 m toward the start (45.0027 ->
        // 45.0000) before the track ends. Raw accumulated path length would be ~600 m (300 up
        // + 300 down), but real farthest progress toward the end was only ~300 m — the
        // reported distance must reflect the latter, not the former.
        List<TrackSample> track = new ArrayList<>();
        track.add(new TrackSample(45.0000, 6.000, 0));
        track.add(new TrackSample(45.0010, 6.000, 60));
        track.add(new TrackSample(45.0020, 6.000, 120));
        track.add(new TrackSample(45.0027, 6.000, 180)); // farthest point reached (~300 m in)
        track.add(new TrackSample(45.0017, 6.000, 240)); // backtracking
        track.add(new TrackSample(45.0007, 6.000, 300));
        track.add(new TrackSample(45.0000, 6.000, 360)); // back near the start

        int distance = ClimbEntryOnlyDetector.detectIncomplete(
                track, 45.000, 6.000, 45.009, 6.000, 1000);

        assertTrue("expected a flagged incomplete pass, got " + distance, distance >= 0);
        // Farthest progress is ~300 m (45.0027 in, ~ 0.0027 * 111320 ≈ 300.6 m); raw path
        // length traveled would have been nearly double that (~600 m). Assert we're close to
        // the farthest-progress figure, well under the raw-path-length figure.
        assertTrue("expected progress-based distance (~300 m), not raw path length (~600 m), got "
                + distance, distance > 200 && distance < 400);
    }

    @Test
    public void nullOrTinyTrack_isNotFlagged() {
        assertEquals(-1, ClimbEntryOnlyDetector.detectIncomplete(
                null, 45.000, 6.0, 45.009, 6.0, 1000));
        assertEquals(-1, ClimbEntryOnlyDetector.detectIncomplete(
                new ArrayList<>(), 45.000, 6.0, 45.009, 6.0, 1000));
    }
}
