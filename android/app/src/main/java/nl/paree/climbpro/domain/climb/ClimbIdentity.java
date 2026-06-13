package nl.paree.climbpro.domain.climb;

/**
 * Route-independent identity for a climb, so the same climb appearing in several
 * routes collapses onto one logbook record. Built from the climb start coordinate
 * (bucketed to absorb GPS jitter) and its length (bucketed to 100 m).
 */
public final class ClimbIdentity {

    /** ~0.001 deg latitude ≈ 110 m: buckets absorb GPS/simplification jitter. */
    private static final double COORD_BUCKET_DEG = 0.001;
    private static final double LENGTH_BUCKET_M  = 100.0;

    private ClimbIdentity() {}

    public static String of(double startLat, double startLon, int lengthM) {
        long latBucket = Math.round(startLat / COORD_BUCKET_DEG);
        long lonBucket = Math.round(startLon / COORD_BUCKET_DEG);
        long lenBucket = Math.round(lengthM / LENGTH_BUCKET_M);
        return latBucket + ":" + lonBucket + ":" + lenBucket;
    }
}
