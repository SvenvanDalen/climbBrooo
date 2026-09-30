package nl.paree.climbpro.domain.maintenance;

import nl.paree.climbpro.data.maintenance.TorqueValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/**
 * Static torque reference (issue #237): typical tightening torques per bike part plus helpers
 * for the rider's own values. Pure Java; the values are guidance only — see {@link #DISCLAIMER}.
 */
public final class TorqueReference {

    /** Upper bound for a custom value; nothing on a bicycle needs more than this. */
    public static final double MAX_NM = 200.0;

    public static final String DISCLAIMER =
            "Richtwaarden. De waarde op het onderdeel of in de handleiding van de fabrikant gaat "
                    + "altijd voor. Bij carbon: gebruik carbon-montagepasta en blijf aan de "
                    + "onderkant van het bereik.";

    /** One reference row: a part with its typical torque range. */
    public static final class Spec {
        public final String part;
        public final double minNm;
        public final double maxNm;

        Spec(String part, double minNm, double maxNm) {
            this.part  = part;
            this.minNm = minNm;
            this.maxNm = maxNm;
        }

        public String rangeLabel() {
            return formatRange(minNm, maxNm);
        }
    }

    private static final List<Spec> SPECS;

    static {
        List<Spec> s = new ArrayList<>();
        s.add(new Spec("Stuurpen stuurklem", 4, 6));
        s.add(new Spec("Stuurpen balhoofdklem", 5, 6));
        s.add(new Spec("Zadelpenklem (carbon)", 4, 6));
        s.add(new Spec("Zadelpenklem (aluminium)", 5, 7));
        s.add(new Spec("Zadelklem (zadelpenkop)", 8, 12));
        s.add(new Spec("Bidonhouder", 2, 3));
        s.add(new Spec("Remklauw bevestiging", 6, 8));
        s.add(new Spec("Crankbouten (Shimano Hollowtech II)", 12, 14));
        s.add(new Spec("Cassette-lockring", 40, 40));
        s.add(new Spec("Trapas BSA", 35, 50));
        s.add(new Spec("Pedalen", 35, 40));
        s.add(new Spec("Achterderailleur (pad)", 8, 10));
        s.add(new Spec("Remschijf 6-bouts", 2, 4));
        s.add(new Spec("Remschijf Centerlock-lockring", 40, 40));
        SPECS = Collections.unmodifiableList(s);
    }

    private TorqueReference() {}

    public static List<Spec> all() {
        return SPECS;
    }

    /** "5", "2,5" — one decimal at most, Dutch decimal comma, no unit. */
    public static String formatNm(double nm) {
        long tenths = Math.round(nm * 10.0);
        long whole = tenths / 10;
        long frac = Math.abs(tenths % 10);
        return frac == 0 ? String.valueOf(whole) : whole + "," + frac;
    }

    /** "4–6 Nm", or "40 Nm" when both ends are equal. */
    public static String formatRange(double minNm, double maxNm) {
        String min = formatNm(minNm);
        String max = formatNm(maxNm);
        return min.equals(max) ? min + " Nm" : min + "–" + max + " Nm";
    }

    /**
     * Parses rider input like "5", "5,5", "5.5" or "5 Nm". Returns the value rounded to 0.1,
     * or {@link Double#NaN} when it is unparsable, not positive after rounding, or above
     * {@link #MAX_NM}.
     */
    public static double parseNm(String text) {
        if (text == null) return Double.NaN;
        String t = text.trim().toLowerCase(Locale.ROOT);
        if (t.endsWith("nm")) t = t.substring(0, t.length() - 2).trim();
        t = t.replace(',', '.');
        if (t.isEmpty()) return Double.NaN;
        double v;
        try {
            v = Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
        if (Double.isNaN(v) || Double.isInfinite(v)) return Double.NaN;
        double rounded = Math.round(v * 10.0) / 10.0;
        if (rounded <= 0 || rounded > MAX_NM) return Double.NaN;
        return rounded;
    }

    /** New list: nulls dropped, values with a bike label first (A–Z), then by part (A–Z). */
    public static List<TorqueValue> sorted(List<TorqueValue> values) {
        List<TorqueValue> out = new ArrayList<>();
        if (values == null) return out;
        for (TorqueValue v : values) {
            if (v != null) out.add(v);
        }
        Collections.sort(out, (a, b) -> {
            String ba = clean(a.bike);
            String bb = clean(b.bike);
            if (ba == null && bb != null) return 1;
            if (ba != null && bb == null) return -1;
            if (ba != null) {
                int c = ba.compareToIgnoreCase(bb);
                if (c != 0) return c;
            }
            return nonNull(a.part).compareToIgnoreCase(nonNull(b.part));
        });
        return out;
    }

    /** "Racefiets · Stuurpen stuurklem", or only the part when there is no bike label. */
    public static String label(TorqueValue v) {
        String bike = clean(v.bike);
        String part = nonNull(v.part);
        return bike == null ? part : bike + " · " + part;
    }

    /** Distinct bike labels for auto-complete: trimmed, non-blank, case-insensitive dedupe. */
    public static List<String> bikeLabels(List<TorqueValue> values) {
        TreeMap<String, String> byKey = new TreeMap<>();
        if (values != null) {
            for (TorqueValue v : values) {
                String bike = v != null ? clean(v.bike) : null;
                if (bike == null) continue;
                String key = bike.toLowerCase(Locale.ROOT);
                if (!byKey.containsKey(key)) byKey.put(key, bike);
            }
        }
        return new ArrayList<>(byKey.values());
    }

    private static String clean(String s) {
        return s != null && !s.trim().isEmpty() ? s.trim() : null;
    }

    private static String nonNull(String s) {
        return s != null ? s.trim() : "";
    }
}
