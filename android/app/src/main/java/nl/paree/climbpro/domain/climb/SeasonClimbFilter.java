package nl.paree.climbpro.domain.climb;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;

/**
 * Pure filter for the "batch-export a whole season" feature (issue #91): given every
 * stored route and every stored climb attempt, picks out the (route, climb) pairs that
 * were actually ridden within a chosen period, for {@link BatchClimbGpxWriter} to export.
 *
 * <p>"Season" is modelled as a calendar year (UTC) via {@link #yearRange}, which turns a
 * year into the {@code [from, to)} epoch-second bounds {@link #climbsInPeriod} takes; a
 * caller wanting an arbitrary date range (not a whole year) can build those bounds itself
 * and call {@link #climbsInPeriod} directly.
 *
 * <p>A climb "was ridden in the period" is decided via {@link ClimbIdentity}, the same
 * route-independent key {@link SegmentPrCalculator} and {@link LogbookCalculator} already
 * match attempts against — so a climb whose geometry appears in more than one stored route
 * (e.g. re-imported, or ridden as part of two different GPX files) is matched independently
 * per route it currently lives in, and each occurrence is exported as its own track.
 */
public final class SeasonClimbFilter {

    private SeasonClimbFilter() {}

    /** One climb that had an attempt in the period, with the route it belongs to. */
    public static final class Match {
        public final StoredRoute route;
        public final StoredClimb climb;
        public final int climbIndex;

        public Match(StoredRoute route, StoredClimb climb, int climbIndex) {
            this.route = route;
            this.climb = climb;
            this.climbIndex = climbIndex;
        }
    }

    /**
     * @param routes                every stored route as full documents (not catalog
     *                              entries — climb geometry only lives in the route JSON).
     * @param attempts               every stored climb attempt, any climb/route.
     * @param fromEpochSecInclusive period start, inclusive.
     * @param toEpochSecExclusive   period end, exclusive.
     * @return one {@link Match} per (route, climb) that has at least one attempt whose
     *         {@link StoredClimbAttempt#dateEpochSec} falls in {@code [from, to)}. Order
     *         follows {@code routes}, then climb order within each route.
     */
    public static List<Match> climbsInPeriod(List<StoredRoute> routes,
            List<StoredClimbAttempt> attempts, long fromEpochSecInclusive,
            long toEpochSecExclusive) {
        Set<String> climbIdsInPeriod = new HashSet<>();
        if (attempts != null) {
            for (StoredClimbAttempt a : attempts) {
                if (a == null || a.climbId == null) continue;
                if (a.dateEpochSec >= fromEpochSecInclusive
                        && a.dateEpochSec < toEpochSecExclusive) {
                    climbIdsInPeriod.add(a.climbId);
                }
            }
        }

        List<Match> out = new ArrayList<>();
        if (routes == null || climbIdsInPeriod.isEmpty()) return out;
        for (StoredRoute route : routes) {
            if (route == null || route.climbs == null) continue;
            for (int i = 0; i < route.climbs.size(); i++) {
                StoredClimb c = route.climbs.get(i);
                if (c == null) continue;
                int len = c.length > 0 ? c.length : (c.endDistance - c.startDistance);
                String climbId = ClimbIdentity.of(c.startLat, c.startLon, len);
                if (climbIdsInPeriod.contains(climbId)) {
                    out.add(new Match(route, c, i));
                }
            }
        }
        return out;
    }

    /** {@code [fromInclusive, toExclusive)} epoch-second bounds for calendar year {@code year}, UTC. */
    public static long[] yearRange(int year) {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.clear();
        cal.set(year, Calendar.JANUARY, 1, 0, 0, 0);
        long from = cal.getTimeInMillis() / 1000L;
        cal.set(year + 1, Calendar.JANUARY, 1, 0, 0, 0);
        long to = cal.getTimeInMillis() / 1000L;
        return new long[] {from, to};
    }

    /**
     * Distinct calendar years (UTC) that have at least one dated attempt, newest first —
     * feeds the year picker so it only ever offers years that actually have data.
     */
    public static List<Integer> yearsWithAttempts(List<StoredClimbAttempt> attempts) {
        if (attempts == null || attempts.isEmpty()) return Collections.emptyList();
        TreeSet<Integer> years = new TreeSet<>(Collections.reverseOrder());
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        for (StoredClimbAttempt a : attempts) {
            if (a == null || a.dateEpochSec <= 0) continue;
            cal.setTimeInMillis(a.dateEpochSec * 1000L);
            years.add(cal.get(Calendar.YEAR));
        }
        return new ArrayList<>(years);
    }
}
