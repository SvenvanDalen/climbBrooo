package nl.paree.climbpro.domain.weather;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Hour-by-hour text of where rain is expected along the route (issue #245). Pure. */
public final class RouteRainSummary {

    /** Precipitation per hour (mm) from which a sample counts as "rain". */
    public static final double WET_MM = 0.1;

    private static final Locale NL = new Locale("nl");
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm");

    private RouteRainSummary() {}

    public static String describe(List<RouteSampler.Sample> samples, PrecipitationGrid grid,
                                  Instant now, int hours, ZoneId zone) {
        int start = grid.indexAt(now);
        if (start < 0) {
            for (int i = 0; i < grid.times.length; i++) {
                if (grid.times[i].isAfter(now)) { start = i; break; }
            }
        }
        if (start < 0 || samples.isEmpty() || hours < 1) {
            return "Geen neerslagverwachting beschikbaar.";
        }
        int end = Math.min(grid.times.length, start + hours);
        List<String> lines = new ArrayList<>();
        boolean anyRainOrUnknown = false;
        for (int h = start; h < end; h++) {
            String wet = wetStretches(samples, grid, h);
            String what;
            if (wet == null) what = "geen gegevens";
            else if (wet.isEmpty()) what = "droog";
            else what = "regen bij " + wet;
            if (wet == null || !wet.isEmpty()) anyRainOrUnknown = true;
            lines.add(HOUR.format(grid.times[h].atZone(zone)) + "  " + what);
        }
        if (!anyRainOrUnknown) {
            return "Geen regen verwacht langs de route in de komende " + (end - start) + " uur.";
        }
        return "Regen langs de route (per uur):\n" + String.join("\n", lines);
    }

    /** "km a–b (max mm), ..." for hour {@code h}; "" when dry; null when every sample is unknown. */
    private static String wetStretches(List<RouteSampler.Sample> samples, PrecipitationGrid grid,
                                       int h) {
        int n = Math.min(samples.size(), grid.mm.length);
        boolean anyKnown = false;
        List<String> runs = new ArrayList<>();
        int runStart = -1;
        double runMax = 0;
        for (int k = 0; k <= n; k++) {
            double v = k < n ? grid.mm[k][h] : Double.NaN;
            if (k < n && !Double.isNaN(v)) anyKnown = true;
            boolean wet = k < n && v >= WET_MM;
            if (wet) {
                if (runStart < 0) { runStart = k; runMax = v; } else runMax = Math.max(runMax, v);
            } else if (runStart >= 0) {
                runs.add(range(samples.get(runStart), samples.get(k - 1))
                        + String.format(NL, " (%.1f mm)", runMax));
                runStart = -1;
            }
        }
        if (!anyKnown) return null;
        return String.join(", ", runs);
    }

    private static String range(RouteSampler.Sample a, RouteSampler.Sample b) {
        String from = String.format(NL, "%.0f", a.distanceM / 1000.0);
        if (a == b) return "km " + from;
        return "km " + from + "–" + String.format(NL, "%.0f", b.distanceM / 1000.0);
    }
}
