package nl.paree.climbpro.domain.ride;

/** Dutch user-facing label for a {@link RideCategory}, matching the rest of the UI copy. */
public final class RideCategoryLabel {

    private RideCategoryLabel() {}

    public static String forCategory(RideCategory category) {
        if (category == null) return "";
        switch (category) {
            case COMMUTE:  return "Woon-werk";
            case TOUR:     return "Toerrit";
            case TRAINING:
            default:       return "Training";
        }
    }
}
