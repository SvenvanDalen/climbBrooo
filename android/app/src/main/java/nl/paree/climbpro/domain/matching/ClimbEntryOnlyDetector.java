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
     * @return distance (m) of PROGRESS toward the climb's end — {@code climbLengthM -
     *         minDistToEnd} at the closest approach the track ever made to the end coordinate
     *         — for the last genuine incomplete pass found in the track; -1 if the track never
     *         entered the climb, or no candidate entry produced a genuine incomplete pass.
     *
     *         <p>Unlike an earlier version of this method, this is NOT raw path length
     *         traveled since entry: raw path length double-counts backtracking (a rider who
     *         climbs 300 m up and rides back down 300 m before the track ends would otherwise
     *         report 600 m of "progress" on a track whose farthest reach was only 300 m).
     *         "Progress toward the end" is well-defined even across a backtrack.
     *
     *         <p>The entry-gate search retries from {@code entryIdx + 1} whenever a candidate
     *         entry doesn't yield a genuine incomplete pass (mirroring {@link
     *         ClimbAttemptMatcher#findAllPasses}), so an incidental/spurious early gate hit
     *         (e.g. an unrelated road crossing near the climb-start coordinate) doesn't lock
     *         out a later, genuine attempt. When a candidate entry's scan actually reaches the
     *         exit gate, that window is a completed pass (not incomplete); the search resumes
     *         after its exit and keeps looking for a later, separate incomplete attempt. Across
     *         multiple genuine incomplete candidates in one track (e.g. two separate abandoned
     *         attempts in an out-and-back ride), the LAST one found is reported — the rider's
     *         final, most recent attempt in the activity is the one most representative of "how
     *         far did I get" for a never-completed-climbs overview.
     */
    public static int detectIncomplete(List<TrackSample> track,
                                       double startLat, double startLon,
                                       double endLat, double endLon,
                                       int climbLengthM) {
        if (track == null || track.size() < 2 || climbLengthM <= 0) return -1;

        double cutoff = CUTOFF_LENGTH_MULTIPLIER * climbLengthM;
        int best = -1;
        int searchFrom = 0;
        while (true) {
            int entryIdx = firstWithin(track, startLat, startLon, searchFrom);
            if (entryIdx < 0) break; // no more candidate entries

            TrackSample entry = track.get(entryIdx);
            double distToEndAtEntry = CumulativeDistance.haversine(endLat, endLon, entry.lat, entry.lon);
            if (distToEndAtEntry <= ClimbAttemptMatcher.GATE_M) {
                // entry point itself is already within the exit gate — not a valid candidate;
                // retry from the next sample so a later, genuine entry is still found.
                searchFrom = entryIdx + 1;
                continue;
            }

            double minDistToEnd = distToEndAtEntry;
            double covered = 0;
            int exitIdx = -1;
            for (int i = entryIdx; i < track.size() - 1; i++) {
                TrackSample a = track.get(i);
                double distToEnd = CumulativeDistance.haversine(endLat, endLon, a.lat, a.lon);
                if (distToEnd <= ClimbAttemptMatcher.GATE_M) {
                    exitIdx = i;
                    break; // reached the exit gate — this window is a completed pass
                }
                if (distToEnd < minDistToEnd) minDistToEnd = distToEnd;
                TrackSample b = track.get(i + 1);
                covered += CumulativeDistance.haversine(a.lat, a.lon, b.lat, b.lon);
                // Check b — the point that may have just pushed `covered` past the cutoff —
                // against the exit gate BEFORE applying the cutoff early-return. Otherwise a
                // track that wandered past the cutoff distance but whose very last processed
                // sample actually landed inside the exit gate (a genuine, if untidy,
                // completion) would be misflagged as incomplete purely because the cutoff
                // check ran against `a` (the point before the crossing) and never evaluated
                // `b` (the point that actually crossed it).
                double bDistToEnd = CumulativeDistance.haversine(endLat, endLon, b.lat, b.lon);
                if (bDistToEnd <= ClimbAttemptMatcher.GATE_M) {
                    exitIdx = i + 1;
                    break;
                }
                if (bDistToEnd < minDistToEnd) minDistToEnd = bDistToEnd;
                if (covered >= cutoff) {
                    break; // rode well past the climb without exiting; stop scanning this window
                }
            }

            if (exitIdx >= 0) {
                // A completed pass — not incomplete. Resume searching after its exit for a
                // later, separate incomplete attempt in the rest of the track.
                searchFrom = exitIdx + 1;
                continue;
            }

            // Track ran out, or hit the cutoff, without ever reaching the exit gate in this
            // window — only flag if the track was plausibly heading toward the climb's end,
            // not just accumulating distance in some unrelated direction after an incidental
            // entry-gate pass.
            if (madeProgressTowardEnd(distToEndAtEntry, minDistToEnd)) {
                best = (int) Math.round(Math.max(0.0, climbLengthM - minDistToEnd));
            }
            // Retry from the next sample after this entry so a spurious/implausible early
            // gate hit doesn't block a later genuine attempt from being found.
            searchFrom = entryIdx + 1;
        }
        return best;
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
