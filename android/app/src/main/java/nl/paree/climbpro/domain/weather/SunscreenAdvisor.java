package nl.paree.climbpro.domain.weather;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Sunscreen advice for a planned ride (issue #229) from the hourly UV forecast. Uses the WHO
 * UV-index bands: below 3 no protection is needed; from 3 apply sunscreen before leaving and
 * re-apply every {@link #REAPPLY_EVERY_SEC} (sweat and wind wear it off) while the UV index at
 * that moment is still 3 or more.
 *
 * <p>Pure: the caller passes the forecast, start and duration.
 */
public final class SunscreenAdvisor {

    private SunscreenAdvisor() {}

    public static final double PROTECT_FROM_UV = 3.0;
    public static final long REAPPLY_EVERY_SEC = 2L * 60 * 60;

    public enum Level {
        LOW("laag"), MODERATE("matig"), HIGH("hoog"), VERY_HIGH("zeer hoog"), EXTREME("extreem");

        public final String label;

        Level(String label) {
            this.label = label;
        }

        static Level of(double uv) {
            if (uv >= 11) return EXTREME;
            if (uv >= 8) return VERY_HIGH;
            if (uv >= 6) return HIGH;
            if (uv >= PROTECT_FROM_UV) return MODERATE;
            return LOW;
        }
    }

    public static final class Advice {
        /** Highest UV index during the ride, or NaN when the forecast has no UV for it. */
        public final double peakUv;
        public final Level level;
        /** Moments during the ride to re-apply (UV still at least 3 then). */
        public final List<Instant> reapplyAt;
        /** Hours of the ride window with UV at least 3. */
        public final int protectHours;

        Advice(double peakUv, Level level, List<Instant> reapplyAt, int protectHours) {
            this.peakUv = peakUv;
            this.level = level;
            this.reapplyAt = reapplyAt;
            this.protectHours = protectHours;
        }

        public boolean known() {
            return !Double.isNaN(peakUv);
        }

        public boolean needed() {
            return known() && level != Level.LOW;
        }
    }

    public static Advice advise(HourlyForecast f, Instant start, long durationSec) {
        Instant end = start.plusSeconds(Math.max(0, durationSec));
        double peak = Double.NaN;
        int protectHours = 0;
        for (int i = 0; i < f.times.length; i++) {
            Instant hourStart = f.times[i];
            Instant hourEnd = hourStart.plusSeconds(3600);
            if (!hourEnd.isAfter(start) || !hourStart.isBefore(end)) continue;
            double uv = f.uvIndex[i];
            if (Double.isNaN(uv)) continue;
            if (Double.isNaN(peak) || uv > peak) peak = uv;
            if (uv >= PROTECT_FROM_UV) protectHours++;
        }
        Level level = Double.isNaN(peak) ? Level.LOW : Level.of(peak);

        List<Instant> reapply = new ArrayList<>();
        if (!Double.isNaN(peak) && level != Level.LOW) {
            for (Instant t = start.plusSeconds(REAPPLY_EVERY_SEC); t.isBefore(end);
                 t = t.plusSeconds(REAPPLY_EVERY_SEC)) {
                int i = f.indexAt(t);
                if (i >= 0 && !Double.isNaN(f.uvIndex[i]) && f.uvIndex[i] >= PROTECT_FROM_UV) {
                    reapply.add(t);
                }
            }
        }
        return new Advice(peak, level, reapply, protectHours);
    }

    /** Lead time for the "smeer nu in" reminder before a planned start. */
    public static final long BEFORE_START_SEC = 15L * 60;

    /** One scheduled phone notification. */
    public static final class Reminder {
        public final Instant at;
        public final String text;

        Reminder(Instant at, String text) {
            this.at = at;
            this.text = text;
        }
    }

    /**
     * Notifications to schedule: one {@link #BEFORE_START_SEC} before a start that is still
     * far enough ahead, plus one per re-apply moment. Past moments are dropped.
     */
    public static List<Reminder> reminders(Advice a, Instant start, Instant now) {
        List<Reminder> out = new ArrayList<>();
        if (!a.needed()) return out;
        Instant before = start.minusSeconds(BEFORE_START_SEC);
        if (before.isAfter(now)) {
            out.add(new Reminder(before, "Smeer " + spf(a.level) + " in voor je rit (UV-index tot "
                    + String.format(Locale.GERMANY, "%.0f", a.peakUv) + ")."));
        }
        for (Instant t : a.reapplyAt) {
            if (t.isAfter(now)) {
                out.add(new Reminder(t, "Tijd om opnieuw zonnebrand te smeren."));
            }
        }
        return out;
    }

    /** SPF advice per level; null when no protection is needed. */
    static String spf(Level level) {
        switch (level) {
            case MODERATE: return "factor 30";
            case HIGH: return "factor 50";
            case VERY_HIGH:
            case EXTREME: return "factor 50+";
            default: return null;
        }
    }

    /** Headline, e.g. "UV-index tot 7 (hoog) — smeer factor 50 vóór vertrek". */
    public static String headline(Advice a) {
        if (!a.known()) return "Geen UV-verwachting beschikbaar voor dit tijdstip.";
        String uv = String.format(Locale.GERMANY, "UV-index tot %.0f (%s)", a.peakUv, a.level.label);
        if (!a.needed()) return uv + " — geen zonnebrand nodig.";
        return uv + " — smeer " + spf(a.level) + " vóór vertrek.";
    }

    /** Detail line about re-applying and extra protection. */
    public static String detail(Advice a, java.time.ZoneId zone) {
        if (!a.needed()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(a.protectHours == 1 ? "1 uur" : a.protectHours + " uur")
          .append(" van je rit is de UV-index 3 of hoger.");
        if (a.reapplyAt.isEmpty()) {
            sb.append(" Eén keer smeren is genoeg.");
        } else {
            sb.append(" Opnieuw smeren om ");
            java.time.format.DateTimeFormatter hm =
                    java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(zone);
            for (int i = 0; i < a.reapplyAt.size(); i++) {
                if (i > 0) sb.append(i == a.reapplyAt.size() - 1 ? " en " : ", ");
                sb.append(hm.format(a.reapplyAt.get(i)));
            }
            sb.append('.');
        }
        sb.append(" Vergeet nek, oren en onderarmen niet");
        if (a.level.ordinal() >= Level.HIGH.ordinal()) sb.append(", en neem lippenbalsem met SPF mee");
        sb.append('.');
        return sb.toString();
    }
}
