package nl.paree.climbpro.domain.events;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls route distances and elevation out of free event text (issue #241), e.g.
 * "Toertocht 60/100/150 km, 1.850 hoogtemeters" → [60, 100, 150] km and 1850 m. Organisers
 * write these in many ways, so this is deliberately lenient and returns "unknown" rather
 * than guessing when nothing matches.
 */
public final class EventTextStats {

    /** Plausible ride distances; anything outside is a postcode, year or typo. */
    static final int MIN_KM = 10;
    static final int MAX_KM = 400;
    static final int MIN_ELEVATION_M = 50;
    static final int MAX_ELEVATION_M = 10000;

    /**
     * "60/100/150 km", "60, 100 en 150 km", "150km", "150 kilometer". Not preceded by a digit
     * or separator, so "1200 km" is not read as 200.
     */
    private static final Pattern DISTANCES = Pattern.compile(
            "(?<![\\d.,])((?:\\d{2,3}\\s*(?:/|,|-|en|of|&)\\s*)*\\d{2,3})\\s*(?:km|kilometer)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER = Pattern.compile("\\d{2,3}");
    /** "1850 hm", "1.850 hoogtemeters", "2,300 m elevation", "D+ 1850", "1850m D+". */
    private static final Pattern ELEVATION = Pattern.compile(
            "(?:(\\d{1,2}[.,]?\\d{3}|\\d{2,4})\\s*(?:hm|hoogtemeters?|m\\s+hoogte|m\\s+elevation|"
                    + "m\\s+climbing|m\\s*d\\+|hm\\+))|(?:d\\+\\s*:?\\s*(\\d{1,2}[.,]?\\d{3}|\\d{2,4}))",
            Pattern.CASE_INSENSITIVE);

    private EventTextStats() {}

    /** Distinct distances in km, ascending; empty when none found. */
    public static List<Integer> distancesKm(String text) {
        if (text == null) return Collections.emptyList();
        TreeSet<Integer> out = new TreeSet<>();
        Matcher m = DISTANCES.matcher(text);
        while (m.find()) {
            Matcher n = NUMBER.matcher(m.group(1));
            while (n.find()) {
                int km = Integer.parseInt(n.group());
                if (km >= MIN_KM && km <= MAX_KM) out.add(km);
            }
        }
        return new ArrayList<>(out);
    }

    /** Largest elevation mentioned (the longest route's), or null when none found. */
    public static Integer elevationM(String text) {
        if (text == null) return null;
        Integer best = null;
        Matcher m = ELEVATION.matcher(text);
        while (m.find()) {
            String raw = m.group(1) != null ? m.group(1) : m.group(2);
            int v = Integer.parseInt(raw.replace(".", "").replace(",", ""));
            if (v >= MIN_ELEVATION_M && v <= MAX_ELEVATION_M && (best == null || v > best)) {
                best = v;
            }
        }
        return best;
    }
}
