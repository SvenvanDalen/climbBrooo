package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.data.route.StoredClimb;

/**
 * Short, user-facing (Dutch, matching the rest of the UI's copy) label for a {@link ClimbShape}.
 * Kept separate from the enum itself so the enum stays a plain domain type.
 */
public final class ClimbShapeLabel {

    private ClimbShapeLabel() {}

    public static String forShape(ClimbShape shape) {
        if (shape == null) return "";
        switch (shape) {
            case STEEP_FINISH: return "steil einde";
            case EASY_START:   return "rustige start";
            case IRREGULAR:    return "grillig";
            case STEADY:
            default:           return "gelijkmatig";
        }
    }

    /**
     * @param climb a stored climb; prefers the user's manual override
     *              ({@link StoredClimb#shapeOverride}, issue #36) when set, otherwise falls
     *              back to {@link StoredClimb#shape} or, if that's null (routes stored before
     *              the field existed), a fresh classification of the currently stored segments.
     *              See {@link ClimbShapeClassifier#effectiveShape}.
     */
    public static String forStoredClimb(StoredClimb climb) {
        if (climb == null) return "";
        return forShape(ClimbShapeClassifier.effectiveShape(climb));
    }
}
