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
     * @param climb a stored climb; {@link StoredClimb#shape} may be null on routes stored
     *              before this field existed, in which case it is classified on the fly from
     *              the currently stored segments rather than shown as blank.
     */
    public static String forStoredClimb(StoredClimb climb) {
        if (climb == null) return "";
        ClimbShape shape;
        try {
            shape = climb.shape != null ? ClimbShape.valueOf(climb.shape) : null;
        } catch (IllegalArgumentException unknownValue) {
            shape = null;
        }
        if (shape == null) {
            shape = ClimbShapeClassifier.classifyStored(climb.segments);
        }
        return forShape(shape);
    }
}
