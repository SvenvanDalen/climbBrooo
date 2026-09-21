package nl.paree.climbpro.ui.routes;

/**
 * One line of the pre-ride checklist (see {@link PreRideChecklistBuilder}).
 * Pure data — no Android dependency — so it can be unit-tested and rendered
 * however the pre-ride screen sees fit.
 */
public final class PreRideChecklistItem {

    public enum Type {
        /** Estimated total ride duration (or a reminder to complete the rider profile). */
        DURATION,
        /** Generic reminder to check the watch's battery level before departure. */
        BATTERY,
        /** Climb count + total elevation gain on the route. */
        CLIMBS
    }

    public final Type type;
    public final String message;

    public PreRideChecklistItem(Type type, String message) {
        this.type = type;
        this.message = message;
    }
}
