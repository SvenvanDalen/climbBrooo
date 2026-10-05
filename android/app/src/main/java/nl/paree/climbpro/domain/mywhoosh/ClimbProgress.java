package nl.paree.climbpro.domain.mywhoosh;

import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.domain.rider.WeightHistory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Progression of every attempt on one climb (issue #387): time, average power and W/kg per
 * attempt, oldest first, with the fastest time and the highest W/kg marked. Indoor repeats of
 * a MyWhoosh climb are perfectly comparable, but outdoor attempts work the same way. A
 * deviated attempt (cut switchback) is shown but can't hold the time PR, like the logbook.
 * W/kg uses the weight on the attempt's date (issue #408). Pure; phone-only.
 */
public final class ClimbProgress {

    private ClimbProgress() {}

    public static final class Point {
        public final long dateEpochSec;
        public final int elapsedSec;
        /** Null when the attempt has no power (outdoor without power meter, or older match). */
        public final Integer avgWatts;
        public final Double wattsPerKg;
        public final boolean indoor;
        public final boolean timePr;
        public final boolean powerPr;

        Point(long dateEpochSec, int elapsedSec, Integer avgWatts, Double wattsPerKg,
              boolean indoor, boolean timePr, boolean powerPr) {
            this.dateEpochSec = dateEpochSec;
            this.elapsedSec = elapsedSec;
            this.avgWatts = avgWatts;
            this.wattsPerKg = wattsPerKg;
            this.indoor = indoor;
            this.timePr = timePr;
            this.powerPr = powerPr;
        }
    }

    /**
     * @param indoorActivityIds activity ids of indoor rides, to tell the two apart; may be null
     */
    public static List<Point> compute(String climbId, List<StoredClimbAttempt> attempts,
                                      java.util.Set<Long> indoorActivityIds,
                                      WeightHistory weights) {
        if (climbId == null || attempts == null) return Collections.emptyList();
        List<StoredClimbAttempt> mine = new ArrayList<>();
        for (StoredClimbAttempt a : attempts) {
            if (a != null && climbId.equals(a.climbId) && a.elapsedSec > 0) mine.add(a);
        }
        mine.sort((x, y) -> x.dateEpochSec != y.dateEpochSec
                ? Long.compare(x.dateEpochSec, y.dateEpochSec)
                : Integer.compare(x.startOffsetSec, y.startOffsetSec));

        StoredClimbAttempt fastest = null;
        StoredClimbAttempt strongest = null;
        double bestWkg = 0;
        Double[] wkg = new Double[mine.size()];
        for (int i = 0; i < mine.size(); i++) {
            StoredClimbAttempt a = mine.get(i);
            wkg[i] = weights != null ? weights.wattsPerKg(a.avgWatts, a.dateEpochSec) : null;
            if (!a.routeDeviation && (fastest == null || a.elapsedSec < fastest.elapsedSec)) {
                fastest = a;
            }
            if (wkg[i] != null && wkg[i] > bestWkg) {
                bestWkg = wkg[i];
                strongest = a;
            }
        }
        List<Point> out = new ArrayList<>();
        for (int i = 0; i < mine.size(); i++) {
            StoredClimbAttempt a = mine.get(i);
            out.add(new Point(a.dateEpochSec, a.elapsedSec, a.avgWatts, wkg[i],
                    indoorActivityIds != null && indoorActivityIds.contains(a.activityId),
                    a == fastest, a == strongest));
        }
        return out;
    }
}
