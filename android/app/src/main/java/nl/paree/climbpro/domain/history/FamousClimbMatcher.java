package nl.paree.climbpro.domain.history;

import nl.paree.climbpro.domain.route.CumulativeDistance;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * Finds the famous climb (issue #212) a stored climb corresponds to. Pure.
 *
 * <p>Three tiers, strongest first:
 * <ol>
 *   <li>{@link Kind#START_AND_TOP}: the climb's top is near the famous top <em>and</em> its
 *       foot is near one of the listed sides' start points. Closest (top + start) wins.</li>
 *   <li>{@link Kind#TOP}: only the top is near (a side missing from the dataset, or a climb
 *       whose detected foot was trimmed differently). A col is identified by its summit.</li>
 *   <li>{@link Kind#NAME}: the climb's (user) name contains an alias as whole words, and —
 *       when the climb has coordinates — it lies within {@link #NAME_MAX_DISTANCE_M} of the
 *       famous top, so a local "Muur" or a namesake col elsewhere isn't mislabelled.</li>
 * </ol>
 * Tolerances scale with the straight-line foot-to-top distance of the famous side, so a short
 * Flemish hill gets a tight radius and an Alpine col a wide one.
 */
public final class FamousClimbMatcher {

    public enum Kind { START_AND_TOP, TOP, NAME }

    public static final class Match {
        public final FamousClimb climb;
        public final Kind kind;
        /** Label of the matched side, or null for a top-only / name match. */
        public final String sideLabel;

        Match(FamousClimb climb, Kind kind, String sideLabel) {
            this.climb = climb;
            this.kind = kind;
            this.sideLabel = sideLabel;
        }
    }

    static final double TOP_TOL_MIN_M = 600;
    static final double TOP_TOL_MAX_M = 1500;
    static final double START_TOL_MIN_M = 800;
    static final double START_TOL_MAX_M = 3000;
    /** Top tolerance for a famous climb without any listed side. */
    static final double TOP_TOL_NO_SIDES_M = 1000;
    static final double NAME_MAX_DISTANCE_M = 30_000;

    private FamousClimbMatcher() {}

    /**
     * @param startLat,startLon foot of the stored climb (NaN when unknown)
     * @param topLat,topLon     top of the stored climb (NaN when unknown)
     * @param names             the climb's names (user name, detected name); nulls ignored
     * @return the best match, or null when none
     */
    public static Match match(List<FamousClimb> dataset,
                              double startLat, double startLon,
                              double topLat, double topLon,
                              String... names) {
        if (dataset == null || dataset.isEmpty()) return null;
        boolean hasStart = valid(startLat, startLon);
        boolean hasTop = valid(topLat, topLon);

        if (hasTop) {
            Match best = null;
            double bestScore = Double.MAX_VALUE;
            Match bestTopOnly = null;
            double bestTopOnlyDist = Double.MAX_VALUE;
            for (FamousClimb fc : dataset) {
                double dTop = CumulativeDistance.haversine(topLat, topLon, fc.topLat, fc.topLon);
                double topTol = TOP_TOL_NO_SIDES_M;
                boolean topNear = false;
                for (FamousClimb.Side side : fc.sides) {
                    double span = CumulativeDistance.haversine(side.lat, side.lon, fc.topLat, fc.topLon);
                    double sideTopTol = clamp(0.2 * span, TOP_TOL_MIN_M, TOP_TOL_MAX_M);
                    if (dTop > sideTopTol) continue;
                    topNear = true;
                    if (!hasStart) continue;
                    double dStart = CumulativeDistance.haversine(startLat, startLon, side.lat, side.lon);
                    double startTol = clamp(0.4 * span, START_TOL_MIN_M, START_TOL_MAX_M);
                    if (dStart <= startTol && dTop + dStart < bestScore) {
                        bestScore = dTop + dStart;
                        best = new Match(fc, Kind.START_AND_TOP, side.label);
                    }
                }
                if (fc.sides.isEmpty() && dTop <= topTol) topNear = true;
                if (topNear && dTop < bestTopOnlyDist) {
                    bestTopOnlyDist = dTop;
                    bestTopOnly = new Match(fc, Kind.TOP, null);
                }
            }
            if (best != null) return best;
            if (bestTopOnly != null) return bestTopOnly;
        }

        return matchByName(dataset, hasStart, startLat, startLon, hasTop, topLat, topLon, names);
    }

    private static Match matchByName(List<FamousClimb> dataset,
                                     boolean hasStart, double startLat, double startLon,
                                     boolean hasTop, double topLat, double topLon,
                                     String... names) {
        if (names == null) return null;
        Match best = null;
        int bestLen = 0;
        for (String raw : names) {
            if (raw == null) continue;
            String padded = " " + normalize(raw) + " ";
            if (padded.trim().isEmpty()) continue;
            for (FamousClimb fc : dataset) {
                if (!withinNameRange(fc, hasStart, startLat, startLon, hasTop, topLat, topLon)) {
                    continue;
                }
                for (String alias : fc.aliases) {
                    // Longest alias wins: "muur van geraardsbergen" beats a shorter overlap.
                    if (alias.length() > bestLen && padded.contains(" " + alias + " ")) {
                        bestLen = alias.length();
                        best = new Match(fc, Kind.NAME, null);
                    }
                }
            }
        }
        return best;
    }

    private static boolean withinNameRange(FamousClimb fc,
                                           boolean hasStart, double startLat, double startLon,
                                           boolean hasTop, double topLat, double topLon) {
        if (!hasStart && !hasTop) return true; // nothing to contradict the name
        double d = Double.MAX_VALUE;
        if (hasTop) d = CumulativeDistance.haversine(topLat, topLon, fc.topLat, fc.topLon);
        if (hasStart) {
            d = Math.min(d, CumulativeDistance.haversine(startLat, startLon, fc.topLat, fc.topLon));
        }
        return d <= NAME_MAX_DISTANCE_M;
    }

    /** Lower-case, accents stripped, every non-letter/digit run collapsed to one space. */
    public static String normalize(String s) {
        if (s == null) return "";
        String decomposed = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return decomposed.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }

    /** Coordinates are usable: finite, in range, and not the (0,0) "unset" placeholder. */
    private static boolean valid(double lat, double lon) {
        if (Double.isNaN(lat) || Double.isNaN(lon)) return false;
        if (lat == 0 && lon == 0) return false;
        return lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
