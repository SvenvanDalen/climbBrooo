package nl.paree.climbpro.domain.route;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Komoot or RideWithGPS share link (issue #210), recognised in free text such as a pasted
 * link or the text another app shares with ClimbPro. Pure: no network, no Android types.
 *
 * <p>Supported forms:
 * <ul>
 *   <li>{@code https://www.komoot.com/tour/123}, also {@code komoot.de}/{@code komoot.nl},
 *       a locale prefix ({@code /nl-nl/tour/123}) and {@code ?share_token=...} for tours
 *       shared privately;</li>
 *   <li>{@code https://ridewithgps.com/routes/123} and {@code /trips/123}.</li>
 * </ul>
 * Downloads use keyless public endpoints: Komoot's {@code api.komoot.de/v007} JSON and
 * RideWithGPS's GPX export.
 */
public final class ShareLink {

    public enum Provider { KOMOOT, RIDE_WITH_GPS }

    private static final Pattern KOMOOT_HOST = Pattern.compile("(^|\\.)komoot\\.[a-z]{2,3}$");
    private static final Pattern RWGPS_HOST = Pattern.compile("(^|\\.)ridewithgps\\.com$");
    private static final Pattern KOMOOT_PATH = Pattern.compile("/tour/(\\d{1,19})(/|$)");
    private static final Pattern RWGPS_PATH = Pattern.compile("^/(routes|trips)/(\\d{1,19})(/|\\.|$)");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{1,200}");

    public final Provider provider;
    /** Numeric id as it appears in the link. */
    public final String id;
    /** RideWithGPS only: true for a recorded trip, false for a planned route. */
    public final boolean trip;
    /** Komoot only: the {@code share_token} of a privately shared tour, or null. */
    public final String shareToken;

    private ShareLink(Provider provider, String id, boolean trip, String shareToken) {
        this.provider = provider;
        this.id = id;
        this.trip = trip;
        this.shareToken = shareToken;
    }

    /**
     * Returns the first Komoot/RideWithGPS link found in {@code text}, or null when there is
     * none (also for other sites and for links that do not point at a single tour/route).
     */
    public static ShareLink find(String text) {
        if (text == null) return null;
        for (String token : text.split("\\s+")) {
            ShareLink link = parseToken(token);
            if (link != null) return link;
        }
        return null;
    }

    private static ShareLink parseToken(String token) {
        String t = token.trim();
        // Strip wrapping punctuation from chat messages: "(https://...)" or "...123."
        t = t.replaceAll("^[<(\\[\"']+", "").replaceAll("[>)\\]\"',.!?]+$", "");
        if (t.isEmpty()) return null;
        String lower = t.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            if (!lower.contains("komoot.") && !lower.contains("ridewithgps.com")) return null;
            t = "https://" + t;
        }
        URI uri;
        try {
            uri = new URI(t);
        } catch (URISyntaxException e) {
            return null;
        }
        String host = uri.getHost();
        String path = uri.getPath();
        if (host == null || path == null) return null;
        host = host.toLowerCase(Locale.ROOT);

        if (KOMOOT_HOST.matcher(host).find()) {
            Matcher m = KOMOOT_PATH.matcher(path);
            if (!m.find()) return null;
            return new ShareLink(Provider.KOMOOT, m.group(1), false,
                    queryParam(uri.getRawQuery(), "share_token"));
        }
        if (RWGPS_HOST.matcher(host).find()) {
            Matcher m = RWGPS_PATH.matcher(path);
            if (!m.find()) return null;
            return new ShareLink(Provider.RIDE_WITH_GPS, m.group(2), "trips".equals(m.group(1)),
                    null);
        }
        return null;
    }

    private static String queryParam(String rawQuery, String name) {
        if (rawQuery == null) return null;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                String value = pair.substring(eq + 1);
                return TOKEN.matcher(value).matches() ? value : null;
            }
        }
        return null;
    }

    /** RideWithGPS GPX export as a track (full geometry with elevation). */
    public String gpxUrl() {
        return "https://ridewithgps.com/" + (trip ? "trips/" : "routes/") + id
                + ".gpx?sub_format=track";
    }

    /** Komoot tour metadata (name, distance, ...). */
    public String komootTourUrl() {
        return "https://api.komoot.de/v007/tours/" + id + tokenQuery();
    }

    /** Komoot tour geometry: {@code {"items":[{"lat":..,"lng":..,"alt":..}, ...]}}. */
    public String komootCoordinatesUrl() {
        return "https://api.komoot.de/v007/tours/" + id + "/coordinates" + tokenQuery();
    }

    private String tokenQuery() {
        if (shareToken == null) return "";
        try {
            return "?share_token=" + URLEncoder.encode(shareToken, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }

    /** Fallback route name when the provider does not return one. */
    public String defaultName() {
        return provider == Provider.KOMOOT ? "Komoot-tour " + id
                : (trip ? "RideWithGPS-rit " : "RideWithGPS-route ") + id;
    }
}
