package nl.paree.climbpro.domain.mywhoosh;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.route.StoredClimbAttempt;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Virtual elevation counted apart (issue #393): MyWhoosh and other indoor hm can be left out
 * of the elevation goals and the climbing year overview with one setting. Counting them stays
 * the default, so nothing changes until the rider opts out. Pure; phone-only.
 */
public final class VirtualElevation {

    private VirtualElevation() {}

    /** Default SharedPreferences key; absent = true (indoor hm count). */
    public static final String PREF_COUNT_VIRTUAL = "count_virtual_elevation";

    /** The rides that count: all of them, or only the outdoor ones. */
    public static List<StoredRide> countedRides(List<StoredRide> rides, boolean includeVirtual) {
        List<StoredRide> out = new ArrayList<>();
        if (rides == null) return out;
        for (StoredRide r : rides) {
            if (r != null && (includeVirtual || !IndoorRides.isIndoor(r))) out.add(r);
        }
        return out;
    }

    /** The indoor rides only, to show their hm separately. */
    public static List<StoredRide> virtualRides(List<StoredRide> rides) {
        List<StoredRide> out = new ArrayList<>();
        if (rides == null) return out;
        for (StoredRide r : rides) if (IndoorRides.isIndoor(r)) out.add(r);
        return out;
    }

    /** The climb attempts that count: all, or those not ridden on an indoor activity. */
    public static List<StoredClimbAttempt> countedAttempts(List<StoredClimbAttempt> attempts,
                                                           Set<Long> indoorActivityIds,
                                                           boolean includeVirtual) {
        List<StoredClimbAttempt> out = new ArrayList<>();
        if (attempts == null) return out;
        for (StoredClimbAttempt a : attempts) {
            if (a == null) continue;
            if (includeVirtual || indoorActivityIds == null
                    || !indoorActivityIds.contains(a.activityId)) out.add(a);
        }
        return out;
    }

    /** The climb attempts ridden on an indoor activity. */
    public static List<StoredClimbAttempt> virtualAttempts(List<StoredClimbAttempt> attempts,
                                                           Set<Long> indoorActivityIds) {
        List<StoredClimbAttempt> out = new ArrayList<>();
        if (attempts == null || indoorActivityIds == null) return out;
        for (StoredClimbAttempt a : attempts) {
            if (a != null && indoorActivityIds.contains(a.activityId)) out.add(a);
        }
        return out;
    }
}
