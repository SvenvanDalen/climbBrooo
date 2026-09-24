package nl.paree.climbpro.domain.matching;

import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredIncompleteClimbAttempt;
import nl.paree.climbpro.domain.climb.KnownClimb;
import nl.paree.climbpro.domain.matching.ClimbAttemptMatcher.TrackSample;

import java.util.ArrayList;
import java.util.List;

/**
 * Matches one recorded activity track against every known climb, independent of where the
 * track came from: the Strava stream sync and the Garmin/FIT file import (issue #253) share
 * this so both produce identical attempts, splits, deviation flags and incomplete passes.
 */
public final class ActivityClimbMatcher {

    private ActivityClimbMatcher() {}

    /**
     * @param incompleteOut ADDITIONAL, separate output: never-completed passes are appended
     *                      here for climbs that had zero successful passes matched in this
     *                      activity — see {@link ClimbEntryOnlyDetector}. The returned list
     *                      of successful {@link StoredClimbAttempt}s is unaffected.
     */
    public static List<StoredClimbAttempt> match(List<TrackSample> track, List<KnownClimb> climbs,
                                                 long activityId, long dateSec,
                                                 List<StoredIncompleteClimbAttempt> incompleteOut) {
        List<StoredClimbAttempt> out = new ArrayList<>();
        if (track == null || track.size() < 2) return out;
        for (KnownClimb k : climbs) {
            // matchAllPasses finds every valid ascent in the track, not just the first —
            // an out-and-back or loop route can pass over the same climb more than
            // once in a single activity, and each pass should be logged separately.
            List<ClimbAttemptMatcher.PassResult> passes = ClimbAttemptMatcher.matchAllPasses(
                    track, k.startLat, k.startLon, k.endLat, k.endLon,
                    k.lengthM, k.segLengthsM);
            for (int i = 0; i < passes.size(); i++) {
                ClimbAttemptMatcher.PassResult p = passes.get(i);
                StoredClimbAttempt a = new StoredClimbAttempt();
                a.climbId      = k.climbId;
                a.activityId   = activityId;
                a.dateEpochSec = dateSec;
                a.elapsedSec   = p.elapsedSec;
                a.passIndex    = i;
                a.segSplitSec  = p.segSplitSec;
                a.routeDeviation = ClimbRouteDeviationDetector.isDeviated(
                        track, p.entryIdx, p.exitIdx, k.calibLats, k.calibLons);
                out.add(a);
            }

            // Only run entry-only detection when this climb had zero successful passes
            // in this activity — a climb ridden successfully isn't "never completed",
            // even if the rider also looped back over the start gate afterwards.
            if (passes.isEmpty()) {
                int distanceCovered = ClimbEntryOnlyDetector.detectIncomplete(
                        track, k.startLat, k.startLon, k.endLat, k.endLon, k.lengthM);
                if (distanceCovered >= 0) {
                    StoredIncompleteClimbAttempt ia = new StoredIncompleteClimbAttempt();
                    ia.climbId          = k.climbId;
                    ia.activityId       = activityId;
                    ia.dateEpochSec     = dateSec;
                    ia.distanceCoveredM = distanceCovered;
                    incompleteOut.add(ia);
                }
            }
        }
        return out;
    }
}
