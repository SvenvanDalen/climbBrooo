package nl.paree.climbpro.domain.tire;

import java.util.Locale;

/**
 * Parsing and formatting of logged tire pressures (issue #155). The log is kept in bar with one
 * decimal (what Dutch floor pumps show first); {@link #barToPsi} lets the UI show the psi
 * equivalent so a logged value can be compared with the psi ranges of the advice (issue #90,
 * {@code TirePressureConstants}). Pure, no Android dependency.
 */
public final class TirePressureUnits {

    private TirePressureUnits() {}

    public static final double PSI_PER_BAR = 14.5038;

    /** Plausible bicycle range: covers low-pressure MTB/gravel up to high-pressure track tyres. */
    public static final double MIN_BAR = 0.5;
    public static final double MAX_BAR = 12.0;

    /**
     * Parses user input such as "6,5" or "6.5" to bar rounded to one decimal, or null when empty,
     * not a number, or outside [{@link #MIN_BAR}, {@link #MAX_BAR}].
     */
    public static Double parseBar(String input) {
        if (input == null) return null;
        String s = input.trim().replace(',', '.');
        if (s.isEmpty()) return null;
        double v;
        try {
            v = Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
        if (Double.isNaN(v) || v < MIN_BAR || v > MAX_BAR) return null;
        return roundOneDecimal(v);
    }

    public static double roundOneDecimal(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    public static int barToPsi(double bar) {
        return (int) Math.round(bar * PSI_PER_BAR);
    }

    /** "6,5 bar (94 psi)" in the default locale's decimal separator. */
    public static String format(double bar) {
        return String.format(Locale.getDefault(), "%.1f bar (%d psi)", bar, barToPsi(bar));
    }

    /**
     * Like {@link #format(double)} but with the rider's preferred unit first (issue #262):
     * "94 psi (6,5 bar)" when {@code psiFirst}, otherwise "6,5 bar (94 psi)".
     */
    public static String format(double bar, boolean psiFirst) {
        if (!psiFirst) return format(bar);
        return String.format(Locale.getDefault(), "%d psi (%.1f bar)", barToPsi(bar), bar);
    }

    /**
     * Parses user input in psi (issue #262) to bar rounded to one decimal — the log stays in
     * bar — or null when empty, not a number, or outside the plausible bar range.
     */
    public static Double parsePsi(String input) {
        if (input == null) return null;
        String s = input.trim().replace(',', '.');
        if (s.isEmpty()) return null;
        double psi;
        try {
            psi = Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
        if (Double.isNaN(psi)) return null;
        double bar = roundOneDecimal(psi / PSI_PER_BAR);
        if (bar < MIN_BAR || bar > MAX_BAR) return null;
        return bar;
    }

    /** Parses input in the rider's pressure unit (issue #262) to bar. */
    public static Double parse(String input, boolean psi) {
        return psi ? parsePsi(input) : parseBar(input);
    }
}
