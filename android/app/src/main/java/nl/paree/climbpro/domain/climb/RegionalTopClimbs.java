package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.domain.route.CumulativeDistance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Top 10 zwaarste klimmen in je regio" (issue #211). Pure, Android-free ranking of the
 * known climbs whose start lies within a radius of a point, hardest first by
 * {@link DifficultyScoreCalculator}. Phone-only; nothing here touches the wire format.
 *
 * <p>The score is the calculator's standalone form ({@code distanceIntoRouteKm = 0}, i.e. no
 * fatigue bonus): a regional ranking compares climbs on their own merit, independent of
 * where they happen to sit in whichever route they were imported from.
 *
 * <p>The same physical climb often appears in several routes. Candidates are deduplicated on
 * their route-independent {@link ClimbIdentity} key (falling back to route + index when it is
 * missing); the hardest version is kept, and on an exact tie the first one seen.
 *
 * <p>Ordering: score descending, then distance to the point ascending, then name — so the
 * list is deterministic even for identical scores.
 */
public final class RegionalTopClimbs {

    /** Used when the caller passes {@code limit <= 0}. */
    public static final int DEFAULT_LIMIT = 10;

    private RegionalTopClimbs() {}

    /** A known climb that may be ranked. Coordinates in degrees, distances in metres. */
    public static final class Candidate {
        /** Route-independent identity ({@link ClimbIdentity}); used for dedupe. May be null. */
        public final String climbId;
        public final String routeId;
        public final int    climbIndex;
        public final String name;
        public final double startLat;
        public final double startLon;
        public final int    elevationGainM;
        /** Average gradient as a fraction (0.072 = 7.2 %). */
        public final double avgGradient;
        public final int    lengthM;

        public Candidate(String climbId, String routeId, int climbIndex, String name,
                         double startLat, double startLon, int elevationGainM,
                         double avgGradient, int lengthM) {
            this.climbId        = climbId;
            this.routeId        = routeId;
            this.climbIndex     = climbIndex;
            this.name           = name;
            this.startLat       = startLat;
            this.startLon       = startLon;
            this.elevationGainM = elevationGainM;
            this.avgGradient    = avgGradient;
            this.lengthM        = lengthM;
        }

        String dedupeKey() {
            return climbId != null ? climbId : ("route:" + routeId + "#" + climbIndex);
        }
    }

    /** A ranked climb: the candidate plus its difficulty score and distance to the point. */
    public static final class Ranked {
        public final Candidate candidate;
        public final double    score;
        /** Straight-line distance from the query point to the climb start, in metres. */
        public final double    distanceM;

        Ranked(Candidate candidate, double score, double distanceM) {
            this.candidate = candidate;
            this.score     = score;
            this.distanceM = distanceM;
        }
    }

    /**
     * @param lat        query latitude (e.g. the phone's last known location)
     * @param lon        query longitude
     * @param radiusM    only climbs whose start lies within this many metres are ranked
     * @param candidates known climbs; null entries and entries with invalid coordinates are skipped
     * @param limit      maximum number of results; {@code <= 0} means {@link #DEFAULT_LIMIT}
     * @return at most {@code limit} climbs, hardest first; empty on invalid input
     */
    public static List<Ranked> top(double lat, double lon, double radiusM,
                                   List<Candidate> candidates, int limit) {
        if (candidates == null || !(radiusM > 0) || !validCoord(lat, lon)) {
            return Collections.emptyList();
        }
        int max = limit > 0 ? limit : DEFAULT_LIMIT;

        Map<String, Ranked> byKey = new LinkedHashMap<>();
        for (Candidate c : candidates) {
            if (c == null || !validCoord(c.startLat, c.startLon)) continue;
            double d = CumulativeDistance.haversine(lat, lon, c.startLat, c.startLon);
            if (d > radiusM) continue;
            Ranked r = new Ranked(c,
                    DifficultyScoreCalculator.score(c.elevationGainM, c.avgGradient, 0), d);
            Ranked existing = byKey.get(c.dedupeKey());
            if (existing == null || r.score > existing.score) byKey.put(c.dedupeKey(), r);
        }

        List<Ranked> out = new ArrayList<>(byKey.values());
        Collections.sort(out, ORDER);
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    private static final Comparator<Ranked> ORDER = (a, b) -> {
        int c = Double.compare(b.score, a.score);
        if (c != 0) return c;
        c = Double.compare(a.distanceM, b.distanceM);
        if (c != 0) return c;
        String an = a.candidate.name != null ? a.candidate.name : "";
        String bn = b.candidate.name != null ? b.candidate.name : "";
        return an.compareToIgnoreCase(bn);
    };

    private static boolean validCoord(double lat, double lon) {
        return !Double.isNaN(lat) && !Double.isNaN(lon)
                && Math.abs(lat) <= 90 && Math.abs(lon) <= 180;
    }
}
