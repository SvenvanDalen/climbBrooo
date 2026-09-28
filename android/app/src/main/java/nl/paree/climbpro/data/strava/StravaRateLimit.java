package nl.paree.climbpro.data.strava;

/**
 * Reads Strava's rate-limit headers ({@code "<15-min>,<daily>"}, e.g. {@code "100,1000"}) so a
 * long-running job such as the history backfill (issue #312) can stop before it exhausts the
 * budget and leaves the regular incremental sync with nothing. Prefers the read-specific
 * {@code X-ReadRateLimit-*} headers and falls back to the overall {@code X-RateLimit-*} ones.
 */
public final class StravaRateLimit {

    /** Requests left untouched in the current 15-minute window for the regular sync. */
    static final int RESERVE_15MIN = 10;
    /** Requests left untouched for the rest of the day for the regular sync. */
    static final int RESERVE_DAILY = 100;

    private StravaRateLimit() {}

    /** @return true when either window has reached its limit minus the reserve. */
    public static boolean nearLimit(okhttp3.Headers headers) {
        if (headers == null) return false;
        String limit = headers.get("X-ReadRateLimit-Limit");
        String usage = headers.get("X-ReadRateLimit-Usage");
        if (limit == null || usage == null) {
            limit = headers.get("X-RateLimit-Limit");
            usage = headers.get("X-RateLimit-Usage");
        }
        return nearLimit(limit, usage);
    }

    /** Header values as sent by Strava; missing or malformed values never pause the caller. */
    static boolean nearLimit(String limitHeader, String usageHeader) {
        int[] limit = parse(limitHeader);
        int[] usage = parse(usageHeader);
        if (limit == null || usage == null) return false;
        return usage[0] >= limit[0] - RESERVE_15MIN || usage[1] >= limit[1] - RESERVE_DAILY;
    }

    private static int[] parse(String header) {
        if (header == null) return null;
        String[] parts = header.split(",");
        if (parts.length < 2) return null;
        try {
            return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
