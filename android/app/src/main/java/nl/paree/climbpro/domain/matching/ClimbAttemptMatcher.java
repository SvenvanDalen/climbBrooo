package nl.paree.climbpro.domain.matching;

import nl.paree.climbpro.domain.route.CumulativeDistance;

import java.util.List;

/**
 * Matches a recorded GPS track against a single known climb.
 *
 * Strategy: find the track sample nearest the climb start (the entry) and, at a
 * later index, the sample nearest the climb end (the exit). Both must lie within
 * {@link #GATE_M}. The distance covered between entry and exit must be within
 * {@link #LENGTH_TOLERANCE} of the climb length, which rejects crossing roads and
 * partial passes. Returns the elapsed time on the climb in seconds, or -1.
 */
public final class ClimbAttemptMatcher {

    /** Max distance (m) a track sample may be from the climb start/end to count. */
    public static final double GATE_M = 40.0;
    /** Covered distance must be within ±25% of the climb length. */
    public static final double LENGTH_TOLERANCE = 0.25;

    private ClimbAttemptMatcher() {}

    /**
     * One GPS fix: position + a time value.
     *
     * The matcher only uses the DIFFERENCE between the exit and entry {@code timeSec},
     * so any consistent time base works: absolute epoch seconds OR seconds relative to
     * the activity start. Callers must use one consistent base for all samples in a track.
     */
    public static final class TrackSample {
        public final double lat;
        public final double lon;
        public final long   timeSec;

        public TrackSample(double lat, double lon, long timeSec) {
            this.lat = lat;
            this.lon = lon;
            this.timeSec = timeSec;
        }
    }

    /** @return elapsed seconds on the climb, or -1 if no valid attempt is found. */
    public static int match(List<TrackSample> track,
                            double startLat, double startLon,
                            double endLat, double endLon,
                            int climbLengthM) {
        if (track == null || track.size() < 2 || climbLengthM <= 0) return -1;

        int entryIdx = firstWithin(track, startLat, startLon, 0);
        if (entryIdx < 0) return -1;

        int exitIdx = firstWithin(track, endLat, endLon, entryIdx + 1);
        if (exitIdx < 0 || exitIdx <= entryIdx) return -1;

        double covered = 0;
        for (int i = entryIdx; i < exitIdx; i++) {
            TrackSample a = track.get(i);
            TrackSample b = track.get(i + 1);
            covered += CumulativeDistance.haversine(a.lat, a.lon, b.lat, b.lon);
        }
        double tol = LENGTH_TOLERANCE * climbLengthM;
        if (Math.abs(covered - climbLengthM) > tol) return -1;

        long elapsed = track.get(exitIdx).timeSec - track.get(entryIdx).timeSec;
        if (elapsed <= 0) return -1;
        return (int) elapsed;
    }

    /**
     * Same gating/tolerance rules as {@link #match}, but also splits the elapsed time
     * across the climb's segments so a per-segment PR can be tracked.
     *
     * The track between entry and exit is walked once, accumulating covered distance;
     * each segment boundary's crossing time is found by linear interpolation between the
     * two track samples straddling it. Segment N's split is boundary[N] - boundary[N-1]
     * (boundary[-1] = entry time).
     *
     * @param segLengthsM per-segment length (m) in climb order, summing to ~climbLengthM.
     * @return per-segment elapsed seconds (length == segLengthsM.length), or null if no
     *         valid attempt is found (mirrors {@link #match} returning -1).
     */
    public static int[] matchSegments(List<TrackSample> track,
                                      double startLat, double startLon,
                                      double endLat, double endLon,
                                      int climbLengthM, int[] segLengthsM) {
        if (segLengthsM == null || segLengthsM.length == 0) return null;
        if (track == null || track.size() < 2 || climbLengthM <= 0) return null;

        int entryIdx = firstWithin(track, startLat, startLon, 0);
        if (entryIdx < 0) return null;

        int exitIdx = firstWithin(track, endLat, endLon, entryIdx + 1);
        if (exitIdx < 0 || exitIdx <= entryIdx) return null;

        double covered = 0;
        for (int i = entryIdx; i < exitIdx; i++) {
            TrackSample a = track.get(i);
            TrackSample b = track.get(i + 1);
            covered += CumulativeDistance.haversine(a.lat, a.lon, b.lat, b.lon);
        }
        double tol = LENGTH_TOLERANCE * climbLengthM;
        if (Math.abs(covered - climbLengthM) > tol) return null;

        long entryTime = track.get(entryIdx).timeSec;
        long exitTime  = track.get(exitIdx).timeSec;
        if (exitTime - entryTime <= 0) return null;

        // Cumulative segment boundary distances from the climb start.
        double[] boundaries = new double[segLengthsM.length];
        double cum = 0;
        for (int i = 0; i < segLengthsM.length; i++) {
            cum += segLengthsM[i];
            boundaries[i] = cum;
        }

        long[] boundaryTime = new long[segLengthsM.length];
        double distSoFar = 0;
        int sampleIdx = entryIdx;
        for (int b = 0; b < boundaries.length; b++) {
            double target = boundaries[b];
            // Advance until the segment [sampleIdx, sampleIdx+1] straddles `target`,
            // or we run out of track (clamp to exit).
            while (sampleIdx < exitIdx) {
                TrackSample a = track.get(sampleIdx);
                TrackSample bSample = track.get(sampleIdx + 1);
                double stepDist = CumulativeDistance.haversine(a.lat, a.lon, bSample.lat, bSample.lon);
                if (distSoFar + stepDist >= target || sampleIdx == exitIdx - 1) {
                    double into = target - distSoFar;
                    double frac = (stepDist > 0) ? Math.max(0.0, Math.min(1.0, into / stepDist)) : 0.0;
                    boundaryTime[b] = a.timeSec + Math.round((bSample.timeSec - a.timeSec) * frac);
                    break;
                }
                distSoFar += stepDist;
                sampleIdx++;
            }
        }

        int[] splits = new int[segLengthsM.length];
        long prevTime = entryTime;
        for (int b = 0; b < boundaries.length; b++) {
            long t = (b == boundaries.length - 1) ? exitTime : boundaryTime[b];
            // Clamp: boundary times must be monotonic and within [entryTime, exitTime].
            if (t < prevTime) t = prevTime;
            if (t > exitTime) t = exitTime;
            splits[b] = (int) (t - prevTime);
            prevTime = t;
        }
        return splits;
    }

    /** Index of the first sample at/after {@code fromIdx} within GATE_M of (lat,lon), or -1. */
    private static int firstWithin(List<TrackSample> track,
                                   double lat, double lon, int fromIdx) {
        for (int i = fromIdx; i < track.size(); i++) {
            TrackSample s = track.get(i);
            if (CumulativeDistance.haversine(lat, lon, s.lat, s.lon) <= GATE_M) {
                return i;
            }
        }
        return -1;
    }
}
