package nl.paree.climbpro.domain.route;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts Komoot's public tour JSON (issue #210) into route points and a GPX document, so a
 * Komoot tour goes through exactly the same import pipeline as a picked GPX file.
 * Pure: no network, no Android types.
 */
public final class KomootTourConverter {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern GPX_NAME = Pattern.compile("<name>\\s*(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?\\s*</name>",
            Pattern.DOTALL);

    private KomootTourConverter() {}

    /**
     * Parses {@code /v007/tours/<id>/coordinates}. Items without a valid lat/lng are skipped;
     * a missing {@code alt} becomes {@link Double#NaN} (as in {@link GpxParser}).
     *
     * @throws IOException on malformed JSON or when no usable point remains
     */
    public static List<RoutePoint> parseCoordinates(String json) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        JsonNode items = root == null ? null : root.get("items");
        if (items == null || !items.isArray()) {
            throw new IOException("Komoot-antwoord bevat geen coördinaten");
        }
        List<RoutePoint> points = new ArrayList<>();
        for (JsonNode item : items) {
            JsonNode lat = item.get("lat");
            JsonNode lng = item.get("lng");
            if (lat == null || lng == null || !lat.isNumber() || !lng.isNumber()) continue;
            double la = lat.asDouble();
            double lo = lng.asDouble();
            if (la < -90 || la > 90 || lo < -180 || lo > 180) continue;
            JsonNode alt = item.get("alt");
            double ele = alt != null && alt.isNumber() ? alt.asDouble() : Double.NaN;
            points.add(new RoutePoint(la, lo, ele, 0));
        }
        if (points.size() < 2) {
            throw new IOException("Komoot-tour bevat te weinig punten");
        }
        return points;
    }

    /** The {@code name} of a {@code /v007/tours/<id>} response, or null when absent/malformed. */
    public static String parseName(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode name = root == null ? null : root.get("name");
            if (name == null || !name.isTextual()) return null;
            String trimmed = name.asText().trim();
            return trimmed.isEmpty() ? null : trimmed;
        } catch (IOException e) {
            return null;
        }
    }

    /** A minimal GPX 1.1 track of {@code points}, named {@code name}. */
    public static String toGpx(String name, List<RoutePoint> points) {
        StringBuilder sb = new StringBuilder(points.size() * 80 + 256);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
          .append("<gpx version=\"1.1\" creator=\"ClimbPro\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
          .append("<trk><name>").append(escape(name)).append("</name><trkseg>\n");
        for (RoutePoint p : points) {
            sb.append(String.format(Locale.US, "<trkpt lat=\"%.6f\" lon=\"%.6f\">", p.lat, p.lon));
            if (!Double.isNaN(p.elevation)) {
                sb.append(String.format(Locale.US, "<ele>%.1f</ele>", p.elevation));
            }
            sb.append("</trkpt>\n");
        }
        sb.append("</trkseg></trk>\n</gpx>\n");
        return sb.toString();
    }

    /**
     * First {@code <name>} of a GPX document (the route/track title RideWithGPS puts in its
     * export), or null. Cheap regex scan; the geometry is parsed by {@link GpxParser}.
     */
    public static String gpxName(String gpx) {
        if (gpx == null) return null;
        Matcher m = GPX_NAME.matcher(gpx);
        if (!m.find()) return null;
        String name = unescape(m.group(1)).trim();
        return name.isEmpty() ? null : name;
    }

    static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&#39;", "'").replace("&amp;", "&");
    }
}
