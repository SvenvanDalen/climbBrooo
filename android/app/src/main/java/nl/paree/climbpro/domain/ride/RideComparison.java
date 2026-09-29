package nl.paree.climbpro.domain.ride;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.domain.route.CumulativeDistance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Ride comparer (issue #199): two rides of the same route side by side, per kilometre —
 * moving time, speed, average heart rate and the running time difference. Rides are aligned
 * on distance, so both rows of a kilometre cover the same stretch of road. Pure; phone-only.
 */
public final class RideComparison {

    /** A sample interval slower than this counts as standing still (lights, café stop). */
    static final double MOVING_MIN_MPS = 0.5;
    /** Candidate rides: distance within this fraction of the base ride. */
    static final double SAME_ROUTE_DISTANCE_TOLERANCE = 0.10;
    /** Candidate rides: start and end within this many metres of the base ride's. */
    static final double SAME_ROUTE_ENDPOINT_M = 500;
    /** A trailing partial kilometre shorter than this is dropped as noise. */
    static final double MIN_LAST_KM_M = 100;

    private RideComparison() {}

    /** One kilometre (or the trailing part of one) of the comparison. */
    public static final class Km {
        /** 1-based kilometre number. */
        public final int km;
        public final double lengthM;
        public final int secA;
        public final int secB;
        /** km/h over this kilometre, 0 when the ride did not move. */
        public final double speedA;
        public final double speedB;
        /** Average bpm, NaN when the ride has no heart rate here. */
        public final double hrA;
        public final double hrB;
        /** Running moving-time difference at the end of this kilometre: B minus A (s). */
        public final int cumulativeDeltaSec;

        Km(int km, double lengthM, int secA, int secB, double hrA, double hrB,
           int cumulativeDeltaSec) {
            this.km = km;
            this.lengthM = lengthM;
            this.secA = secA;
            this.secB = secB;
            this.speedA = speedKmh(lengthM, secA);
            this.speedB = speedKmh(lengthM, secB);
            this.hrA = hrA;
            this.hrB = hrB;
            this.cumulativeDeltaSec = cumulativeDeltaSec;
        }
    }

    /**
     * Per-kilometre comparison over the distance both rides cover. Empty when either ride
     * has no usable time/distance stream.
     */
    public static List<Km> compare(RideStreams a, RideStreams b) {
        if (a == null || b == null || !a.isUsable() || !b.isUsable()) {
            return Collections.emptyList();
        }
        double[] movingA = movingTime(a);
        double[] movingB = movingTime(b);
        double total = Math.min(a.distance[a.distance.length - 1],
                b.distance[b.distance.length - 1]);
        List<Km> out = new ArrayList<>();
        double prevA = 0;
        double prevB = 0;
        for (int k = 0; k * 1000.0 < total; k++) {
            double from = k * 1000.0;
            double to = Math.min(total, from + 1000.0);
            if (to - from < MIN_LAST_KM_M && k > 0) break;
            double tA = at(a.distance, movingA, to);
            double tB = at(b.distance, movingB, to);
            int secA = (int) Math.round(tA - prevA);
            int secB = (int) Math.round(tB - prevB);
            out.add(new Km(k + 1, to - from, secA, secB,
                    meanHr(a, from, to), meanHr(b, from, to),
                    (int) Math.round(tB - tA)));
            prevA = tA;
            prevB = tB;
        }
        return out;
    }

    /**
     * Other rides that look like the same route as {@code base}: similar distance and, when
     * both have coordinates, start and end close together. Newest first.
     */
    public static List<StoredRide> sameRouteCandidates(StoredRide base, List<StoredRide> all) {
        List<StoredRide> out = new ArrayList<>();
        if (base == null || all == null || base.distanceM <= 0) return out;
        for (StoredRide r : all) {
            if (r == null || r.activityId == base.activityId) continue;
            if (Math.abs(r.distanceM - base.distanceM)
                    > base.distanceM * SAME_ROUTE_DISTANCE_TOLERANCE) continue;
            if (!near(base.startLat, base.startLon, r.startLat, r.startLon)) continue;
            if (!near(base.endLat, base.endLon, r.endLat, r.endLon)) continue;
            out.add(r);
        }
        out.sort((x, y) -> Long.compare(y.startEpochSec, x.startEpochSec));
        return out;
    }

    /** Unknown coordinates on either side don't rule a ride out; distance still has to match. */
    private static boolean near(Double lat1, Double lon1, Double lat2, Double lon2) {
        if (lat1 == null || lon1 == null || lat2 == null || lon2 == null) return true;
        return CumulativeDistance.haversine(lat1, lon1, lat2, lon2) <= SAME_ROUTE_ENDPOINT_M;
    }

    /** Cumulative moving time (s) per sample; intervals slower than walking pace don't count. */
    static double[] movingTime(RideStreams s) {
        double[] out = new double[s.time.length];
        for (int i = 1; i < out.length; i++) {
            int dt = s.time[i] - s.time[i - 1];
            double dd = s.distance[i] - s.distance[i - 1];
            boolean moving = dt > 0 && dd / dt >= MOVING_MIN_MPS;
            out[i] = out[i - 1] + (moving ? dt : 0);
        }
        return out;
    }

    /** Linear interpolation of {@code values} at distance {@code d} (distance is non-decreasing). */
    static double at(double[] distance, double[] values, double d) {
        int n = distance.length;
        if (d <= distance[0]) return values[0];
        if (d >= distance[n - 1]) return values[n - 1];
        int lo = 0;
        int hi = n - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (distance[mid] < d) lo = mid; else hi = mid;
        }
        double span = distance[hi] - distance[lo];
        if (span <= 0) return values[hi];
        return values[lo] + (values[hi] - values[lo]) * (d - distance[lo]) / span;
    }

    private static double meanHr(RideStreams s, double from, double to) {
        if (s.heartrate == null) return Double.NaN;
        double sum = 0;
        int n = 0;
        for (int i = 0; i < s.distance.length; i++) {
            if (s.distance[i] < from || s.distance[i] > to) continue;
            double hr = s.heartrate[i];
            if (Double.isNaN(hr) || hr <= 0) continue;
            sum += hr;
            n++;
        }
        return n > 0 ? sum / n : Double.NaN;
    }

    private static double speedKmh(double lengthM, int sec) {
        return sec > 0 ? lengthM / sec * 3.6 : 0;
    }
}
