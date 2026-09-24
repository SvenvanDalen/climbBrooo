package nl.paree.climbpro.domain.climb;

import java.util.Random;

import nl.paree.climbpro.domain.route.CumulativeDistance;

/**
 * Privacy zone for "home climb" start locations (issue #92 "Privacy-modus voor thuisklim-
 * startlocatie"), modelled on Strava's privacy zones. Phone-only: the wire payload sent to
 * the watch and all internal calculations (matching, PR times, time estimates) always use the
 * real {@link nl.paree.climbpro.data.route.StoredClimb#startLat}/{@code startLon}; only the
 * GPX export path reads this class.
 *
 * <p>The zone is a circle of the user's radius around a <b>centre that is offset from the real
 * start</b>. On export every point inside the circle is dropped and the centre stands in for
 * the start. Because the circle is not centred on the real start, neither the centre nor the
 * edge where the visible track begins reveals where the start is.
 *
 * <p><b>Why a stored random centre, not one derived from the coordinate:</b> any centre computed
 * from the start coordinate (even through a hashed seed) can be reversed by brute-forcing the
 * candidate starts inside the radius. A centre that is re-drawn per export or per radius lets an
 * attacker intersect several exported zones. So the centre is drawn once from a
 * {@link java.security.SecureRandom}, stored with the climb, and reused for as long as
 * {@link #isUsableZoneCentre} holds. Growing the radius keeps the same centre; only shrinking
 * it below the centre's offset, or a resync that moves the start, forces a new one.
 */
public final class CoordinateFuzzer {

    /** Pref key for the user-configurable privacy-zone radius, in meters. Android-free constant
     *  so {@code SettingsViewModel}/{@code ClimbDetailViewModel} can share it without pulling
     *  Android types into this pure domain class. */
    public static final String PREF_PRIVACY_RADIUS_M = "privacy_radius_metres";
    public static final int DEFAULT_PRIVACY_RADIUS_M = 500;
    /** Smallest radius the setting allows; a zone of 0 m would silently disable the feature
     *  while the UI still reports the start as obscured. */
    public static final int MIN_PRIVACY_RADIUS_M = 100;
    public static final int MAX_PRIVACY_RADIUS_M = 2000;

    /** Centre offset range as a fraction of the radius. The lower bound keeps the centre from
     *  reading as "basically the real start"; the upper bound keeps the real start well inside
     *  the zone so the first metres of the approach are always hidden. */
    private static final double MIN_OFFSET_FRACTION = 0.15;
    private static final double MAX_OFFSET_FRACTION = 0.85;

    private static final double EARTH_RADIUS_M = 6_371_000.0;

    private CoordinateFuzzer() {}

    /** Clamps a stored/pref radius to the allowed range. */
    public static int effectiveRadius(int radiusMeters) {
        return Math.max(MIN_PRIVACY_RADIUS_M, Math.min(MAX_PRIVACY_RADIUS_M, radiusMeters));
    }

    /**
     * Draws a new zone centre at a random bearing, {@code [0.15, 0.85] * radius} from the real
     * start. Production callers must pass a {@link java.security.SecureRandom}; tests may pass
     * a seeded {@link Random}.
     *
     * @return {@code [lat, lon]} of the centre
     */
    public static double[] randomZoneCentre(double lat, double lon, double radiusMeters,
            Random rnd) {
        double angle = rnd.nextDouble() * 2 * Math.PI;
        double distance = radiusMeters * (MIN_OFFSET_FRACTION
                + (MAX_OFFSET_FRACTION - MIN_OFFSET_FRACTION) * rnd.nextDouble());

        double dLatDeg = Math.toDegrees((distance * Math.cos(angle)) / EARTH_RADIUS_M);
        // Near the poles cos(lat) -> 0 and the longitude offset blows up; clamp so the result
        // stays a valid, bounded coordinate (irrelevant for real rides, keeps the function total).
        double cosLat = Math.max(Math.abs(Math.cos(Math.toRadians(lat))), 1e-6);
        double dLonDeg = Math.toDegrees((distance * Math.sin(angle)) / (EARTH_RADIUS_M * cosLat));

        return new double[] {lat + dLatDeg, lon + dLonDeg};
    }

    /**
     * True when a stored centre still hides the real start deep enough inside a zone of
     * {@code radiusMeters}. False when there is no centre yet, the radius was reduced below the
     * centre's offset, or a resync moved the start away from it.
     */
    public static boolean isUsableZoneCentre(Double centreLat, Double centreLon,
            double startLat, double startLon, double radiusMeters) {
        if (centreLat == null || centreLon == null || radiusMeters <= 0) return false;
        return CumulativeDistance.haversine(centreLat, centreLon, startLat, startLon)
                <= radiusMeters * MAX_OFFSET_FRACTION;
    }

    /** True when {@code (lat, lon)} lies inside the zone (straight-line distance to its centre). */
    public static boolean isInZone(double lat, double lon, double centreLat, double centreLon,
            double radiusMeters) {
        return CumulativeDistance.haversine(lat, lon, centreLat, centreLon) < radiusMeters;
    }
}
