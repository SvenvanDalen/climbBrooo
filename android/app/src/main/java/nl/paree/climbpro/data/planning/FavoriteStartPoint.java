package nl.paree.climbpro.data.planning;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A saved start point for planning (issue #206 — favoriete startpunten): home, work, a
 * parking spot. Phone-only; never sent to the watch.
 * Layout: getFilesDir()/favorite_start_points.json — a flat array of these, see
 * {@link FavoriteStartPointStore}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class FavoriteStartPoint {

    public String id;         // UUID, stable identity for rename/delete
    public String name;
    public double lat;
    public double lon;
    public long   createdAtMs;

    public FavoriteStartPoint() {}

    public FavoriteStartPoint(String id, String name, double lat, double lon, long createdAtMs) {
        this.id = id;
        this.name = name;
        this.lat = lat;
        this.lon = lon;
        this.createdAtMs = createdAtMs;
    }

    public static boolean isValidCoordinate(double lat, double lon) {
        return !Double.isNaN(lat) && !Double.isNaN(lon)
                && lat >= -90.0 && lat <= 90.0
                && lon >= -180.0 && lon <= 180.0;
    }

    /**
     * Parses a typed "lat, lon" pair. Accepts {@code 50.85, 5.69}, {@code 50.85 5.69} and the
     * Dutch decimal-comma form {@code 50,85; 5,69}. Returns {@code {lat, lon}}, or null when
     * the text is not exactly two numbers or lies outside the valid range.
     */
    public static double[] parseCoordinates(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.isEmpty()) return null;
        String[] parts;
        if (t.contains(";")) {
            parts = t.replace(',', '.').split("\\s*;\\s*");
        } else {
            parts = t.split("\\s*,\\s*|\\s+");
        }
        if (parts.length != 2) return null;
        double lat;
        double lon;
        try {
            lat = Double.parseDouble(parts[0].trim());
            lon = Double.parseDouble(parts[1].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        return isValidCoordinate(lat, lon) ? new double[]{lat, lon} : null;
    }
}
