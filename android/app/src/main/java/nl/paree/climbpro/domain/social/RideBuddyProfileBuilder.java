package nl.paree.climbpro.domain.social;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.route.StoredClimbAttempt;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Derives your own {@link RideBuddyProfile} from the ride archive and climb attempts (issue
 * #242). Only outdoor bike rides of the last {@link #WINDOW_SEC} count (virtual rides, rides
 * under {@link #MIN_RIDE_M} and rides without moving time are ignored). Medians rather than
 * means, so one epic day doesn't define you. Pure.
 */
public final class RideBuddyProfileBuilder {

    public static final long WINDOW_SEC = 180L * 24 * 3_600;
    public static final int MIN_RIDES = 3;
    static final float MIN_RIDE_M = 5_000f;
    /** Rides climbing less than this many metres per km count as "flat" for the pace. */
    static final float FLAT_M_PER_KM = 8f;
    /** A ride type / weekday / daypart must cover this share of rides to be listed. */
    static final double TYPE_SHARE = 0.25;
    static final double DAY_SHARE = 0.15;
    static final double PART_SHARE = 0.20;
    /** The home cell needs at least this many ride starts, so one trip away can't be it. */
    static final int MIN_AREA_STARTS = 2;
    /** Climb attempts count for VAM only with enough gain and time to be meaningful. */
    static final int MIN_VAM_GAIN_M = 30;
    static final int MIN_VAM_SEC = 60;
    static final int MIN_VAM = 100;
    static final int MAX_VAM = 2_500;

    private RideBuddyProfileBuilder() {}

    /**
     * @param climbGainById elevation gain per climb id, for the VAM of your attempts
     * @return the full profile (all derivable fields); {@code rideCount < MIN_RIDES} means
     *         there is too little to share and every other field stays empty
     */
    public static RideBuddyProfile build(List<StoredRide> rides, List<StoredClimbAttempt> attempts,
                                         Map<String, Integer> climbGainById,
                                         long nowEpochSec, ZoneId zone) {
        RideBuddyProfile p = new RideBuddyProfile();
        p.createdEpochSec = nowEpochSec;
        long from = nowEpochSec - WINDOW_SEC;

        List<StoredRide> used = usedRides(rides, from, nowEpochSec);
        p.rideCount = used.size();
        if (used.size() < MIN_RIDES) return p;

        Habits h = new Habits();
        for (StoredRide r : used) h.add(r, zone);
        // Pace: median speed on flat-ish rides; all rides when there are too few flat ones.
        double pace = median(h.flat.size() >= 2 ? h.flat : h.all);
        p.flatSpeedDkmh = (int) Math.round(pace * 10);
        p.typicalDistanceKm = Math.max(1, (int) Math.round(median(h.distances)));

        int n = used.size();
        int types = bits(h.typeCounts, Math.ceil(TYPE_SHARE * n));
        if (types == 0) types = 1 << argMax(h.typeCounts);
        p.rideTypes = types;

        p.weekdays |= bits(h.dayCounts, Math.max(1, (int) Math.ceil(DAY_SHARE * n)));
        p.dayparts |= bits(h.partCounts, Math.max(1, (int) Math.ceil(PART_SHARE * n)));

        String bestCell = mostCommonCell(h.cellCounts);
        if (bestCell != null && h.cellCounts.get(bestCell) >= MIN_AREA_STARTS) {
            double[] c = h.cellCentres.get(bestCell);
            p.areaLat = c[0];
            p.areaLon = c[1];
        }

        p.vamMph = vam(attempts, climbGainById, from);
        return p;
    }

    /** Usable rides that started in the profile window (a day of clock slack into the future). */
    private static List<StoredRide> usedRides(List<StoredRide> rides, long from, long nowEpochSec) {
        List<StoredRide> used = new ArrayList<>();
        if (rides == null) return used;
        for (StoredRide r : rides) {
            if (isUsable(r) && r.startEpochSec >= from && r.startEpochSec <= nowEpochSec + 86_400) {
                used.add(r);
            }
        }
        return used;
    }

    /** Bit i set for every count that reaches {@code min}. */
    private static int bits(int[] counts, double min) {
        int mask = 0;
        for (int i = 0; i < counts.length; i++) if (counts[i] >= min) mask |= 1 << i;
        return mask;
    }

    /** Cell with the most ride starts; ties broken by key so HashMap order doesn't matter. */
    private static String mostCommonCell(Map<String, Integer> cellCounts) {
        String bestCell = null;
        int best = 0;
        for (Map.Entry<String, Integer> e : cellCounts.entrySet()) {
            if (e.getValue() > best || (e.getValue() == best && bestCell != null
                    && e.getKey().compareTo(bestCell) < 0)) {
                best = e.getValue();
                bestCell = e.getKey();
            }
        }
        return bestCell;
    }

    /** Speeds, distances, ride types, start days/dayparts and start cells of the used rides. */
    private static final class Habits {
        final List<Double> flat = new ArrayList<>();
        final List<Double> all = new ArrayList<>();
        final List<Double> distances = new ArrayList<>();
        final int[] typeCounts = new int[3];
        final int[] dayCounts = new int[7];
        final int[] partCounts = new int[3];
        final Map<String, Integer> cellCounts = new HashMap<>();
        final Map<String, double[]> cellCentres = new HashMap<>();

        void add(StoredRide r, ZoneId zone) {
            double kmh = speedKmh(r);
            all.add(kmh);
            if (r.elevationGainM / (r.distanceM / 1000f) < FLAT_M_PER_KM) flat.add(kmh);
            distances.add(r.distanceM / 1000.0);
            typeCounts[typeIndex(r)]++;
            if (r.startEpochSec > 0) addStartTime(Instant.ofEpochSecond(r.startEpochSec).atZone(zone));
            if (r.startLat != null && r.startLon != null
                    && !(r.startLat == 0 && r.startLon == 0)) {
                String key = RideBuddyProfile.cellKey(r.startLat, r.startLon);
                cellCounts.merge(key, 1, Integer::sum);
                cellCentres.put(key, RideBuddyProfile.snapToCell(r.startLat, r.startLon));
            }
        }

        private void addStartTime(ZonedDateTime t) {
            dayCounts[t.getDayOfWeek().getValue() - 1]++;
            int part = daypartIndex(t.getHour());
            if (part >= 0) partCounts[part]++;
        }
    }

    static boolean isUsable(StoredRide r) {
        if (r == null || r.distanceM < MIN_RIDE_M || r.movingTimeSec <= 0) return false;
        String kind = r.sportType != null ? r.sportType : r.type;
        if (kind == null) return false;
        if (kind.startsWith("Virtual")) return false;
        return kind.endsWith("Ride") || kind.equals("Handcycle") || kind.equals("Velomobile");
    }

    static int typeIndex(StoredRide r) {
        String kind = r.sportType != null ? r.sportType : r.type;
        if ("GravelRide".equals(kind)) return 1;
        if ("MountainBikeRide".equals(kind) || "EMountainBikeRide".equals(kind)) return 2;
        return 0;
    }

    /** 0 = ochtend (5–12 u), 1 = middag (12–17 u), 2 = avond (17–23 u), −1 = night. */
    static int daypartIndex(int hour) {
        if (hour >= 5 && hour < 12) return 0;
        if (hour >= 12 && hour < 17) return 1;
        if (hour >= 17 && hour < 23) return 2;
        return -1;
    }

    private static double speedKmh(StoredRide r) {
        if (r.avgSpeedMps > 0) return r.avgSpeedMps * 3.6;
        return r.distanceM / r.movingTimeSec * 3.6;
    }

    private static int vam(List<StoredClimbAttempt> attempts, Map<String, Integer> gains, long from) {
        if (attempts == null || gains == null) return 0;
        List<Double> vams = new ArrayList<>();
        for (StoredClimbAttempt a : attempts) {
            if (a == null || a.climbId == null || a.dateEpochSec < from) continue;
            Integer gain = gains.get(a.climbId);
            if (gain == null || gain < MIN_VAM_GAIN_M || a.elapsedSec < MIN_VAM_SEC) continue;
            double v = gain * 3600.0 / a.elapsedSec;
            if (v >= MIN_VAM && v <= MAX_VAM) vams.add(v);
        }
        return vams.isEmpty() ? 0 : (int) Math.round(median(vams));
    }

    private static int argMax(int[] a) {
        int best = 0;
        for (int i = 1; i < a.length; i++) if (a[i] > a[best]) best = i;
        return best;
    }

    static double median(List<Double> values) {
        List<Double> s = new ArrayList<>(values);
        Collections.sort(s);
        int n = s.size();
        if (n == 0) return 0;
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
    }
}
