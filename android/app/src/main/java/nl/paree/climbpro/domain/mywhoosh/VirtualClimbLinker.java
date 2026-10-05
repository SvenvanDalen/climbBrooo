package nl.paree.climbpro.domain.mywhoosh;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Links a MyWhoosh climb that recreates a real one (Alpe d'Huez, Hautacam, ...) to that real
 * climb in the collection, and back (issue #395). Two climbs belong together when their names
 * (climb name plus route name) share a distinctive word and their profiles agree: length within
 * {@link #LENGTH_TOLERANCE} and average gradient within {@link #GRADIENT_TOLERANCE}. Of several
 * matches the one with the closest length wins. Pure; phone-only.
 */
public final class VirtualClimbLinker {

    private VirtualClimbLinker() {}

    static final double LENGTH_TOLERANCE = 0.20;
    /** Absolute difference in average gradient, as a fraction (1.5 percentage points). */
    static final double GRADIENT_TOLERANCE = 0.015;
    static final int MIN_WORD_LENGTH = 4;

    /** Words that say nothing about which mountain it is. */
    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
            "mywhoosh", "summit", "climb", "klim", "route", "loop", "ronde", "stage", "etappe",
            "virtual", "from", "naar", "door", "with", "over", "full", "short", "long",
            "kort", "lang", "deel", "part", "north", "south", "east", "west", "noord", "zuid",
            "oost", "via", "side", "kant", "road", "weg", "pass", "pas", "col", "mont", "monte",
            "mount", "berg", "alto", "puerto", "passo", "cote", "colle", "start", "finish",
            "naar", "flat", "rit", "tour"));

    public static final class Candidate {
        public final String climbId;
        public final String name;
        public final String routeName;
        public final int lengthM;
        /** Fraction, e.g. 0.081. */
        public final double avgGradient;
        public final boolean virtual;
        /** Opaque handle for the caller, e.g. route id + climb index to open the climb. */
        public final String routeId;
        public final int climbIndex;

        public Candidate(String climbId, String name, String routeName, int lengthM,
                         double avgGradient, boolean virtual, String routeId, int climbIndex) {
            this.climbId = climbId;
            this.name = name;
            this.routeName = routeName;
            this.lengthM = lengthM;
            this.avgGradient = avgGradient;
            this.virtual = virtual;
            this.routeId = routeId;
            this.climbIndex = climbIndex;
        }
    }

    /**
     * The best counterpart of {@code climb} on the other side (a real climb for a virtual one
     * and vice versa), or null.
     */
    public static Candidate counterpart(Candidate climb, List<Candidate> all) {
        if (climb == null || all == null || climb.lengthM <= 0) return null;
        Set<String> words = words(climb);
        if (words.isEmpty()) return null;
        Candidate best = null;
        double bestDiff = Double.MAX_VALUE;
        for (Candidate c : all) {
            if (c == null || c.virtual == climb.virtual || c.lengthM <= 0) continue;
            if (climb.climbId != null && climb.climbId.equals(c.climbId)) continue;
            if (!profilesMatch(climb, c)) continue;
            Set<String> shared = words(c);
            shared.retainAll(words);
            if (shared.isEmpty()) continue;
            double diff = Math.abs(c.lengthM - climb.lengthM) / (double) climb.lengthM;
            if (diff < bestDiff) {
                bestDiff = diff;
                best = c;
            }
        }
        return best;
    }

    static boolean profilesMatch(Candidate a, Candidate b) {
        double lengthDiff = Math.abs(a.lengthM - b.lengthM) / (double) Math.max(a.lengthM, b.lengthM);
        return lengthDiff <= LENGTH_TOLERANCE
                && Math.abs(a.avgGradient - b.avgGradient) <= GRADIENT_TOLERANCE;
    }

    /** Distinctive lower-case words without accents from the climb and route name. */
    static Set<String> words(Candidate c) {
        Set<String> out = new HashSet<>();
        addWords(c.name, out);
        addWords(c.routeName, out);
        return out;
    }

    private static void addWords(String text, Set<String> out) {
        if (text == null) return;
        String plain = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        for (String w : plain.split("[^a-z0-9]+")) {
            if (w.length() >= MIN_WORD_LENGTH && !STOP_WORDS.contains(w)
                    && !w.chars().allMatch(Character::isDigit)) {
                out.add(w);
            }
        }
    }
}
