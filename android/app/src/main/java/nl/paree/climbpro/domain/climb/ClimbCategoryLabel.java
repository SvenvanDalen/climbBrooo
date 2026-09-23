package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.data.route.StoredClimb;

/**
 * Short, user-facing label for a {@link ClimbCategory}, e.g. "HC" or "Cat. 2". Kept
 * separate from the enum itself so the enum stays a plain domain type, mirroring
 * {@link ClimbShapeLabel}'s split from {@link ClimbShape}.
 */
public final class ClimbCategoryLabel {

    private ClimbCategoryLabel() {}

    public static String forCategory(ClimbCategory category) {
        if (category == null) return "";
        switch (category) {
            case HC:    return "HC";
            case CAT_1: return "Cat. 1";
            case CAT_2: return "Cat. 2";
            case CAT_3: return "Cat. 3";
            case CAT_4: return "Cat. 4";
            case UNCATEGORIZED:
            default:    return "";
        }
    }

    /** Computes the category on the fly from a stored climb's length/avgGradient and labels it. */
    public static String forStoredClimb(StoredClimb climb) {
        if (climb == null) return "";
        return forCategory(ClimbCategoryCalculator.categorize(climb.length, climb.avgGradient));
    }
}
