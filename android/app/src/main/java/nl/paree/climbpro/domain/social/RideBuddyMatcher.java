package nl.paree.climbpro.domain.social;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Ranks imported rider profiles by how well they'd ride with you (issue #242). Each aspect both
 * profiles share gets a similarity in [0, 1]; the score is their weighted mean, scaled down a bit
 * when few aspects could be compared (a profile that only shares its pace shouldn't top the list
 * on that alone). Aspects either side left out are skipped, never counted as a mismatch. Pure.
 */
public final class RideBuddyMatcher {

    static final double W_PACE = 3;
    static final double W_CLIMB = 2;
    static final double W_DISTANCE = 1.5;
    static final double W_TYPE = 1.5;
    static final double W_SCHEDULE = 1;
    static final double W_AREA = 2;
    static final double W_TOTAL = W_PACE + W_CLIMB + W_DISTANCE + W_TYPE + W_SCHEDULE + W_AREA;

    /** Pace difference (km/h) at which pace similarity reaches 0. */
    static final double PACE_ZERO_KMH = 6;
    /** Pace difference still called "vergelijkbaar". */
    static final double PACE_SIMILAR_KMH = 1.5;
    static final double VAM_ZERO = 400;
    static final double VAM_SIMILAR = 100;
    /** Areas within this distance count as fully "nearby"; similarity is 0 beyond AREA_ZERO_KM. */
    static final double AREA_FULL_KM = 5;
    static final double AREA_ZERO_KM = 60;

    private static final Locale NL = new Locale("nl");

    public static final class Match {
        public final RideBuddyProfile buddy;
        /** 0–100. */
        public final int score;
        /** E.g. "vergelijkbaar tempo, 12 km verderop, rijdt ook gravel". */
        public final String explanation;
        /** Distance between the two areas' cell centres; null when either didn't share one. */
        public final Double distanceKm;

        Match(RideBuddyProfile buddy, int score, String explanation, Double distanceKm) {
            this.buddy = buddy;
            this.score = score;
            this.explanation = explanation;
            this.distanceKm = distanceKm;
        }
    }

    private RideBuddyMatcher() {}

    /** Best match first; ties by name. */
    public static List<Match> rank(RideBuddyProfile me, List<RideBuddyProfile> others) {
        List<Match> out = new ArrayList<>();
        if (others != null) {
            for (RideBuddyProfile o : others) if (o != null) out.add(score(me, o));
        }
        Collections.sort(out, (a, b) -> {
            if (a.score != b.score) return Integer.compare(b.score, a.score);
            return String.valueOf(a.buddy.name).compareToIgnoreCase(String.valueOf(b.buddy.name));
        });
        return out;
    }

    public static Match score(RideBuddyProfile me, RideBuddyProfile other) {
        Scorer s = new Scorer();
        if (me != null) {
            s.pace(me, other);
            s.area(me, other);
            s.rideTypes(me, other);
            s.distance(me, other);
            s.climbing(me, other);
            s.schedule(me, other);
        }
        double sum = s.sum;
        double weight = s.weight;
        List<String> why = s.why;
        Double km = s.km;

        if (weight == 0) {
            return new Match(other, 0, "Te weinig gegevens om te vergelijken", null);
        }
        double coverage = weight / W_TOTAL;
        int score = (int) Math.round(100 * (sum / weight) * (0.6 + 0.4 * coverage));
        return new Match(other, Math.max(0, Math.min(100, score)), String.join(", ", why), km);
    }

    /**
     * Weighted similarity per aspect both profiles share, with a reason line for each. An
     * aspect either profile leaves out adds no weight, so it neither helps nor hurts.
     */
    private static final class Scorer {
        double sum = 0;
        double weight = 0;
        final List<String> why = new ArrayList<>();
        Double km = null;

        private void add(double w, double similarity, String reason) {
            sum += w * similarity;
            weight += w;
            why.add(reason);
        }

        void pace(RideBuddyProfile me, RideBuddyProfile other) {
            if (me.flatSpeedDkmh <= 0 || other.flatSpeedDkmh <= 0) return;
            double d = (other.flatSpeedDkmh - me.flatSpeedDkmh) / 10.0;
            String reason = Math.abs(d) <= PACE_SIMILAR_KMH ? "vergelijkbaar tempo"
                    : String.format(NL, "rijdt %.1f km/u %s", Math.abs(d),
                            d > 0 ? "sneller" : "langzamer");
            add(W_PACE, linear(Math.abs(d), PACE_ZERO_KMH), reason);
        }

        void area(RideBuddyProfile me, RideBuddyProfile other) {
            if (!me.hasArea() || !other.hasArea()) return;
            double dist = haversineKm(me.areaLat, me.areaLon, other.areaLat, other.areaLon);
            km = dist;
            double sim = dist <= AREA_FULL_KM ? 1
                    : linear(dist - AREA_FULL_KM, AREA_ZERO_KM - AREA_FULL_KM);
            add(W_AREA, sim, dist < 2.5 ? "in je buurt"
                    : String.format(NL, "%d km verderop", Math.round(dist)));
        }

        void rideTypes(RideBuddyProfile me, RideBuddyProfile other) {
            if (me.rideTypes == 0 || other.rideTypes == 0) return;
            int common = me.rideTypes & other.rideTypes;
            add(W_TYPE, jaccard(me.rideTypes, other.rideTypes),
                    common != 0 ? "rijdt ook " + RideBuddyCode.typeNames(common)
                            : "rijdt vooral " + RideBuddyCode.typeNames(other.rideTypes));
        }

        void distance(RideBuddyProfile me, RideBuddyProfile other) {
            if (me.typicalDistanceKm <= 0 || other.typicalDistanceKm <= 0) return;
            double ratio = Math.abs(Math.log((double) other.typicalDistanceKm / me.typicalDistanceKm));
            add(W_DISTANCE, linear(ratio, Math.log(2)),
                    ratio <= Math.log(1.25)
                            ? "vergelijkbare ritlengte"
                            : "ritten van ~" + other.typicalDistanceKm + " km");
        }

        void climbing(RideBuddyProfile me, RideBuddyProfile other) {
            if (me.vamMph <= 0 || other.vamMph <= 0) return;
            int d = other.vamMph - me.vamMph;
            String reason;
            if (Math.abs(d) <= VAM_SIMILAR) reason = "klimt even snel";
            else reason = d > 0 ? "klimt sneller" : "klimt rustiger";
            add(W_CLIMB, linear(Math.abs(d), VAM_ZERO), reason);
        }

        void schedule(RideBuddyProfile me, RideBuddyProfile other) {
            boolean days = me.weekdays != 0 && other.weekdays != 0;
            boolean parts = me.dayparts != 0 && other.dayparts != 0;
            if (!days && !parts) return;
            add(W_SCHEDULE, scheduleSimilarity(me, other, days, parts),
                    days ? weekdayReason(me.weekdays & other.weekdays)
                            : daypartReason(me.dayparts & other.dayparts));
        }
    }

    private static double scheduleSimilarity(RideBuddyProfile me, RideBuddyProfile other,
                                             boolean days, boolean parts) {
        if (days && parts) {
            return 0.7 * jaccard(me.weekdays, other.weekdays)
                    + 0.3 * jaccard(me.dayparts, other.dayparts);
        }
        return days ? jaccard(me.weekdays, other.weekdays)
                : jaccard(me.dayparts, other.dayparts);
    }

    private static String weekdayReason(int common) {
        return common != 0 ? "rijdt ook op " + RideBuddyCode.dayNames(common)
                : "rijdt op andere dagen";
    }

    private static String daypartReason(int common) {
        return common != 0 ? "rijdt ook in de " + RideBuddyCode.partNames(common)
                : "rijdt op andere tijden";
    }

    /** 1 at 0, falling linearly to 0 at {@code zero}. */
    private static double linear(double diff, double zero) {
        return Math.max(0, 1 - diff / zero);
    }

    private static double jaccard(int a, int b) {
        int union = Integer.bitCount(a | b);
        return union == 0 ? 0 : (double) Integer.bitCount(a & b) / union;
    }

    static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * 6371.0 * Math.asin(Math.min(1, Math.sqrt(h)));
    }
}
