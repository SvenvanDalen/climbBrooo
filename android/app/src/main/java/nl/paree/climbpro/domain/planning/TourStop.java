package nl.paree.climbpro.domain.planning;

/**
 * One climb (or a whole planned route) to distribute over a multi-day tour (issue #67).
 * Immutable input to {@link MultiDayTourPlanner}. Coordinates are the climb's start and top;
 * the planner only ever measures straight-line (hemelsbreed) distances between them — there is
 * no road router in the app, the real road route is planned in Garmin/Strava/Komoot.
 */
public final class TourStop {

    /** Stable identity (e.g. {@code routeId#climbIndex}); also the deterministic sort key. */
    public final String key;
    public final String name;
    public final double startLat;
    public final double startLon;
    public final double endLat;
    public final double endLon;
    /** Climbing elevation gain in metres (the day-load unit when balancing on hoogtemeters). */
    public final int elevationGainM;
    /** Road length of the climb itself, metres. */
    public final int lengthM;
    /** Estimated climbing time in seconds, or null when unknown (incomplete rider profile). */
    public final Integer climbSeconds;

    public TourStop(String key, String name,
                    double startLat, double startLon, double endLat, double endLon,
                    int elevationGainM, int lengthM, Integer climbSeconds) {
        this.key = key;
        this.name = name;
        this.startLat = startLat;
        this.startLon = startLon;
        this.endLat = endLat;
        this.endLon = endLon;
        this.elevationGainM = Math.max(0, elevationGainM);
        this.lengthM = Math.max(0, lengthM);
        this.climbSeconds = climbSeconds;
    }
}
