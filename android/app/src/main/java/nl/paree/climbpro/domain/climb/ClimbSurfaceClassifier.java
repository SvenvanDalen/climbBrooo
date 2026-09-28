package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.domain.segment.SurfaceType;

import java.util.List;

/**
 * Aggregates a climb's per-segment surface types ({@link SurfaceType}, already set on
 * {@link StoredSegment#surfaceType} — see {@code ClimbDetailActivity}'s per-segment and bulk
 * surface editors) into a single overview classification (issue #74). Pure and static, no
 * Android framework dependency, mirroring {@link ClimbShapeClassifier}'s style.
 *
 * <p>Only {@link StoredSegment} carries surface data (the in-memory {@code Segment} domain
 * model does not), so this operates on the persisted/wire segment list.
 *
 * <p>Rule (majority-by-distance, deliberately simple — this is an overview badge, not a
 * scored metric): ASPHALT and COBBLESTONE count toward "paved" distance, GRAVEL and DIRT
 * toward "unpaved" distance; UNKNOWN segments are excluded from the totals. If no segment
 * has a known surface type, the result is UNKNOWN. Otherwise, if any segment is explicitly
 * tagged MIXED, or both the paved and unpaved shares of the known distance are at least
 * {@link ClimbConstants#SURFACE_MIXED_MINORITY_FRACTION}, the climb is MIXED. Otherwise the
 * dominant group (paved or unpaved) wins.
 */
public final class ClimbSurfaceClassifier {

    private ClimbSurfaceClassifier() {}

    public static ClimbSurfaceComposition classifyStored(List<StoredSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return ClimbSurfaceComposition.UNKNOWN;
        }

        long pavedDistance = 0;
        long unpavedDistance = 0;
        boolean hasExplicitMixed = false;

        for (StoredSegment seg : segments) {
            int type = SurfaceType.fromInt(seg.surfaceType);
            int distance = Math.max(0, seg.distance);
            switch (type) {
                case SurfaceType.ASPHALT:
                case SurfaceType.COBBLESTONE:
                    pavedDistance += distance;
                    break;
                case SurfaceType.GRAVEL:
                case SurfaceType.DIRT:
                    unpavedDistance += distance;
                    break;
                case SurfaceType.MIXED:
                    hasExplicitMixed = true;
                    break;
                case SurfaceType.UNKNOWN:
                default:
                    break;
            }
        }

        long knownDistance = pavedDistance + unpavedDistance;
        if (knownDistance == 0) {
            return hasExplicitMixed ? ClimbSurfaceComposition.MIXED : ClimbSurfaceComposition.UNKNOWN;
        }

        double pavedFraction = (double) pavedDistance / knownDistance;
        double unpavedFraction = (double) unpavedDistance / knownDistance;

        if (hasExplicitMixed
                || (pavedFraction >= ClimbConstants.SURFACE_MIXED_MINORITY_FRACTION
                        && unpavedFraction >= ClimbConstants.SURFACE_MIXED_MINORITY_FRACTION)) {
            return ClimbSurfaceComposition.MIXED;
        }

        return pavedFraction >= unpavedFraction
                ? ClimbSurfaceComposition.PAVED
                : ClimbSurfaceComposition.GRAVEL;
    }
}
