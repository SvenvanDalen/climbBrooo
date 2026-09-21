package nl.paree.climbpro.domain.climb;

import java.util.Random;

/**
 * Obscures a lat/lon within a configurable radius, for phone-side export/share privacy
 * filtering of "home climb" start locations (issue #92 "Privacy-modus voor thuisklim-
 * startlocatie") — analogous to Strava's privacy zones. Phone-only: the wire payload sent to
 * the watch and all internal calculations (matching, PR times, time estimates) always use the
 * real {@link StoredClimb#startLat}/{@link StoredClimb#startLon}; only {@code ClimbGpxWriter}'s
 * export path reads this class.
 *
 * <p><b>Why a randomized-but-deterministic offset, not a coarse grid snap:</b> the same home
 * climb must fuzz to the exact same obscured point on every export (a different point per
 * export would let anyone comparing two exported files triangulate roughly where the real
 * point lies, defeating the purpose), so the offset is seeded from the exact input coordinate
 * rather than {@link Math#random()}. A grid-snap alternative was considered but rejected: it
 * would (a) sometimes snap back into the cell containing the true point when the point sits
 * near a cell boundary, weakening the guarantee, and (b) leave every fuzzed point suspiciously
 * aligned to round grid lines, which is itself a signature that lets a viewer infer "this is a
 * fuzzed point, the real one is somewhere in this cell." A random direction and distance drawn
 * uniformly across the privacy circle (minus a small dead zone near the centre so the output
 * is never the exact input) gives a plausible-looking point anywhere within the zone instead.
 */
public final class CoordinateFuzzer {

    /** Pref key for the user-configurable privacy-zone radius, in meters. Android-free constant
     *  so {@code SettingsViewModel}/{@code ClimbDetailViewModel} can share it without pulling
     *  Android types into this pure domain class. */
    public static final String PREF_PRIVACY_RADIUS_M = "privacy_radius_metres";
    public static final int DEFAULT_PRIVACY_RADIUS_M = 500;

    /** Fraction of the radius carved out as a dead zone around the true point, so the fuzzed
     *  point is never mistakenly close enough to read as "basically the real location." */
    private static final double MIN_OFFSET_FRACTION = 0.15;

    private static final double EARTH_RADIUS_M = 6_371_000.0;

    private CoordinateFuzzer() {}

    /**
     * @param lat           real latitude, degrees
     * @param lon           real longitude, degrees
     * @param radiusMeters  privacy-zone radius; a value {@code <= 0} disables fuzzing and
     *                      returns the input unchanged (treat as "feature off")
     * @return {@code [fuzzedLat, fuzzedLon]}, deterministic for the same input, always within
     *         {@code radiusMeters} of the input, and never exactly equal to the input when
     *         {@code radiusMeters > 0}
     */
    public static double[] fuzz(double lat, double lon, double radiusMeters) {
        if (radiusMeters <= 0) return new double[] {lat, lon};

        Random rnd = new Random(seedFor(lat, lon, radiusMeters));
        double angle = rnd.nextDouble() * 2 * Math.PI;
        double distance = radiusMeters * (MIN_OFFSET_FRACTION
                + (1 - MIN_OFFSET_FRACTION) * rnd.nextDouble());

        double dLatDeg = Math.toDegrees((distance * Math.cos(angle)) / EARTH_RADIUS_M);
        double cosLat = Math.cos(Math.toRadians(lat));
        // Near the poles cos(lat) -> 0 and the longitude offset blows up; clamp so the result
        // stays a valid, bounded coordinate rather than NaN/huge (irrelevant for real rides,
        // but keeps the function total).
        double safeCosLat = Math.max(Math.abs(cosLat), 1e-6) * Math.signum(cosLat == 0 ? 1 : cosLat);
        double dLonDeg = Math.toDegrees((distance * Math.sin(angle)) / (EARTH_RADIUS_M * safeCosLat));

        return new double[] {lat + dLatDeg, lon + dLonDeg};
    }

    /** Deterministic seed from the quantized input, so the same climb always fuzzes the same
     *  way but two distinct nearby climbs don't collide onto the same offset pattern. */
    private static long seedFor(double lat, double lon, double radiusMeters) {
        long latBits = Math.round(lat * 1_000_000);
        long lonBits = Math.round(lon * 1_000_000);
        long radiusBits = Math.round(radiusMeters);
        return latBits * 1_000_003L + lonBits * 97L + radiusBits;
    }
}
