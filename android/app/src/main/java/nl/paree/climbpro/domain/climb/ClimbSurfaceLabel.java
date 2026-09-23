package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.data.route.StoredClimb;

/**
 * Short, user-facing (Dutch, matching the rest of the UI's copy) label for a
 * {@link ClimbSurfaceComposition}. Kept separate from the enum itself, mirroring
 * {@link ClimbShapeLabel}.
 */
public final class ClimbSurfaceLabel {

    private ClimbSurfaceLabel() {}

    public static String forComposition(ClimbSurfaceComposition composition) {
        if (composition == null) return "";
        switch (composition) {
            case PAVED:   return "asfalt";
            case GRAVEL:  return "gravel";
            case MIXED:   return "gemengd";
            case UNKNOWN:
            default:      return "";
        }
    }

    /** @param climb a stored climb; classified on the fly from its current segments. */
    public static String forStoredClimb(StoredClimb climb) {
        if (climb == null) return "";
        return forComposition(ClimbSurfaceClassifier.classifyStored(climb.segments));
    }
}
