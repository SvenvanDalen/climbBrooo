package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.data.route.StoredClimb;

import java.util.Locale;

/**
 * Rules for the rider's own climb rating (issue #244): three 1–5 star aspects (wegdek,
 * verkeer — 5 = rustig —, uitzicht) plus an optional note. One place for validation,
 * averaging, labels and sort order so the detail screen, route list and logbook agree. Pure.
 */
public final class ClimbRating {

    public static final int MIN_STARS = 1;
    public static final int MAX_STARS = 5;

    private static final Locale NL = new Locale("nl", "NL");

    private ClimbRating() {}

    /** Returns {@code stars} when it is 1–5, otherwise null (not rated). */
    public static Integer normalize(Integer stars) {
        if (stars == null || stars < MIN_STARS || stars > MAX_STARS) return null;
        return stars;
    }

    /** Trimmed note, or null when null/blank. */
    public static String normalizeNote(String note) {
        if (note == null) return null;
        String t = note.trim();
        return t.isEmpty() ? null : t;
    }

    public static boolean isRated(StoredClimb c) {
        return average(c) != null;
    }

    /** Mean of the valid aspect scores, or null when none is set. */
    public static Double average(StoredClimb c) {
        if (c == null) return null;
        int sum = 0;
        int n = 0;
        Integer[] scores = {normalize(c.ratingRoad), normalize(c.ratingTraffic),
                normalize(c.ratingView)};
        for (Integer s : scores) {
            if (s != null) {
                sum += s;
                n++;
            }
        }
        return n == 0 ? null : (double) sum / n;
    }

    /** {@code "★ 4,3"}, or "" when {@code average} is null. */
    public static String badge(Double average) {
        return average == null ? "" : String.format(NL, "★ %.1f", average);
    }

    public static String badge(StoredClimb c) {
        return badge(average(c));
    }

    /** {@code "Wegdek 5/5 · Verkeer –/5 · Uitzicht 4/5"}, or "" when unrated. */
    public static String breakdown(StoredClimb c) {
        if (!isRated(c)) return "";
        return "Wegdek " + stars(c.ratingRoad)
                + " · Verkeer " + stars(c.ratingTraffic)
                + " · Uitzicht " + stars(c.ratingView);
    }

    /** Text for the climb detail screen: badge + breakdown, then the note in quotes. */
    public static String detailText(StoredClimb c) {
        String note = c != null ? normalizeNote(c.ratingNote) : null;
        StringBuilder sb = new StringBuilder();
        if (isRated(c)) sb.append(badge(c)).append(" · ").append(breakdown(c));
        if (note != null) {
            if (sb.length() > 0) sb.append('\n');
            sb.append('“').append(note).append('”');
        }
        return sb.length() == 0 ? "Nog niet beoordeeld" : sb.toString();
    }

    /** Highest average first; null (unrated) sorts last. */
    public static int compareBestFirst(Double a, Double b) {
        if (a == null && b == null) return 0;
        if (a == null) return 1;
        if (b == null) return -1;
        return Double.compare(b, a);
    }

    /** Writes a (normalized) rating onto {@code target}; all-null clears it. */
    public static void apply(StoredClimb target, Integer road, Integer traffic, Integer view,
                             String note) {
        target.ratingRoad = normalize(road);
        target.ratingTraffic = normalize(traffic);
        target.ratingView = normalize(view);
        target.ratingNote = normalizeNote(note);
    }

    /** Copies the four rating fields verbatim (used by resync merge and propagation). */
    public static void copy(StoredClimb from, StoredClimb to) {
        to.ratingRoad = from.ratingRoad;
        to.ratingTraffic = from.ratingTraffic;
        to.ratingView = from.ratingView;
        to.ratingNote = from.ratingNote;
    }

    private static String stars(Integer v) {
        Integer n = normalize(v);
        return (n == null ? "–" : String.valueOf(n)) + "/" + MAX_STARS;
    }
}
