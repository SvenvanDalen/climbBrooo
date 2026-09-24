package nl.paree.climbpro.domain.climb;

import java.util.Locale;

/**
 * Makes elevation totals tangible (issue #251): "3,2× de Eiffeltoren", "een halve Mont
 * Ventoux". Pure; phone-only presentation helper.
 */
public final class ElevationComparisons {

    private static final class Landmark {
        final String name;
        final int heightM;
        final boolean building;
        Landmark(String name, int heightM, boolean building) {
            this.name = name; this.heightM = heightM; this.building = building;
        }
    }

    /** Ascending by height. Climbs use their elevation gain, not the summit altitude. */
    private static final Landmark[] LANDMARKS = {
            new Landmark("Domtoren", 112, true),
            new Landmark("Euromast", 185, true),
            new Landmark("Eiffeltoren", 330, true),
            new Landmark("Burj Khalifa", 828, true),
            new Landmark("Alpe d'Huez", 1_071, false),
            new Landmark("Mont Ventoux", 1_610, false),
            new Landmark("Kilimanjaro", 5_895, false),
            new Landmark("Mount Everest", 8_849, false),
    };

    private ElevationComparisons() {}

    /** Dutch comparison phrase for {@code metres} of elevation gain, or null when {@code <= 0}. */
    public static String describe(long metres) {
        if (metres <= 0) return null;
        for (int i = LANDMARKS.length - 1; i >= 0; i--) {
            Landmark l = LANDMARKS[i];
            double half = l.heightM / 2.0;
            if (l.heightM >= 1000 && Math.abs(metres - half) <= 0.05 * half) {
                return "een halve " + l.name;
            }
        }
        Landmark pick = null;
        for (Landmark l : LANDMARKS) {
            if (l.heightM <= metres) pick = l;
        }
        if (pick == null) {
            return Math.round(100.0 * metres / LANDMARKS[0].heightM) + "% van de " + LANDMARKS[0].name;
        }
        double ratio = (double) metres / pick.heightM;
        // Round first: 9,96 must read "10×", not "10,0×".
        double oneDecimal = Math.round(ratio * 10) / 10.0;
        String r = oneDecimal < 10 ? String.format(new Locale("nl"), "%.1f", oneDecimal)
                : String.valueOf(Math.round(ratio));
        return r + "× " + (pick.building ? "de " : "") + pick.name;
    }
}
