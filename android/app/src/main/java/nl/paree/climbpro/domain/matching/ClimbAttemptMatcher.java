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

    /** One GPS fix: position + absolute epoch seconds. */
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

        int entryIdx = nearestWithin(track, startLat, startLon, 0);
        if (entryIdx < 0) return -1;

        int exitIdx = nearestWithin(track, endLat, endLon, entryIdx + 1);
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

    /** Index of the sample at/after {@code fromIdx} nearest to (lat,lon) within GATE_M, or -1. */
    private static int nearestWithin(List<TrackSample> track,
                                     double lat, double lon, int fromIdx) {
        int best = -1;
        double bestDist = GATE_M;
        for (int i = fromIdx; i < track.size(); i++) {
            TrackSample s = track.get(i);
            double d = CumulativeDistance.haversine(lat, lon, s.lat, s.lon);
            if (d <= bestDist) { bestDist = d; best = i; }
        }
        return best;
    }
}
