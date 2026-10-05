package nl.paree.climbpro.domain.mywhoosh;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.domain.activity.MyWhooshRouteReader;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which archived rides are indoor (any trainer app, Strava's {@code VirtualRide}) and which of
 * those come from MyWhoosh, the base of the "more data from MyWhoosh" features (issues
 * #387-#409). Pure; phone-only.
 */
public final class IndoorRides {

    private IndoorRides() {}

    /** VirtualRide or EVirtualRide, in either Strava type field. */
    public static boolean isIndoor(StoredRide r) {
        if (r == null) return false;
        return (r.type != null && r.type.contains("Virtual"))
                || (r.sportType != null && r.sportType.contains("Virtual"));
    }

    /** A MyWhoosh upload: a virtual ride named "MyWhoosh - &lt;route&gt;". */
    public static boolean isMyWhoosh(StoredRide r) {
        return r != null && MyWhooshRouteReader.isMyWhooshActivity(r.name, r.type, r.sportType);
    }

    /** "MyWhoosh - Hautacam Summit" → "Hautacam Summit". */
    public static String routeTitle(StoredRide r) {
        return MyWhooshRouteReader.routeTitle(r != null ? r.name : null);
    }

    /** Activity ids of the MyWhoosh rides in {@code rides}. */
    public static Set<Long> myWhooshIds(List<StoredRide> rides) {
        Set<Long> ids = new HashSet<>();
        if (rides == null) return ids;
        for (StoredRide r : rides) if (isMyWhoosh(r)) ids.add(r.activityId);
        return ids;
    }

    /** Activity ids of all indoor rides in {@code rides}. */
    public static Set<Long> indoorIds(List<StoredRide> rides) {
        Set<Long> ids = new HashSet<>();
        if (rides == null) return ids;
        for (StoredRide r : rides) if (isIndoor(r)) ids.add(r.activityId);
        return ids;
    }
}
