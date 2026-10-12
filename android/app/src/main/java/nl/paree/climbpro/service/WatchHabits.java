package nl.paree.climbpro.service;

import android.content.Context;
import android.util.Log;

import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.ride.RideStreamStatsRepository;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.ride.StoredRideStreamStats;
import nl.paree.climbpro.data.rider.RiderProfileRepository;
import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.domain.mywhoosh.CadenceByGrade;
import nl.paree.climbpro.domain.ride.HeartRateByGrade;
import nl.paree.climbpro.domain.ride.ZoneCalculator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the watch payload knows about the rider's own history: which climbs were ever ridden
 * ('nw', issue #27) and the usual cadence and heart-rate zone per gradient class ('cg' /
 * 'hg', issues #18 / #24). Computed from data the phone already stores; {@link #compute} is
 * pure, {@link #load} reads the repositories and never throws.
 */
public final class WatchHabits {

    private static final String TAG = "WatchHabits";

    /** Nothing known: the payload stays as before. */
    public static final WatchHabits NONE = new WatchHabits(Collections.emptySet(), null, null);

    public final Set<String> riddenClimbIds;
    /** Six rpm values (0 = unknown) or null. */
    public final int[] cadenceByGrade;
    /** Six zone values 1-5 (0 = unknown) or null. */
    public final int[] hrZoneByGrade;

    WatchHabits(Set<String> riddenClimbIds, int[] cadenceByGrade, int[] hrZoneByGrade) {
        this.riddenClimbIds = riddenClimbIds;
        this.cadenceByGrade = cadenceByGrade;
        this.hrZoneByGrade = hrZoneByGrade;
    }

    /**
     * @param setMaxHr the rider's own max heart rate, 0 = use the highest one observed in rides
     */
    public static WatchHabits compute(List<StoredClimbAttempt> attempts, List<StoredRide> rides,
                                      Map<Long, StoredRideStreamStats> stats, int setMaxHr) {
        Set<String> ridden = new HashSet<>();
        if (attempts != null) {
            for (StoredClimbAttempt a : attempts) {
                if (a != null && a.climbId != null) ridden.add(a.climbId);
            }
        }
        int[] cadence = null;
        int[] hrZones = null;
        if (stats != null && !stats.isEmpty()) {
            CadenceByGrade.Result c = CadenceByGrade.compute(rides, stats, false);
            cadence = ClimbPayloadBuilder.gradeClasses(c.avgRpm, 250);
            List<StoredRideStreamStats> all = new ArrayList<>(stats.values());
            int maxHr = setMaxHr > 0 ? setMaxHr : ZoneCalculator.observedMaxHr(all);
            hrZones = HeartRateByGrade.zones(all, maxHr);
        }
        return new WatchHabits(ridden, cadence, hrZones);
    }

    /** Reads attempts, rides, stream stats and max heart rate; {@link #NONE} on any failure. */
    public static WatchHabits load(Context ctx) {
        try {
            return compute(new ClimbAttemptRepository(ctx).loadAll(),
                    new RideRepository(ctx).loadAll(),
                    new RideStreamStatsRepository(ctx).loadById(),
                    new RiderProfileRepository(ctx).loadMaxHeartRate());
        } catch (Exception e) {
            Log.w(TAG, "Rider history unavailable; payload without nw/cg/hg", e);
            return NONE;
        }
    }

    public ClimbPayloadBuilder applyTo(ClimbPayloadBuilder builder) {
        return builder.withRiddenClimbIds(riddenClimbIds)
                .withGradeHabits(cadenceByGrade, hrZoneByGrade);
    }

    /**
     * Folded into the sync hash so a new ride that changes cg/hg, or a first ride up a climb
     * (clears its nw), resyncs the active route. Empty when nothing is known, so riders
     * without any history keep their existing hash.
     */
    public String signature() {
        if (riddenClimbIds.isEmpty() && cadenceByGrade == null && hrZoneByGrade == null) return "";
        List<String> ids = new ArrayList<>(riddenClimbIds);
        Collections.sort(ids);
        return "|hab" + ids.hashCode() + ":" + Arrays.toString(cadenceByGrade)
                + ":" + Arrays.toString(hrZoneByGrade);
    }
}
