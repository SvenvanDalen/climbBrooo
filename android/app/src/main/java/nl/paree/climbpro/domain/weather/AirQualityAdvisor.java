package nl.paree.climbpro.domain.weather;

import nl.paree.climbpro.domain.weather.AirQualityForecast.Pollen;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Air-quality and pollen warning for a planned ride (issue #197): the worst hour of the ride
 * window for the European AQI and each pollen type, a level per item and short advice for
 * riders with asthma or hay fever. Pure.
 */
public final class AirQualityAdvisor {

    public enum Level { GOOD, MODERATE, HIGH }

    /** European AQI from which air quality is "matig" (moderate) and "slecht" (poor). */
    static final double AQI_MODERATE_FROM = 40;
    static final double AQI_HIGH_FROM = 60;

    private AirQualityAdvisor() {}

    /** One pollen type that is present during the ride. */
    public static final class PollenPeak {
        public final Pollen type;
        public final double grains;
        public final Level level;

        PollenPeak(Pollen type, double grains, Level level) {
            this.type = type;
            this.grains = grains;
            this.level = level;
        }
    }

    public static final class Advice {
        /** False when the forecast has no hour in the ride window. */
        public final boolean covered;
        /** Worst hourly European AQI in the window; NaN when unknown. */
        public final double maxAqi;
        public final Instant maxAqiAt;
        public final double maxPm25;
        public final double maxPm10;
        public final Level airLevel;
        /** Pollen types at moderate or high level, worst first. */
        public final List<PollenPeak> pollen;
        /** True when the service had pollen data at all (Europe, in season). */
        public final boolean pollenKnown;

        Advice(boolean covered, double maxAqi, Instant maxAqiAt, double maxPm25, double maxPm10,
               List<PollenPeak> pollen, boolean pollenKnown) {
            this.covered = covered;
            this.maxAqi = maxAqi;
            this.maxAqiAt = maxAqiAt;
            this.maxPm25 = maxPm25;
            this.maxPm10 = maxPm10;
            this.airLevel = aqiLevel(maxAqi);
            this.pollen = pollen;
            this.pollenKnown = pollenKnown;
        }

        /** Worst of air and pollen. */
        public Level overall() {
            Level worst = airLevel;
            for (PollenPeak p : pollen) if (p.level.ordinal() > worst.ordinal()) worst = p.level;
            return worst;
        }
    }

    /** Hours overlapping {@code [start, start + durationSec)}. */
    public static Advice advise(AirQualityForecast f, Instant start, long durationSec) {
        Instant end = start.plusSeconds(durationSec);
        boolean covered = false;
        double maxAqi = Double.NaN;
        Instant maxAqiAt = null;
        double maxPm25 = Double.NaN;
        double maxPm10 = Double.NaN;
        double[] maxPollen = new double[Pollen.values().length];
        java.util.Arrays.fill(maxPollen, Double.NaN);
        for (int i = 0; i < f.times.length; i++) {
            Instant h = f.times[i];
            if (!h.plusSeconds(3600).isAfter(start) || !h.isBefore(end)) continue;
            covered = true;
            if (!Double.isNaN(f.europeanAqi[i])
                    && (Double.isNaN(maxAqi) || f.europeanAqi[i] > maxAqi)) {
                maxAqi = f.europeanAqi[i];
                maxAqiAt = h;
            }
            maxPm25 = max(maxPm25, f.pm25[i]);
            maxPm10 = max(maxPm10, f.pm10[i]);
            for (Pollen p : Pollen.values()) {
                maxPollen[p.ordinal()] = max(maxPollen[p.ordinal()], f.pollen[p.ordinal()][i]);
            }
        }
        boolean pollenKnown = false;
        List<PollenPeak> peaks = new ArrayList<>();
        for (Pollen p : Pollen.values()) {
            double g = maxPollen[p.ordinal()];
            if (Double.isNaN(g)) continue;
            pollenKnown = true;
            Level level = g >= p.highFrom ? Level.HIGH
                    : g >= p.moderateFrom ? Level.MODERATE : Level.GOOD;
            if (level != Level.GOOD) peaks.add(new PollenPeak(p, g, level));
        }
        Collections.sort(peaks, (a, b) -> a.level != b.level
                ? b.level.ordinal() - a.level.ordinal()
                : Double.compare(b.grains / b.type.highFrom, a.grains / a.type.highFrom));
        return new Advice(covered, maxAqi, maxAqiAt, maxPm25, maxPm10, peaks, pollenKnown);
    }

    static Level aqiLevel(double aqi) {
        if (Double.isNaN(aqi) || aqi < AQI_MODERATE_FROM) return Level.GOOD;
        return aqi < AQI_HIGH_FROM ? Level.MODERATE : Level.HIGH;
    }

    public static String headline(Advice a) {
        if (!a.covered) return "Geen luchtkwaliteitsverwachting voor dit tijdstip";
        switch (a.overall()) {
            case HIGH: return "Let op: " + problem(a) + " tijdens je rit";
            case MODERATE: return "Matig: " + problem(a) + " tijdens je rit";
            default: return "Lucht en pollen zijn prima voor je rit";
        }
    }

    public static String detail(Advice a, ZoneId zone) {
        if (!a.covered) return "";
        StringBuilder sb = new StringBuilder();
        if (!Double.isNaN(a.maxAqi)) {
            sb.append(String.format(Locale.GERMANY, "Luchtkwaliteit: %s (Europese AQI tot %.0f",
                    aqiLabel(a.maxAqi), a.maxAqi));
            if (a.maxAqiAt != null) {
                sb.append(" om ").append(DateTimeFormatter.ofPattern("HH:mm")
                        .format(a.maxAqiAt.atZone(zone)));
            }
            sb.append(')');
            if (!Double.isNaN(a.maxPm25) || !Double.isNaN(a.maxPm10)) {
                sb.append(String.format(Locale.GERMANY, "\nFijnstof: PM2,5 tot %s, PM10 tot %s µg/m³",
                        fmt(a.maxPm25), fmt(a.maxPm10)));
            }
        }
        if (!a.pollenKnown) {
            sb.append("\nPollen: geen verwachting voor deze plek of dit seizoen");
        } else if (a.pollen.isEmpty()) {
            sb.append("\nPollen: weinig");
        } else {
            for (PollenPeak p : a.pollen) {
                sb.append(String.format(Locale.GERMANY, "\nPollen %s: %s (tot %.0f korrels/m³)",
                        p.type.label, p.level == Level.HIGH ? "hoog" : "matig", p.grains));
            }
        }
        String tips = tips(a);
        if (!tips.isEmpty()) sb.append("\n\n").append(tips);
        return sb.toString();
    }

    static String tips(Advice a) {
        List<String> out = new ArrayList<>();
        if (a.airLevel == Level.HIGH) {
            out.add("Rij rustiger of korter, mijd drukke wegen en neem je inhaler mee als je astma hebt.");
        } else if (a.airLevel == Level.MODERATE) {
            out.add("Gevoelig voor fijnstof? Houd de intensiteit wat lager.");
        }
        Level pollen = Level.GOOD;
        for (PollenPeak p : a.pollen) if (p.level.ordinal() > pollen.ordinal()) pollen = p.level;
        if (pollen == Level.HIGH) {
            out.add("Hooikoorts? Neem vooraf je medicatie, draag een goed sluitende bril en "
                    + "spoel na de rit je gezicht en douche.");
        } else if (pollen == Level.MODERATE) {
            out.add("Hooikoorts? Een bril helpt tegen pollen in je ogen.");
        }
        return String.join("\n", out);
    }

    private static String problem(Advice a) {
        List<String> parts = new ArrayList<>();
        if (a.airLevel == a.overall()) parts.add(aqiLabel(a.maxAqi) + " lucht");
        for (PollenPeak p : a.pollen) {
            if (p.level == a.overall()) parts.add(p.type.label + "pollen");
        }
        return String.join(", ", parts);
    }

    static String aqiLabel(double aqi) {
        if (aqi < 20) return "goede";
        if (aqi < 40) return "redelijke";
        if (aqi < 60) return "matige";
        if (aqi < 80) return "slechte";
        if (aqi < 100) return "zeer slechte";
        return "extreem slechte";
    }

    private static double max(double acc, double v) {
        if (Double.isNaN(v)) return acc;
        return Double.isNaN(acc) || v > acc ? v : acc;
    }

    private static String fmt(double v) {
        return Double.isNaN(v) ? "?" : String.format(Locale.GERMANY, "%.0f", v);
    }
}
