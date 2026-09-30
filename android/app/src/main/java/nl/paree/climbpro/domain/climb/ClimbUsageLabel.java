package nl.paree.climbpro.domain.climb;

/**
 * Short, user-facing (Dutch, matching the rest of the UI's copy) label for a
 * {@link ClimbUsageType}. Kept separate from the enum itself, mirroring {@link ClimbShapeLabel}.
 */
public final class ClimbUsageLabel {

    private ClimbUsageLabel() {}

    /** UNKNOWN (and null) render as an empty string — insufficient data is not shown as a badge. */
    public static String forType(ClimbUsageType type) {
        if (type == null) return "";
        switch (type) {
            case TRAINING:     return "trainingsklim";
            case RECREATIONAL: return "eenmalige klim";
            case UNKNOWN:
            default:           return "";
        }
    }
}
