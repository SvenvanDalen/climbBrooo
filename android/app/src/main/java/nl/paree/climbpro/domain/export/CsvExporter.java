package nl.paree.climbpro.domain.export;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.climb.ClimbIdentity;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the CSV export of routes and climb attempts (issue #256). Pure: callers pass the
 * loaded routes/attempts and a zone; nothing here touches Android or the file system.
 *
 * <p>Format: RFC 4180 (comma separator, CRLF line ends, fields quoted when they contain a
 * comma, quote or line break), dot decimals and ISO-8601 local dates so spreadsheets and
 * scripts parse the numbers without locale guessing. {@link #BOM} is prepended by the file
 * writer so Excel detects UTF-8 (climb names often carry accents).
 */
public final class CsvExporter {

    public static final String BOM = "\uFEFF"; // UTF-8 byte-order mark
    private static final String EOL = "\r\n";
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US);

    private CsvExporter() {}

    /** One row per route, in the given order. */
    public static String routesCsv(List<StoredRoute> routes, ZoneId zone) {
        StringBuilder sb = new StringBuilder();
        row(sb, "route_id", "naam", "afstand_km", "hoogtemeters_klimmen", "aantal_klimmen",
                "geimporteerd", "notities");
        for (StoredRoute r : routes) {
            row(sb,
                    r.routeId,
                    displayName(r.userDisplayName, r.name),
                    km(routeLengthM(r)),
                    String.valueOf(climbGain(r)),
                    String.valueOf(r.climbs != null ? r.climbs.size() : 0),
                    r.importedAtMs > 0 ? formatMs(r.importedAtMs, zone) : "",
                    r.notes);
        }
        return sb.toString();
    }

    /**
     * One row per attempt, oldest first. Climb name/route/length/gain come from the first
     * route that contains the attempt's climb; attempts whose climb no longer exists in any
     * route are still exported, with those columns left empty.
     */
    public static String attemptsCsv(List<StoredClimbAttempt> attempts,
                                     List<StoredRoute> routes, ZoneId zone) {
        Map<String, ClimbInfo> climbs = indexClimbs(routes);
        List<StoredClimbAttempt> sorted = new ArrayList<>(attempts);
        sorted.sort(Comparator.comparingLong((StoredClimbAttempt a) -> a.dateEpochSec)
                .thenComparingInt(a -> a.passIndex));

        StringBuilder sb = new StringBuilder();
        row(sb, "datum", "klim", "route", "klim_id", "tijd_sec", "tijd", "lengte_m",
                "hoogtemeters", "gem_snelheid_kmh", "vam_m_per_uur", "doorgang",
                "afwijkend_gereden", "activiteit_id", "notitie");
        for (StoredClimbAttempt a : sorted) {
            ClimbInfo c = climbs.get(a.climbId);
            boolean timed = a.elapsedSec > 0;
            row(sb,
                    a.dateEpochSec > 0 ? formatMs(a.dateEpochSec * 1000L, zone) : "",
                    c != null ? c.name : "",
                    c != null ? c.routeName : "",
                    a.climbId,
                    String.valueOf(a.elapsedSec),
                    timed ? hms(a.elapsedSec) : "",
                    c != null ? String.valueOf(c.lengthM) : "",
                    c != null ? String.valueOf(c.gainM) : "",
                    c != null && timed ? oneDecimal(c.lengthM / (double) a.elapsedSec * 3.6) : "",
                    c != null && timed ? String.valueOf(Math.round(c.gainM * 3600.0 / a.elapsedSec)) : "",
                    String.valueOf(a.passIndex + 1),
                    a.routeDeviation ? "ja" : "nee",
                    String.valueOf(a.activityId),
                    a.note);
        }
        return sb.toString();
    }

    /** Quotes a field when it contains a separator, quote or line break; null becomes empty. */
    static String escape(String field) {
        if (field == null) return "";
        boolean needsQuotes = field.indexOf(',') >= 0 || field.indexOf('"') >= 0
                || field.indexOf('\n') >= 0 || field.indexOf('\r') >= 0;
        if (!needsQuotes) return field;
        return '"' + field.replace("\"", "\"\"") + '"';
    }

    private static void row(StringBuilder sb, String... fields) {
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(escape(fields[i]));
        }
        sb.append(EOL);
    }

    private static final class ClimbInfo {
        final String name;
        final String routeName;
        final int lengthM;
        final int gainM;

        ClimbInfo(String name, String routeName, int lengthM, int gainM) {
            this.name = name;
            this.routeName = routeName;
            this.lengthM = lengthM;
            this.gainM = gainM;
        }
    }

    private static Map<String, ClimbInfo> indexClimbs(List<StoredRoute> routes) {
        Map<String, ClimbInfo> byId = new HashMap<>();
        for (StoredRoute r : routes) {
            if (r.climbs == null) continue;
            String routeName = displayName(r.userDisplayName, r.name);
            for (StoredClimb c : r.climbs) {
                byId.putIfAbsent(ClimbIdentity.of(c), new ClimbInfo(
                        displayName(c.userDisplayName, c.name), routeName,
                        ClimbIdentity.effectiveLength(c), c.elevationGain));
            }
        }
        return byId;
    }

    private static String displayName(String userName, String name) {
        return userName != null && !userName.isEmpty() ? userName : name;
    }

    private static double routeLengthM(StoredRoute r) {
        return r.distances != null && r.distances.length > 0
                ? r.distances[r.distances.length - 1] : 0;
    }

    private static int climbGain(StoredRoute r) {
        int sum = 0;
        if (r.climbs != null) {
            for (StoredClimb c : r.climbs) sum += Math.max(0, c.elevationGain);
        }
        return sum;
    }

    private static String km(double metres) {
        return String.format(Locale.US, "%.2f", metres / 1000.0);
    }

    private static String oneDecimal(double v) {
        return String.format(Locale.US, "%.1f", v);
    }

    private static String hms(int sec) {
        return String.format(Locale.US, "%d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60);
    }

    private static String formatMs(long epochMs, ZoneId zone) {
        return DATE_TIME.format(Instant.ofEpochMilli(epochMs).atZone(zone));
    }
}
