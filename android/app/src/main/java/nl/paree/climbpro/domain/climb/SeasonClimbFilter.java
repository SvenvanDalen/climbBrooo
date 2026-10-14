package nl.paree.climbpro.domain.climb;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;

/**
 * Pure filter for the "batch-export a whole season" feature (issue #91): given every
 * stored route and every stored climb attempt, picks out the (route, climb) pairs that
 * were actually ridden within a chosen period, for {@link BatchClimbGpxWriter} to export.
 *
 * <p>"Season" is modelled as a local calendar year via {@link #yearRange}, which turns a
 * year into the {@code [from, to)} epoch-second bounds {@link #climbsInPeriod} takes; a
 * caller wanting an arbitrary date range (not a whole year) can build those bounds itself
 * and call {@link #climbsInPeriod} directly.
 *
 * <p>A climb "was ridden in the period" is decided via {@link ClimbIdentity}, the same
 * route-independent key {@link SegmentPrCalculator} and {@link LogbookCalculator} already
 * match attempts against. A climb whose geometry appears in more than one stored route
 * (e.g. re-imported, or ridden as part of two different GPX files) is exported once, from
 * the first route it is found in — a season export lists climbs ridden, not route copies.
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
     *         {@link StoredClimbAttempt#dateEpochSec} falls in {@code [from, to)}, at most
     *         one per climb identity (first occurrence wins). Order follows {@code routes},
     *         then climb order within each route.
     */
    public static List<Match> climbsInPeriod(List<StoredRoute> routes,
            List<StoredClimbAttempt> attempts, long fromEpochSecInclusive,
            long toEpochSecExclusive) {
        Set<String> climbIdsInPeriod =
                climbIdsInPeriod(attempts, fromEpochSecInclusive, toEpochSecExclusive);

        List<Match> out = new ArrayList<>();
        if (routes == null || climbIdsInPeriod.isEmpty()) return out;
        Set<String> emitted = new HashSet<>();
        for (StoredRoute route : routes) {
            if (route == null || route.climbs == null) continue;
            addMatches(route, climbIdsInPeriod, emitted, out);
        }
        return out;
    }

    private static Set<String> climbIdsInPeriod(List<StoredClimbAttempt> attempts,
                                                long fromEpochSecInclusive,
                                                long toEpochSecExclusive) {
        Set<String> ids = new HashSet<>();
        if (attempts == null) return ids;
        for (StoredClimbAttempt a : attempts) {
            if (a == null || a.climbId == null) continue;
            if (a.dateEpochSec >= fromEpochSecInclusive
                    && a.dateEpochSec < toEpochSecExclusive) {
                ids.add(a.climbId);
            }
        }
        return ids;
    }

    /** Adds every climb of {@code route} ridden in the period that wasn't emitted yet. */
    private static void addMatches(StoredRoute route, Set<String> climbIdsInPeriod,
                                   Set<String> emitted, List<Match> out) {
        for (int i = 0; i < route.climbs.size(); i++) {
            StoredClimb c = route.climbs.get(i);
            if (c == null) continue;
            int len = c.length > 0 ? c.length : (c.endDistance - c.startDistance);
            String climbId = ClimbIdentity.of(c.startLat, c.startLon, len);
            if (climbIdsInPeriod.contains(climbId) && emitted.add(climbId)) {
                out.add(new Match(route, c, i));
            }
        }
    }

    /**
     * {@code [fromInclusive, toExclusive)} epoch-second bounds for calendar year {@code year}
     * in {@code zone} — pass the rider's local zone so a New Year's Eve/Day ride lands in the
     * year the rider experienced it in, not the UTC one.
     */
    public static long[] yearRange(int year, ZoneId zone) {
        long from = LocalDate.of(year, 1, 1).atStartOfDay(zone).toEpochSecond();
        long to = LocalDate.of(year + 1, 1, 1).atStartOfDay(zone).toEpochSecond();
        return new long[] {from, to};
    }

    /**
     * Distinct calendar years (in {@code zone}, same as {@link #yearRange}) that have at
     * least one dated attempt, newest first — feeds the year picker so it only ever offers
     * years that actually have data.
     */
    public static List<Integer> yearsWithAttempts(List<StoredClimbAttempt> attempts,
            ZoneId zone) {
        if (attempts == null || attempts.isEmpty()) return Collections.emptyList();
        TreeSet<Integer> years = new TreeSet<>(Collections.reverseOrder());
        for (StoredClimbAttempt a : attempts) {
            if (a == null || a.dateEpochSec <= 0) continue;
            years.add(Instant.ofEpochSecond(a.dateEpochSec).atZone(zone).getYear());
        }
        return new ArrayList<>(years);
    }
}
