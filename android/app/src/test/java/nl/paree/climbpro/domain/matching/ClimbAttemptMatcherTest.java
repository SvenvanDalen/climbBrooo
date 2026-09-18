package nl.paree.climbpro.domain.matching;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.domain.matching.ClimbAttemptMatcher.TrackSample;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class ClimbAttemptMatcherTest {

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
    public void fullPass_returnsElapsedSeconds() {
        // Climb from 45.000 to ~45.009 (~1000 m), length 1000 m.
        // Track: 10 steps of 0.001 deg lat, 60 s each -> passes start and end.
        List<TrackSample> track = straightNorthTrack(11, 45.000, 6.0, 0.001, 1_000, 60);

        int elapsed = ClimbAttemptMatcher.match(
                track, 45.000, 6.0, 45.009, 6.0, 1000);

        // Entry at index 0 (t=1000), exit nearest 45.009 at index 9 (t=1540): 540 s.
        assertEquals(540, elapsed);
    }

    @Test
    public void noNearbyStart_returnsMinusOne() {
        List<TrackSample> track = straightNorthTrack(11, 48.000, 9.0, 0.001, 0, 60);
        int elapsed = ClimbAttemptMatcher.match(
                track, 45.000, 6.0, 45.009, 6.0, 1000);
        assertEquals(-1, elapsed);
    }

    @Test
    public void crossingRoad_tooShortCovered_returnsMinusOne() {
        // Track only reaches the start area then leaves: covered distance far below length.
        List<TrackSample> track = new ArrayList<>();
        track.add(new TrackSample(45.000, 6.0, 0));
        track.add(new TrackSample(45.0005, 6.05, 60));   // veers east, away from the climb line
        track.add(new TrackSample(45.001, 6.10, 120));
        int elapsed = ClimbAttemptMatcher.match(
                track, 45.000, 6.0, 45.009, 6.0, 1000);
        assertEquals(-1, elapsed);
    }

    @Test
    public void outAndBack_matchesAscentNotReturnPass() {
        // Ascent: 45.000 -> 45.009 over indices 0..9 (t=0..540, 60s steps).
        // Descent: back down to 45.000 over indices 10..19 (t=600..1140).
        java.util.List<TrackSample> track = new java.util.ArrayList<>();
        for (int i = 0; i <= 9; i++) track.add(new TrackSample(45.000 + i * 0.001, 6.0, i * 60L));
        for (int i = 1; i <= 9; i++) track.add(new TrackSample(45.009 - i * 0.001, 6.0, 540L + i * 60L));
        int elapsed = ClimbAttemptMatcher.match(track, 45.000, 6.0, 45.009, 6.0, 1000);
        // Entry = first sample within gate of start = index 0 (t=0).
        // Exit  = first sample within gate of end after entry = index 9 (t=540).
        assertEquals(540, elapsed);
    }

    @Test
    public void tooFewSamples_returnsMinusOne() {
        List<TrackSample> track = new ArrayList<>();
        track.add(new TrackSample(45.0, 6.0, 0));
        assertTrue(ClimbAttemptMatcher.match(track, 45.0, 6.0, 45.009, 6.0, 1000) < 0);
    }

    // ---- matchSegments ----------------------------------------------------

    @Test
    public void matchSegments_evenPace_splitsEvenly() {
        // Same fixture as fullPass_returnsElapsedSeconds: 540 s total over 1000 m,
        // split into two 500 m segments -> ~270 s each.
        List<TrackSample> track = straightNorthTrack(11, 45.000, 6.0, 0.001, 1_000, 60);
        int[] segLengths = {500, 500};

        int[] splits = ClimbAttemptMatcher.matchSegments(
                track, 45.000, 6.0, 45.009, 6.0, 1000, segLengths);

        assertNotNull(splits);
        assertEquals(2, splits.length);
        assertEquals(540, splits[0] + splits[1]); // splits sum to the whole-climb elapsed time
        assertTrue("first split roughly half", Math.abs(splits[0] - 270) <= 60);
    }

    @Test
    public void matchSegments_fourSegments_sumsToTotalElapsed() {
        List<TrackSample> track = straightNorthTrack(11, 45.000, 6.0, 0.001, 0, 60);
        int[] segLengths = {250, 250, 250, 250};

        int[] splits = ClimbAttemptMatcher.matchSegments(
                track, 45.000, 6.0, 45.009, 6.0, 1000, segLengths);

        assertNotNull(splits);
        assertEquals(4, splits.length);
        int sum = 0;
        for (int s : splits) { sum += s; }
        assertEquals(540, sum);
    }

    @Test
    public void matchSegments_noValidAttempt_returnsNull() {
        List<TrackSample> track = straightNorthTrack(11, 48.000, 9.0, 0.001, 0, 60);
        int[] splits = ClimbAttemptMatcher.matchSegments(
                track, 45.000, 6.0, 45.009, 6.0, 1000, new int[]{500, 500});
        assertNull(splits);
    }

    @Test
    public void matchSegments_nullSegLengths_returnsNull() {
        List<TrackSample> track = straightNorthTrack(11, 45.000, 6.0, 0.001, 0, 60);
        assertNull(ClimbAttemptMatcher.matchSegments(
                track, 45.000, 6.0, 45.009, 6.0, 1000, null));
    }
}
