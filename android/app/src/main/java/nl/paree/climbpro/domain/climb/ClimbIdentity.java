package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.data.route.StoredClimb;

/**
 * Route-independent identity for a climb, so the same climb appearing in several
 * routes collapses onto one logbook record. Built from the climb start coordinate
 * (bucketed to absorb GPS/simplification jitter) and its length (bucketed to 100 m).
 *
 * <h2>Why a 0.001 deg coordinate bucket</h2>
 * The bucket is intentionally generous (~110 m of latitude). It must absorb not
 * only raw GPS jitter but also geometry changes from route simplification: the
 * Douglas-Peucker pass can move a stored climb-start point further than GPS noise
 * alone would, so the same physical climb may surface with slightly different
 * start coordinates from one route to the next. A smaller bucket (e.g. the
 * 0.0005 deg originally planned) let those displaced starts fall into different
 * buckets, splitting one real climb into two logbook records. Widening to
 * 0.001 deg keeps the same physical climb on one key.
 *
 * <h2>Known limitation</h2>
 * This is plain rounding-based bucketing, so it has two failure modes:
 * <ul>
 *   <li>Two starts that are physically close but straddle a bucket boundary can
 *       still round to different keys (a single climb split into two records).</li>
 *   <li>Two genuinely different starts that happen to land in the same bucket
 *       collide onto one key.</li>
 * </ul>
 * Both are acceptable for v1. The worst case is cosmetic (a climb shown as two
 * logbook records), and the {@code >= 800 m} minimum climb length makes two
 * distinct climbs with near-identical start coordinates unlikely, so genuine
 * collisions are rare in practice.
 */
public final class ClimbIdentity {

    /**
     * ~0.001 deg latitude ≈ 110 m. Chosen over a smaller bucket so that route
     * simplification (Douglas-Peucker), which can shift a stored climb-start
     * further than raw GPS jitter, still keeps the same physical climb on one
     * key. See the class Javadoc for the boundary/collision tradeoff.
     *
     * <p>Note: the same constant is applied to longitude without latitude
     * scaling, so the longitude bucket is physically smaller (~70-80 m at
     * European latitudes). This is acceptable for a stable identity key.
     */
    private static final double COORD_BUCKET_DEG = 0.001;
    private static final double LENGTH_BUCKET_M  = 100.0;

    private ClimbIdentity() {}

    public static String of(double startLat, double startLon, int lengthM) {
        long latBucket = Math.round(startLat / COORD_BUCKET_DEG);
        long lonBucket = Math.round(startLon / COORD_BUCKET_DEG);
        long lenBucket = Math.round(lengthM / LENGTH_BUCKET_M);
        return latBucket + ":" + lonBucket + ":" + lenBucket;
    }

    /**
     * Same as {@link #of(double, double, int)}, but takes a {@link StoredClimb} directly and
     * applies the shared "effective length" fallback (stored length if present, else
     * end-minus-start distance). Callers that need a climb's identity key from a
     * {@code StoredClimb} MUST go through this overload rather than re-deriving the fallback
     * themselves — a divergent copy of this fallback computes a different key and silently
     * breaks climb/route lookups. See {@link KnownClimbs#fromRoute}.
     */
    public static String of(StoredClimb c) {
        return of(c.startLat, c.startLon, effectiveLength(c));
    }

    /**
     * The "effective length" fallback shared by {@link #of(StoredClimb)}: the stored climb
     * length if present, else the end-minus-start distance. Exposed separately so callers
     * that need this length for something other than the identity key (e.g. display/UI code)
     * can reuse the exact same computation instead of keeping their own copy of the ternary,
     * which would risk silently diverging from the value {@link #of(StoredClimb)} uses.
     */
    public static int effectiveLength(StoredClimb c) {
        return c.length > 0 ? c.length : (c.endDistance - c.startDistance);
    }

    /**
     * Approximate start coordinate ({@code {lat, lon}}, the bucket centre, i.e. within about
     * half a {@code COORD_BUCKET_DEG} of the real start) encoded in a key built by {@link #of},
     * or null when {@code climbId} is not in that format. Lets callers place logbook climbs on
     * the map without loading every stored route.
     */
    public static double[] approxStart(String climbId) {
        if (climbId == null) return null;
        String[] parts = climbId.split(":");
        if (parts.length != 3) return null;
        try {
            return new double[] {
                    Long.parseLong(parts[0]) * COORD_BUCKET_DEG,
                    Long.parseLong(parts[1]) * COORD_BUCKET_DEG};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
