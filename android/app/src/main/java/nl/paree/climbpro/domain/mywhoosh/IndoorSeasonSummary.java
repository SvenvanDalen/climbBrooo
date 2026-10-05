package nl.paree.climbpro.domain.mywhoosh;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.ride.StoredRideStreamStats;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.domain.training.TrainingLoad;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Indoor season overview (issue #409): per winter season, October up to and including March,
 * the indoor rides, hours, training load (TSS, same model as the load calendar), best 5- and
 * 20-minute power, indoor climbs and virtual hm — newest season first, so the screen compares
 * the first entry with the second. Rides from April to September belong to no season. Pure.
 */
public final class IndoorSeasonSummary {

    private IndoorSeasonSummary() {}

    static final int FIRST_MONTH = 10;
    static final int LAST_MONTH = 3;
    /** Indices into {@code StoredRideStreamStats.powerCurve}. */
    static final int IDX_5_MIN = 2;
    static final int IDX_20_MIN = 3;

    public static final class Season {
        /** Year the season starts in: 2025 = October 2025 to March 2026. */
        public final int startYear;
        public int rides;
        public int movingSec;
        public double tss;
        public int best5MinWatts;
        public int best20MinWatts;
        public int climbs;
        public double elevationM;

        Season(int startYear) {
            this.startYear = startYear;
        }

        /** "2025/26". */
        public String label() {
            return startYear + "/" + String.format(java.util.Locale.ROOT, "%02d", (startYear + 1) % 100);
        }
    }

    /** Season start year of a date, or null for April to September. */
    public static Integer seasonOf(LocalDate date) {
        int m = date.getMonthValue();
        if (m >= FIRST_MONTH) return date.getYear();
        if (m <= LAST_MONTH) return date.getYear() - 1;
        return null;
    }

    public static List<Season> compute(List<StoredRide> rides,
                                       Map<Long, StoredRideStreamStats> stats,
                                       List<StoredClimbAttempt> attempts, int ftpWatts,
                                       ZoneId zone) {
        TreeMap<Integer, Season> seasons = new TreeMap<>();
        Set<Long> indoorIds = IndoorRides.indoorIds(rides);
        if (rides != null) {
            for (StoredRide r : rides) {
                if (!IndoorRides.isIndoor(r) || r.startEpochSec <= 0) continue;
                Integer year = seasonOf(day(r.startEpochSec, zone));
                if (year == null) continue;
                Season s = seasons.computeIfAbsent(year, Season::new);
                s.rides++;
                s.movingSec += Math.max(0, r.movingTimeSec);
                s.tss += TrainingLoad.of(r, ftpWatts).tss;
                s.elevationM += Math.max(0, r.elevationGainM);
                StoredRideStreamStats st = stats != null ? stats.get(r.activityId) : null;
                if (st != null && st.powerCurve != null && st.powerCurve.length > IDX_20_MIN) {
                    s.best5MinWatts = Math.max(s.best5MinWatts, st.powerCurve[IDX_5_MIN]);
                    s.best20MinWatts = Math.max(s.best20MinWatts, st.powerCurve[IDX_20_MIN]);
                }
            }
        }
        if (attempts != null) {
            for (StoredClimbAttempt a : attempts) {
                if (a == null || !indoorIds.contains(a.activityId)) continue;
                Integer year = seasonOf(day(a.dateEpochSec, zone));
                Season s = year != null ? seasons.get(year) : null;
                if (s != null) s.climbs++;
            }
        }
        return new ArrayList<>(seasons.descendingMap().values());
    }

    private static LocalDate day(long epochSec, ZoneId zone) {
        return Instant.ofEpochSecond(epochSec).atZone(zone).toLocalDate();
    }
}
