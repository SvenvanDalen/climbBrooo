package nl.paree.climbpro.domain.matching;

import nl.paree.climbpro.domain.matching.ClimbAttemptMatcher.TrackSample;
import nl.paree.climbpro.domain.route.CumulativeDistance;

import java.util.List;

/**
 * Detects an "entered but never exited" pass over a known climb: the rider's track came
 * within {@link ClimbAttemptMatcher#GATE_M} of the climb start but never got within that
 * gate of the climb end before the track ran out (app closed / GPS lost) or before a
 * generous distance cutoff past the climb length (turned back, stopped).
 *
 * Deliberately a SEPARATE, small class rather than a change to {@link ClimbAttemptMatcher}:
 * that class is being actively modified by other in-flight work for an unrelated feature
 * (route-deviation PR exclusion), so this duplicates the ~10-line "first sample within
 * GATE_M" helper instead of touching it. Only used for issue #37 (never-completed-climbs
 * overview) — callers should only invoke this for a climb that {@link ClimbAttemptMatcher}
 * found zero successful passes for in the same track, so a climb ridden successfully isn't
 * double-counted as "incomplete".
 */
public final class ClimbEntryOnlyDetector {

    /**
     * How far past the climb length (as a multiple of climb length) the track may continue,
     * still searching for the exit gate, before we give up and call it an incomplete pass.
     * Chosen generously so a climb that just failed {@link ClimbAttemptMatcher}'s stricter
     * ±25% length tolerance (e.g. a wandering GPS track) is not misflagged as "never
     * completed" as long as the rider genuinely reached the end-gate at some point.
     */
    private static final double CUTOFF_LENGTH_MULTIPLIER = 1.5;

    /**
     * Minimum improvement (m) in straight-line distance to the climb's END coordinate,
     * relative to the distance at the entry point, required before a track is considered a
     * plausible (if unfinished) attempt at the climb. Without this, a track that merely
     * passes within {@link ClimbAttemptMatcher#GATE_M} of the climb's start — e.g. a shared
     * road junction unrelated to the climb — and then heads off in a completely different
     * direction for the rest of the ride would still get flagged as "never completed" once it
     * had covered {@link #CUTOFF_LENGTH_MULTIPLIER}x the climb length in ANY direction. Mirrors
     * {@link ClimbAttemptMatcher#GATE_M} as the tolerance budget so ordinary GPS jitter near
     * the entry point doesn't itself fail the check.
     */
    private static final double MIN_PROGRESS_TOWARD_END_M = ClimbAttemptMatcher.GATE_M;

    private ClimbEntryOnlyDetector() {}

    /**
     * @return distance (m) covered from the entry point before detection stopped, if this
     *         track entered the climb but never reached the exit gate; -1 if the track never
     *         entered the climb, or it did reach the exit gate (a complete-enough pass, even
     *         if {@link ClimbAttemptMatcher} would reject it for an unrelated reason such as
     *         length tolerance).
     */
    public static int detectIncomplete(List<TrackSample> track,
                                       double startLat, double startLon,
                                       double endLat, double endLon,
                                       int climbLengthM) {
        if (track == null || track.size() < 2 || climbLengthM <= 0) return -1;

        int entryIdx = firstWithin(track, startLat, startLon, 0);
        if (entryIdx < 0) return -1; // never entered

        TrackSample entry = track.get(entryIdx);
        double distToEndAtEntry = CumulativeDistance.haversine(endLat, endLon, entry.lat, entry.lon);
        double minDistToEnd = distToEndAtEntry;

        double cutoff = CUTOFF_LENGTH_MULTIPLIER * climbLengthM;
        double covered = 0;
        for (int i = entryIdx; i < track.size() - 1; i++) {
            TrackSample a = track.get(i);
            double distToEnd = CumulativeDistance.haversine(endLat, endLon, a.lat, a.lon);
            if (distToEnd <= ClimbAttemptMatcher.GATE_M) {
                return -1; // reached the exit gate — not an incomplete pass
            }
            if (distToEnd < minDistToEnd) minDistToEnd = distToEnd;
            TrackSample b = track.get(i + 1);
            covered += CumulativeDistance.haversine(a.lat, a.lon, b.lat, b.lon);
            if (covered >= cutoff) {
                // rode well past the climb, never exited — but only flag if the track was
                // plausibly heading toward the climb's end, not just accumulating distance
                // in some unrelated direction after an incidental entry-gate pass.
                return madeProgressTowardEnd(distToEndAtEntry, minDistToEnd)
                        ? (int) Math.round(covered) : -1;
            }
        }
        // Final sample: check it too, then either flag (track just ran out) or clear.
        TrackSample last = track.get(track.size() - 1);
        double lastDistToEnd = CumulativeDistance.haversine(endLat, endLon, last.lat, last.lon);
        if (lastDistToEnd <= ClimbAttemptMatcher.GATE_M) {
            return -1;
        }
        if (lastDistToEnd < minDistToEnd) minDistToEnd = lastDistToEnd;
        return madeProgressTowardEnd(distToEndAtEntry, minDistToEnd) ? (int) Math.round(covered) : -1;
    }

    /**
     * @return true if the closest the track ever got to the climb's end, after entry, was
     *         meaningfully closer than the entry point itself was — i.e. the track was
     *         plausibly trending toward the climb's end, not diverging away from it.
     */
    private static boolean madeProgressTowardEnd(double distToEndAtEntry, double minDistToEnd) {
        double requiredProgress = Math.min(MIN_PROGRESS_TOWARD_END_M, distToEndAtEntry);
        return minDistToEnd <= distToEndAtEntry - requiredProgress;
    }

    /** Duplicated from {@link ClimbAttemptMatcher}'s private helper — see class javadoc. */
    private static int firstWithin(List<TrackSample> track, double lat, double lon, int fromIdx) {
        for (int i = fromIdx; i < track.size(); i++) {
            TrackSample s = track.get(i);
            if (CumulativeDistance.haversine(lat, lon, s.lat, s.lon) <= ClimbAttemptMatcher.GATE_M) {
                return i;
            }
        }
        return -1;
    }
}
