package nl.paree.climbpro.ui.routes;

import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRideStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure filter helper for the route list's bucket-list status (issue #158). The filter
 * index matches the order of the choices in RouteListActivity's filter dialog.
 */
public final class RouteStatusFilter {

    /** Show every route, regardless of status. (Default.) */
    public static final int FILTER_ALL          = 0;
    /** Only routes marked "Wil ik rijden". */
    public static final int FILTER_WANT_TO_RIDE = 1;
    /** Only routes marked "Gereden". */
    public static final int FILTER_RIDDEN       = 2;

    private RouteStatusFilter() {}

    /** Returns a new, filtered list; the input list is left unchanged. */
    public static List<RouteCatalogEntry> apply(List<RouteCatalogEntry> in, int filter) {
        String wanted;
        switch (filter) {
            case FILTER_WANT_TO_RIDE: wanted = RouteRideStatus.WANT_TO_RIDE; break;
            case FILTER_RIDDEN:       wanted = RouteRideStatus.RIDDEN;       break;
            case FILTER_ALL:
            default:
                return new ArrayList<>(in);
        }
        List<RouteCatalogEntry> out = new ArrayList<>();
        for (RouteCatalogEntry e : in) {
            if (wanted.equals(RouteRideStatus.normalize(e.rideStatus))) out.add(e);
        }
        return out;
    }
}
