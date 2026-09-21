package nl.paree.climbpro.ui.routes;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import nl.paree.climbpro.domain.power.DurationFormat;

/**
 * Builds the small pre-ride checklist shown right before the user selects a route to
 * follow on the watch, or hands a route off to Garmin Connect for navigation (issue #71).
 *
 * <p>Phone-only, pure, and derived entirely from data we already compute for the route
 * detail "passport" ({@link RoutePassport}) — no new wire format, no new BT plumbing.
 * This is an advisory checklist, not a hard gate: callers show it and let the user
 * dismiss/skip it, the underlying action (select route / start navigation) always proceeds.
 */
public final class PreRideChecklistBuilder {

    private PreRideChecklistBuilder() {}

    public static List<PreRideChecklistItem> build(RoutePassport passport) {
        List<PreRideChecklistItem> items = new ArrayList<>();
        items.add(durationItem(passport));
        items.add(batteryItem());
        if (passport != null && passport.climbCount > 0) {
            items.add(climbsItem(passport));
        }
        return items;
    }

    private static PreRideChecklistItem durationItem(RoutePassport passport) {
        if (passport != null && passport.totalEstimatedSeconds >= 0) {
            String duration = DurationFormat.format(passport.totalEstimatedSeconds);
            return new PreRideChecklistItem(PreRideChecklistItem.Type.DURATION,
                    "Geschatte rijtijd: ~" + duration);
        }
        return new PreRideChecklistItem(PreRideChecklistItem.Type.DURATION,
                "Geen tijdschatting beschikbaar — vul je rijdersprofiel in voor een schatting.");
    }

    private static PreRideChecklistItem batteryItem() {
        return new PreRideChecklistItem(PreRideChecklistItem.Type.BATTERY,
                "Controleer het batterijniveau van je horloge voor vertrek.");
    }

    private static PreRideChecklistItem climbsItem(RoutePassport passport) {
        String climbWord = passport.climbCount == 1 ? "klim" : "klimmen";
        String message = String.format(Locale.US, "%d %s onderweg, in totaal %d hm klimwerk.",
                passport.climbCount, climbWord, passport.totalElevationGain);
        if (passport.hardestClimbName != null) {
            message += String.format(Locale.US, " Zwaarste: %s (%.1f%%).",
                    passport.hardestClimbName, passport.hardestClimbGradient * 100);
        }
        return new PreRideChecklistItem(PreRideChecklistItem.Type.CLIMBS, message);
    }
}
