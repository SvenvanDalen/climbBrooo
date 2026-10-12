package nl.paree.climbpro.service;

import android.util.Log;

import nl.paree.climbpro.data.route.ClimbMembership;
import nl.paree.climbpro.data.route.RouteCollection;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.climb.DayTripPlanner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a collection into a day-trip payload (issue #9): its loose climbs plus every climb of
 * its member routes, put in riding order by {@link DayTripPlanner} and sent as a radius
 * payload with {@code "ord": 1}. Over the byte budget, climbs are dropped from the end of the
 * trip (the last ones to be ridden) and {@link #wasTruncated()} is set.
 */
public final class DayTripAssembler {

    private static final String TAG = "DayTripAssembler";

    private final RouteRepository routeRepo;
    private final ClimbPayloadBuilder builder;
    private boolean truncated;

    public DayTripAssembler(RouteRepository routeRepo, ClimbPayloadBuilder builder) {
        this.routeRepo = routeRepo;
        this.builder = builder;
    }

    public boolean wasTruncated() { return truncated; }

    /** The collection's climbs in collection order; routes that fail to load are skipped. */
    public List<StoredClimb> climbsOf(RouteCollection collection) {
        List<StoredClimb> out = new ArrayList<>();
        if (collection == null) return out;
        if (collection.climbs != null) {
            for (ClimbMembership m : collection.climbs) {
                StoredRoute r = load(m.routeId);
                if (r != null && r.climbs != null && m.climbIndex >= 0
                        && m.climbIndex < r.climbs.size()) {
                    out.add(r.climbs.get(m.climbIndex));
                }
            }
        }
        if (collection.routeIds != null) {
            for (String routeId : collection.routeIds) {
                StoredRoute r = load(routeId);
                if (r != null && r.climbs != null) out.addAll(r.climbs);
            }
        }
        return out;
    }

    /**
     * @param startLatLon the rider's position, or null when unknown
     * @return the payload, or null when the collection has no climb with a start coordinate
     */
    public byte[] assemble(RouteCollection collection, double[] startLatLon) throws IOException {
        truncated = false;
        List<StoredClimb> trip = DayTripPlanner.order(climbsOf(collection), startLatLon);
        if (trip.isEmpty()) return null;
        byte[] payload = builder.buildRadiusPayload(trip, true);
        while (payload.length > PayloadBudget.MAX_BYTES && trip.size() > 1) {
            trip.remove(trip.size() - 1);
            truncated = true;
            payload = builder.buildRadiusPayload(trip, true);
        }
        if (truncated) Log.w(TAG, "Day trip over budget — sent the first " + trip.size() + " climbs");
        return payload.length <= PayloadBudget.MAX_BYTES ? payload : null;
    }

    private StoredRoute load(String routeId) {
        if (routeId == null || !routeRepo.hasRoute(routeId)) return null;
        try {
            return routeRepo.loadRoute(routeId);
        } catch (IOException e) {
            Log.w(TAG, "Could not load route " + routeId, e);
            return null;
        }
    }
}
