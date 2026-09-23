package nl.paree.climbpro.domain.matching;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.domain.matching.ClimbAttemptMatcher.TrackSample;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class ClimbRouteDeviationDetectorTest {

    /** Straight climb from 45.000,6.0 to 45.009,6.0 (~1000 m north), 11 track samples. */
    private static List<TrackSample> straightTrack() {
        List<TrackSample> t = new ArrayList<>();
        for (int i = 0; i <= 10; i++) {
            t.add(new TrackSample(45.000 + i * 0.0009, 6.0, i * 60L));
        }
        return t;
    }

    /** Calibration polyline following the same straight line as {@link #straightTrack()}. */
    private static double[][] straightCalib() {
        double[] lats = new double[11];
        double[] lons = new double[11];
        for (int i = 0; i <= 10; i++) {
            lats[i] = 45.000 + i * 0.0009;
            lons[i] = 6.0;
        }
        return new double[][]{lats, lons};
    }

    @Test
    public void straightMatch_noDeviation() {
        List<TrackSample> track = straightTrack();
        double[][] calib = straightCalib();

        boolean deviated = ClimbRouteDeviationDetector.isDeviated(
                track, 0, track.size() - 1, calib[0], calib[1]);

        assertFalse(deviated);
    }

    @Test
    public void typicalGpsJitter_withinTolerance_notFlagged() {
        // Same route, but every track sample nudged ~20-30m east of the known line —
        // well inside MAX_DEVIATION_M (75m), representative of ordinary GPS noise.
        List<TrackSample> straight = straightTrack();
        List<TrackSample> jittered = new ArrayList<>();
        for (TrackSample s : straight) {
            jittered.add(new TrackSample(s.lat, s.lon + 0.00025, s.timeSec)); // ~20m @ this latitude
        }
        double[][] calib = straightCalib();

        boolean deviated = ClimbRouteDeviationDetector.isDeviated(
                jittered, 0, jittered.size() - 1, calib[0], calib[1]);

        assertFalse(deviated);
    }

    @Test
    public void cornerCut_sustainedOffset_flaggedAsDeviated() {
        // Track cuts well east of the known road for a sustained stretch in the middle
        // of the climb (a switchback shortcut), then rejoins.
        List<TrackSample> track = new ArrayList<>();
        track.add(new TrackSample(45.000, 6.0, 0));
        track.add(new TrackSample(45.002, 6.0, 120));
        track.add(new TrackSample(45.004, 6.003, 240));  // ~250m east — off corridor
        track.add(new TrackSample(45.005, 6.003, 300));  // still off corridor (consecutive)
        track.add(new TrackSample(45.006, 6.0, 360));
        track.add(new TrackSample(45.009, 6.0, 540));
        double[][] calib = straightCalib();

        boolean deviated = ClimbRouteDeviationDetector.isDeviated(
                track, 0, track.size() - 1, calib[0], calib[1]);

        assertTrue(deviated);
    }

    @Test
    public void singleJitterSpike_notFlagged() {
        // One isolated bad fix far off the road, surrounded by on-route samples — must
        // not trigger a false deviation (requires two consecutive out-of-corridor samples).
        List<TrackSample> track = new ArrayList<>();
        track.add(new TrackSample(45.000, 6.0, 0));
        track.add(new TrackSample(45.003, 6.0, 180));
        track.add(new TrackSample(45.0045, 6.01, 270)); // ~800m spike, single sample
        track.add(new TrackSample(45.006, 6.0, 360));
        track.add(new TrackSample(45.009, 6.0, 540));
        double[][] calib = straightCalib();

        boolean deviated = ClimbRouteDeviationDetector.isDeviated(
                track, 0, track.size() - 1, calib[0], calib[1]);

        assertFalse(deviated);
    }

    @Test
    public void noCalibrationPoints_neverFlagged() {
        List<TrackSample> track = straightTrack();
        boolean deviated = ClimbRouteDeviationDetector.isDeviated(
                track, 0, track.size() - 1, null, null);
        assertFalse(deviated);
    }

    @Test
    public void singleCalibrationPoint_neverFlagged() {
        // Only one calibration point -> no usable geometry to judge against.
        List<TrackSample> track = new ArrayList<>();
        track.add(new TrackSample(45.000, 6.0, 0));
        track.add(new TrackSample(45.005, 6.02, 300)); // wildly off any plausible line
        track.add(new TrackSample(45.009, 6.0, 540));

        boolean deviated = ClimbRouteDeviationDetector.isDeviated(
                track, 0, track.size() - 1, new double[]{45.000}, new double[]{6.0});

        assertFalse(deviated);
    }
}
