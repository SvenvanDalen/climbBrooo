package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.data.route.StoredClimbAttempt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Post-ride "fatigue curve" (issue #21, "Vermoeidheidscurve visualisatie na de rit"): shows how
 * a rider's climbing pace changed across consecutive climbs within the same Strava activity.
 *
 * <h2>Why actual VAM instead of {@link nl.paree.climbpro.domain.power.RouteAwareClimbEstimator}</h2>
 * {@code RouteAwareClimbEstimator}/{@link nl.paree.climbpro.domain.power.ClimbTimeEstimator}
 * build a fatigue-aware *prediction* of climb time from a {@link
 * nl.paree.climbpro.domain.power.RiderProfile} (FTP, mass, W'). Reusing that machinery
 * post-ride would require every rider to have a complete profile and would compare real data
 * against a modelled estimate, which compounds two sources of error. This screen instead uses
 * only data that always exists once an activity is synced: elapsed time per climb attempt
 * ({@link StoredClimbAttempt#elapsedSec}) and the climb's elevation gain. The ratio
 * (elevation gain / elapsed time -> VAM, vertical metres/hour) needs no rider profile at all,
 * and its trend across a ride is exactly the "fatigue curve" the issue asks for: a rider going
 * slower up later climbs, relative to how they went up the first one, is fatigue made visible.
 *
 * <h2>Ordering climbs within a ride</h2>
 * {@link StoredClimbAttempt#dateEpochSec} is the *activity* start time (see {@code
 * StravaActivitiesRepository#matchActivity}), so every attempt from one activity carries the
 * same timestamp — it cannot be used to order distinct climbs chronologically within the ride.
 * Attempts matched since {@link StoredClimbAttempt#startOffsetSec} exists carry their entry
 * offset within the activity, and when every usable attempt of the ride has one they are
 * ordered by it: true ride order, whatever direction or routes were ridden
 * ({@link #isChronological}).
 *
 * <p>For older attempts (offset -1) the fallback below applies: they are ordered by the
 * position of their climb within its route ({@link
 * ClimbRef#orderIndex}, the climb's index in {@code StoredRoute#climbs} — climbs are detected
 * walking the route start-to-end, so this index is a start-distance-along-route proxy for ride
 * order), then by {@link StoredClimbAttempt#passIndex} for repeat ascents of the same climb.
 *
 * <p><b>This is a directional, not chronological, ordering.</b> It only matches true ride order
 * when the rider actually rode the route in the same direction the route was originally
 * recorded/stored in (start-to-end). Nothing in {@link StoredClimbAttempt} distinguishes a ride
 * that covered the route in reverse (e.g. an out-and-back, or a rider starting from what the
 * stored route considers its "end") from a normal forward ride: {@link
 * StoredClimbAttempt#dateEpochSec} is the activity-level start time (identical for every attempt
 * in the same activity, see {@code StravaActivitiesRepository#matchActivity}) and there is no
 * per-attempt start-offset within the track recorded anywhere upstream ({@code
 * ClimbAttemptMatcher#matchAll} does not persist one). For a reversed ride, the climb ridden
 * last in reality is assigned ordinal 1 and used as the 100% VAM baseline, which inverts the
 * resulting curve. Detecting/correcting this would require recording each attempt's start
 * offset within the activity track, which only newer attempts have — so for this fallback
 * callers must surface a caveat to the user (see {@code RideFatigueActivity}'s layout) rather
 * than presenting the curve as unconditionally reliable.
 *
 * <p>Separately, for a ride that touches climbs from more than one route (e.g. rides that leave
 * and rejoin a known route, or free-roam rides that happen to cross two mapped routes) the
 * ordering is a best-effort approximation, grouped by route id first.
 *
 * Pure and stateless, like {@link VamCalculator}.
 */
public final class RideFatigueCurveCalculator {

    private RideFatigueCurveCalculator() {}

    /** Per-climb reference data the caller resolves (route lookup) before calling {@link #computeForActivity}. */
    public static final class ClimbRef {
        public final String displayName;
        public final int    elevationGainM;
        public final String routeId;
        /** Index of this climb within its route's climb list; used as a ride-order proxy (see class Javadoc). */
        public final int    orderIndex;

        public ClimbRef(String displayName, int elevationGainM, String routeId, int orderIndex) {
            this.displayName = displayName;
            this.elevationGainM = elevationGainM;
            this.routeId = routeId;
            this.orderIndex = orderIndex;
        }
    }

    /** One point on the fatigue curve: one climb attempt within the ride, in ride order. */
    public static final class FatiguePoint {
        public final int    ordinal; // 1-based position within the ride
        public final String label;
        public final int    elapsedSec;
        /** Actual VAM for this attempt: elevationGainM / elapsedSec * 3600 (vertical m/h). */
        public final double vamMPerHour;
        /** vamMPerHour relative to the ride's first climb, as a percentage (100 = same pace, &lt;100 = slower). */
        public final double relativeToFirstPct;

        FatiguePoint(int ordinal, String label, int elapsedSec, double vamMPerHour, double relativeToFirstPct) {
            this.ordinal = ordinal;
            this.label = label;
            this.elapsedSec = elapsedSec;
            this.vamMPerHour = vamMPerHour;
            this.relativeToFirstPct = relativeToFirstPct;
        }
    }

    /** Groups attempts by {@link StoredClimbAttempt#activityId}, preserving first-seen activity order. */
    public static Map<Long, List<StoredClimbAttempt>> groupByActivity(List<StoredClimbAttempt> attempts) {
        Map<Long, List<StoredClimbAttempt>> out = new LinkedHashMap<>();
        if (attempts == null) return out;
        for (StoredClimbAttempt a : attempts) {
            out.computeIfAbsent(a.activityId, k -> new ArrayList<>()).add(a);
        }
        return out;
    }

    /**
     * Builds the fatigue curve for a single activity's attempts.
     *
     * @param attemptsForOneActivity attempts sharing one {@code activityId} (not enforced —
     *                               caller is expected to have grouped via {@link #groupByActivity}).
     * @param climbInfoById          climbId -> {@link ClimbRef}, resolved by the caller from the route catalog.
     * @return the ride's fatigue curve, in ride order; empty when fewer than 2 climbs have
     *         usable data (nothing meaningful to chart) or no attempts/lookup were given.
     */
    public static List<FatiguePoint> computeForActivity(
            List<StoredClimbAttempt> attemptsForOneActivity, Map<String, ClimbRef> climbInfoById) {
        if (attemptsForOneActivity == null || attemptsForOneActivity.isEmpty() || climbInfoById == null) {
            return Collections.emptyList();
        }

        List<StoredClimbAttempt> usable = usableAttempts(attemptsForOneActivity, climbInfoById);
        if (usable.size() < 2) {
            return Collections.emptyList();
        }

        if (allHaveStartOffset(usable)) {
            usable.sort(Comparator
                    .comparingInt((StoredClimbAttempt a) -> a.startOffsetSec)
                    .thenComparing(a -> a.climbId));
        } else {
            usable.sort(Comparator
                    .comparing((StoredClimbAttempt a) -> String.valueOf(climbInfoById.get(a.climbId).routeId))
                    .thenComparingInt(a -> climbInfoById.get(a.climbId).orderIndex)
                    .thenComparingInt(a -> a.passIndex)
                    .thenComparing(a -> a.climbId));
        }

        List<FatiguePoint> out = new ArrayList<>(usable.size());
        double firstVam = 0;
        for (int i = 0; i < usable.size(); i++) {
            StoredClimbAttempt a = usable.get(i);
            ClimbRef ref = climbInfoById.get(a.climbId);
            double vam = ref.elevationGainM / (double) a.elapsedSec * 3600.0;
            if (i == 0) firstVam = vam;
            double relPct = firstVam > 0 ? (vam / firstVam) * 100.0 : 100.0;
            out.add(new FatiguePoint(i + 1, ref.displayName, a.elapsedSec, vam, relPct));
        }
        return out;
    }

    /**
     * True when {@link #computeForActivity} will order this ride chronologically (every usable
     * attempt has a {@link StoredClimbAttempt#startOffsetSec}); false when it falls back to the
     * route-position proxy, whose direction caveat the UI must then show.
     */
    public static boolean isChronological(
            List<StoredClimbAttempt> attemptsForOneActivity, Map<String, ClimbRef> climbInfoById) {
        if (attemptsForOneActivity == null || climbInfoById == null) return false;
        List<StoredClimbAttempt> usable = usableAttempts(attemptsForOneActivity, climbInfoById);
        return !usable.isEmpty() && allHaveStartOffset(usable);
    }

    private static List<StoredClimbAttempt> usableAttempts(
            List<StoredClimbAttempt> attempts, Map<String, ClimbRef> climbInfoById) {
        List<StoredClimbAttempt> usable = new ArrayList<>();
        for (StoredClimbAttempt a : attempts) {
            ClimbRef ref = climbInfoById.get(a.climbId);
            // elapsedSec/elevationGainM must both be positive or VAM is undefined/meaningless.
            if (ref == null || a.elapsedSec <= 0 || ref.elevationGainM <= 0) continue;
            // A pass that left the climb's line (issue #77) has a time that isn't comparable,
            // like elsewhere in the app where it's excluded from PRs.
            if (a.routeDeviation) continue;
            usable.add(a);
        }
        return usable;
    }

    private static boolean allHaveStartOffset(List<StoredClimbAttempt> attempts) {
        for (StoredClimbAttempt a : attempts) {
            if (a.startOffsetSec < 0) return false;
        }
        return true;
    }
}
