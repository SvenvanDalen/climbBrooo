package nl.paree.climbpro.domain.ride;

import nl.paree.climbpro.data.ride.StoredRide;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.TreeSet;
import java.util.function.ToDoubleFunction;

/**
 * Personal records outside climbing (issue #156): longest ride, highest average speed, most
 * elevation in one ride, longest moving time and the longest streak of consecutive riding days,
 * computed from the ride archive (issue #160).
 *
 * <p>Junk-record guards:
 * <ul>
 *   <li><b>Highest average speed</b> only counts rides of at least
 *       {@link #SPEED_MIN_DISTANCE_M} (a 2 km sprint to the bakery is not a speed record) and
 *       never {@code VirtualRide}s — indoor trainer "speed" is a simulation, not road speed —
 *       nor e-bike rides ({@code EBikeRide}, {@code EMountainBikeRide}), whose assisted speed
 *       would beat every unassisted ride. These rides do count for the other records and the
 *       streak: they are real rides.</li>
 *   <li>Rides with a zero/negative value never hold that record (no "0 km" longest ride).</li>
 *   <li><b>Streak</b> skips undated rides ({@code startEpochSec <= 0}). Days are local calendar
 *       days in the supplied zone; several rides on one day count as one day.</li>
 * </ul>
 *
 * <p>Ties: the earliest ride keeps the record (a later equal effort does not "break" it);
 * undated rides lose ties against dated ones, then the lowest activity id wins. For streaks of
 * equal length, the earliest streak is reported.
 *
 * <p>Pure and static, no Android dependency; the zone is explicit so tests are deterministic.
 */
public final class RideRecordsCalculator {

    private RideRecordsCalculator() {}

    /** Minimum ride distance for the highest-average-speed record. */
    public static final double SPEED_MIN_DISTANCE_M = 20_000;

    static final String VIRTUAL_RIDE_TYPE = "VirtualRide";

    /** Streak of consecutive local calendar days with at least one ride. */
    public static final class Streak {
        public final int days;
        public final LocalDate firstDay;
        public final LocalDate lastDay;

        Streak(int days, LocalDate firstDay, LocalDate lastDay) {
            this.days = days;
            this.firstDay = firstDay;
            this.lastDay = lastDay;
        }
    }

    /** All records; each field is null when no ride qualifies. */
    public static final class Records {
        public final StoredRide longestDistance;
        public final StoredRide fastestAvgSpeed;
        public final StoredRide mostElevation;
        public final StoredRide longestMovingTime;
        public final Streak longestStreak;

        Records(StoredRide longestDistance, StoredRide fastestAvgSpeed, StoredRide mostElevation,
                StoredRide longestMovingTime, Streak longestStreak) {
            this.longestDistance = longestDistance;
            this.fastestAvgSpeed = fastestAvgSpeed;
            this.mostElevation = mostElevation;
            this.longestMovingTime = longestMovingTime;
            this.longestStreak = longestStreak;
        }

        public boolean isEmpty() {
            return longestDistance == null && fastestAvgSpeed == null && mostElevation == null
                    && longestMovingTime == null && longestStreak == null;
        }
    }

    public static Records compute(List<StoredRide> rides, ZoneId zone) {
        if (rides == null || rides.isEmpty()) return new Records(null, null, null, null, null);
        return new Records(
                best(rides, r -> r.distanceM, false),
                best(rides, r -> r.avgSpeedMps, true),
                best(rides, r -> r.elevationGainM, false),
                best(rides, r -> r.movingTimeSec, false),
                longestStreak(rides, zone));
    }

    static boolean qualifiesForSpeed(StoredRide r) {
        return r.distanceM >= SPEED_MIN_DISTANCE_M && !VIRTUAL_RIDE_TYPE.equals(r.type)
                && !isEBike(r.type);
    }

    /** Strava's assisted types: "EBikeRide", "EMountainBikeRide". */
    static boolean isEBike(String type) {
        return type != null && (type.startsWith("EBike") || type.startsWith("EMountainBike"));
    }

    private static StoredRide best(List<StoredRide> rides, ToDoubleFunction<StoredRide> value,
                                   boolean speedRecord) {
        StoredRide best = null;
        double bestValue = 0;
        for (StoredRide r : rides) {
            if (r == null) continue;
            if (speedRecord && !qualifiesForSpeed(r)) continue;
            double v = value.applyAsDouble(r);
            if (!(v > 0)) continue; // also rejects NaN
            if (best == null || v > bestValue || (v == bestValue && isEarlier(r, best))) {
                best = r;
                bestValue = v;
            }
        }
        return best;
    }

    /** Tie-break order: dated before undated, earlier start first, then lowest activity id. */
    private static boolean isEarlier(StoredRide a, StoredRide b) {
        long ta = a.startEpochSec > 0 ? a.startEpochSec : Long.MAX_VALUE;
        long tb = b.startEpochSec > 0 ? b.startEpochSec : Long.MAX_VALUE;
        if (ta != tb) return ta < tb;
        return a.activityId < b.activityId;
    }

    private static Streak longestStreak(List<StoredRide> rides, ZoneId zone) {
        TreeSet<LocalDate> days = new TreeSet<>();
        for (StoredRide r : rides) {
            if (r == null || r.startEpochSec <= 0) continue;
            days.add(Instant.ofEpochSecond(r.startEpochSec).atZone(zone).toLocalDate());
        }
        if (days.isEmpty()) return null;

        Streak best = null;
        LocalDate runStart = null;
        LocalDate prev = null;
        for (LocalDate d : days) {
            if (prev == null || !d.equals(prev.plusDays(1))) runStart = d;
            int len = (int) (d.toEpochDay() - runStart.toEpochDay()) + 1;
            // Strictly longer only: an equally long later streak does not replace the earlier one.
            if (best == null || len > best.days) best = new Streak(len, runStart, d);
            prev = d;
        }
        return best;
    }
}
