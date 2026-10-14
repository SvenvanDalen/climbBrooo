package nl.paree.climbpro.domain.mywhoosh;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.route.StoredClimbAttempt;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which rides a statistics screen counts (issue #392): everything, only outdoor rides, or only
 * MyWhoosh rides. Other indoor apps (Zwift, …) count under {@link #ALL} only. Climb attempts
 * follow the ride they came from; an attempt without a known ride (a file import) is outdoor.
 * Pure; phone-only.
 */
public enum RideSource {
    ALL, OUTDOOR, MYWHOOSH;

    /** Shared by the four screens, so the choice carries over between them. */
    public static final String PREF_KEY = "stats_ride_source";

    public static RideSource fromName(String name) {
        if (name == null) return ALL;
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return ALL;
        }
    }

    public boolean includes(StoredRide r) {
        if (r == null) return false;
        if (this == OUTDOOR) return !IndoorRides.isIndoor(r);
        if (this == MYWHOOSH) return IndoorRides.isMyWhoosh(r);
        return true;
    }

    public List<StoredRide> filter(List<StoredRide> rides) {
        if (this == ALL || rides == null) return rides;
        List<StoredRide> out = new ArrayList<>();
        for (StoredRide r : rides) if (includes(r)) out.add(r);
        return out;
    }

    /** The attempts whose ride matches; {@code rides} is the whole archive. */
    public List<StoredClimbAttempt> filterAttempts(List<StoredClimbAttempt> attempts,
                                                   List<StoredRide> rides) {
        if (this == ALL || attempts == null) return attempts;
        Set<Long> indoor = IndoorRides.indoorIds(rides);
        Set<Long> myWhoosh = this == MYWHOOSH ? IndoorRides.myWhooshIds(rides) : new HashSet<>();
        List<StoredClimbAttempt> out = new ArrayList<>();
        for (StoredClimbAttempt a : attempts) {
            boolean keep = this == MYWHOOSH
                    ? myWhoosh.contains(a.activityId)
                    : !indoor.contains(a.activityId);
            if (keep) out.add(a);
        }
        return out;
    }
}
