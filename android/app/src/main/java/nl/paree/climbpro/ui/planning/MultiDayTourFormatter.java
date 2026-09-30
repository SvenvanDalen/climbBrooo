package nl.paree.climbpro.ui.planning;

import nl.paree.climbpro.domain.planning.MultiDayTourPlan;
import nl.paree.climbpro.domain.planning.TourDay;
import nl.paree.climbpro.domain.planning.TourStop;
import nl.paree.climbpro.domain.power.DurationFormat;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Dutch copy for a {@link MultiDayTourPlan}: screen blocks and the shareable per-day list.
 * Pure (no Android types) so it is unit-testable. Never pretends to be a routed plan: all
 * distances are labelled hemelsbreed and the road route is left to Garmin/Strava/Komoot.
 */
public final class MultiDayTourFormatter {

    private static final Locale NL = new Locale("nl", "NL");

    static final String DISCLAIMER =
            "Afstanden zijn hemelsbreed (in rechte lijn) van de top van een klim naar de voet "
                    + "van de volgende — de echte afstand over de weg is langer. Plan de "
                    + "wegroute per dag zelf in Garmin Connect, Strava of Komoot.";

    private MultiDayTourFormatter() {}

    /** Plan-level warnings (fewer stops than days, max hm per day not reachable). */
    public static List<String> warnings(MultiDayTourPlan plan) {
        List<String> out = new ArrayList<>();
        if (plan.fewerStopsThanDays()) {
            out.add("Je koos " + plan.requestedDays + " dagen maar hebt maar "
                    + plan.days.size() + " klim" + (plan.days.size() == 1 ? "" : "men")
                    + " geselecteerd — het plan telt " + plan.days.size()
                    + (plan.days.size() == 1 ? " dag." : " dagen."));
        }
        if (plan.maxElevationPerDayM > 0 && plan.anyDayExceedsMaxElevation()) {
            if (plan.minDaysForMaxElevation > plan.days.size()) {
                out.add("Let op: met max " + hm(plan.maxElevationPerDayM)
                        + " per dag zijn voor deze volgorde minstens "
                        + plan.minDaysForMaxElevation + " dagen nodig.");
            } else if (anyStopAbove(plan, plan.maxElevationPerDayM)) {
                out.add("Let op: een of meer klimmen hebben op zichzelf al meer dan "
                        + hm(plan.maxElevationPerDayM) + ".");
            } else {
                out.add("Let op: verdeeld op klimtijd gaat een of meer dagen boven "
                        + hm(plan.maxElevationPerDayM)
                        + " — verdeel op hoogtemeters om binnen het maximum te blijven.");
            }
        }
        return out;
    }

    public static String summary(MultiDayTourPlan plan) {
        return plan.days.size() + (plan.days.size() == 1 ? " dag" : " dagen") + " · "
                + hm(plan.totalElevationGainM) + " klimhoogtemeters · verdeeld op "
                + (plan.balancedOnClimbTime ? "geschatte klimtijd" : "hoogtemeters");
    }

    public static String dayHeader(TourDay day) {
        return "Dag " + day.dayNumber + " — " + hm(day.elevationGainM);
    }

    public static String dayBody(TourDay day, int maxElevationPerDayM) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < day.stops.size(); i++) {
            TourStop s = day.stops.get(i);
            sb.append(i + 1).append(". ").append(s.name).append(" (")
                    .append(hm(s.elevationGainM)).append(", ").append(km(s.lengthM));
            if (s.climbSeconds != null) {
                sb.append(", ").append(DurationFormat.format(s.climbSeconds));
            }
            sb.append(")\n");
        }
        sb.append("Klimmen: ").append(km(day.climbLengthM));
        if (day.climbSeconds > 0) {
            sb.append(" · klimtijd ~").append(DurationFormat.format(day.climbSeconds));
            if (!day.climbTimeComplete) sb.append(" (onvolledig)");
        }
        sb.append("\nTussen klimmen: ").append(km(day.transferMetersHemelsbreed))
                .append(" hemelsbreed");
        if (day.exceedsMaxElevation) {
            sb.append("\nLet op: boven je maximum van ").append(hm(maxElevationPerDayM))
                    .append(" per dag");
        }
        return sb.toString();
    }

    /** Plain-text per-day list for the Android share sheet. */
    public static String shareText(MultiDayTourPlan plan) {
        StringBuilder sb = new StringBuilder("Meerdaagse toer — ").append(summary(plan)).append("\n");
        for (String w : warnings(plan)) sb.append(w).append("\n");
        for (TourDay day : plan.days) {
            sb.append("\n").append(dayHeader(day)).append("\n")
                    .append(dayBody(day, plan.maxElevationPerDayM)).append("\n");
        }
        sb.append("\n").append(DISCLAIMER);
        return sb.toString();
    }

    private static boolean anyStopAbove(MultiDayTourPlan plan, int maxHm) {
        for (TourDay d : plan.days) {
            for (TourStop s : d.stops) if (s.elevationGainM > maxHm) return true;
        }
        return false;
    }

    static String hm(int meters) {
        return NumberFormat.getIntegerInstance(NL).format(meters) + " hm";
    }

    static String km(int meters) {
        return String.format(NL, "%.1f km", meters / 1000.0);
    }
}
