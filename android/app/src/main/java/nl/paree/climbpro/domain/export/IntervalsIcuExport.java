package nl.paree.climbpro.domain.export;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Locale;

/**
 * Pure helpers for pushing a climb workout to intervals.icu as a planned workout (issue #78).
 * intervals.icu authenticates a personal API key with HTTP Basic auth (user {@code API_KEY},
 * password = the key); athlete id {@code 0} means "the athlete the key belongs to". The
 * request itself is built in {@code data.intervals.IntervalsIcuEventDto}.
 *
 * <p>intervals.icu has no custom-climb object, so the climb's PR and attempt count travel as
 * the planned workout's description.
 */
public final class IntervalsIcuExport {

    /** Athlete id that resolves to the owner of the API key. */
    public static final String OWN_ATHLETE_ID = "0";

    private IntervalsIcuExport() {}

    /**
     * Canonical athlete id: blank/{@code 0} → {@code 0}, {@code i123}/{@code I123}/{@code 123}
     * → {@code i123}; anything else (it becomes a URL path segment) → null.
     */
    public static String normalizeAthleteId(String raw) {
        if (raw == null) return OWN_ATHLETE_ID;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.equals(OWN_ATHLETE_ID)) return OWN_ATHLETE_ID;
        if (s.startsWith("i")) s = s.substring(1);
        if (!s.matches("[0-9]{1,12}")) return null;
        return "i" + s;
    }

    /** A usable key is non-blank and has no inner whitespace (a pasted key often has edges). */
    public static boolean isValidApiKey(String key) {
        if (key == null) return false;
        String k = key.trim();
        return !k.isEmpty() && !k.matches(".*\\s.*");
    }

    public static String basicAuthHeader(String apiKey) {
        String creds = "API_KEY:" + apiKey.trim();
        return "Basic " + Base64.getEncoder().encodeToString(creds.getBytes(StandardCharsets.UTF_8));
    }

    /** intervals.icu wants a local date-time; a planned workout sits at the start of the day. */
    public static String startDateLocal(LocalDate date) {
        return date.toString() + "T00:00:00";
    }

    /** Indoor on the trainer is a {@code VirtualRide}; outdoors a plain {@code Ride}. */
    public static String activityType(boolean indoor) {
        return indoor ? "VirtualRide" : "Ride";
    }

    public static String eventName(String climbName, int repeats) {
        return repeats > 1 ? repeats + "× " + climbName : climbName;
    }

    /**
     * Workout description carrying the climb's history (issue #78: intervals.icu has no climb
     * object). {@code prSec <= 0} or {@code attemptCount <= 0} means no attempts yet.
     */
    public static String description(String climbName, int repeats, int prSec, int attemptCount) {
        StringBuilder sb = new StringBuilder("ClimbPro-workout: ");
        if (repeats > 1) sb.append(repeats).append("× ");
        sb.append(climbName).append(", vermogen per segment volgt de helling.");
        if (prSec > 0 && attemptCount > 0) {
            sb.append("\nPR: ").append(formatDuration(prSec))
              .append(" (").append(attemptCount)
              .append(attemptCount == 1 ? " poging" : " pogingen").append(")");
        } else {
            sb.append("\nNog geen pogingen op deze klim.");
        }
        return sb.toString();
    }

    /** User-facing (Dutch) message for a failed HTTP call. */
    public static String errorMessage(int httpCode) {
        switch (httpCode) {
            case 401:
            case 403:
                return "intervals.icu weigert de API-sleutel; controleer hem in Instellingen.";
            case 404:
                return "Atleet-id niet gevonden bij intervals.icu; laat hem leeg of vul 0 in "
                        + "voor je eigen account.";
            case 429:
                return "Te veel verzoeken aan intervals.icu; probeer het later opnieuw.";
            default:
                return "intervals.icu gaf een fout (HTTP " + httpCode + ").";
        }
    }

    static String formatDuration(int totalSec) {
        int h = totalSec / 3600;
        int m = (totalSec % 3600) / 60;
        int s = totalSec % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
                : String.format(Locale.ROOT, "%d:%02d", m, s);
    }
}
