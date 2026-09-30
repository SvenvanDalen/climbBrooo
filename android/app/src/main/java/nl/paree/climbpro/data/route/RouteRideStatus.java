package nl.paree.climbpro.data.route;

/**
 * User-set "bucket list" status of a route (issue #158), stored as a string on
 * {@link StoredRoute#rideStatus} and mirrored to {@link RouteCatalogEntry#rideStatus}.
 * {@code null} means "geen status" — also the value for routes stored before the field
 * existed, so old JSON needs no migration. Phone-only, never synced to the watch.
 * The status is purely manual: nothing (climb attempts, Strava activities) changes it
 * automatically.
 */
public final class RouteRideStatus {

    public static final String WANT_TO_RIDE = "WANT_TO_RIDE";
    public static final String RIDDEN       = "RIDDEN";

    private RouteRideStatus() {}

    /** Returns the canonical value, or null for null/blank/unknown input. */
    public static String normalize(String status) {
        if (WANT_TO_RIDE.equals(status)) return WANT_TO_RIDE;
        if (RIDDEN.equals(status))       return RIDDEN;
        return null;
    }

    /** Dutch UI label; null status maps to "Geen status". */
    public static String label(String status) {
        String s = normalize(status);
        if (WANT_TO_RIDE.equals(s)) return "Wil ik rijden";
        if (RIDDEN.equals(s))       return "Gereden";
        return "Geen status";
    }
}
