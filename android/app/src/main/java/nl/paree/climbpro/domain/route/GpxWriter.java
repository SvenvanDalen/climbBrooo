package nl.paree.climbpro.domain.route;

import java.util.Locale;

import nl.paree.climbpro.data.route.StoredRoute;

/**
 * Serializes a stored route's raw geometry to a GPX 1.1 track that Garmin Connect can
 * import as a navigable course (the navigation-handoff). Counterpart to {@link GpxParser}:
 * {@code parse(toGpx(route))} round-trips the coordinates.
 *
 * <p>Coordinates are formatted with {@link Locale#US} so the decimal separator is always a
 * dot, regardless of the device locale. A missing (NaN) elevation is written as no
 * {@code <ele>} element rather than a garbage value.
 */
public final class GpxWriter {

    private GpxWriter() {}

    public static String toGpx(StoredRoute route) {
        if (route == null || route.lats == null || route.lons == null || route.lats.length == 0) {
            throw new IllegalArgumentException("route has no geometry to write as GPX");
        }
        double[] lats = route.lats;
        double[] lons = route.lons;
        double[] eles = route.elevations;
        int n = Math.min(lats.length, lons.length);

        String name = route.userDisplayName != null ? route.userDisplayName
                : route.name != null ? route.name
                : route.routeId != null ? route.routeId : "ClimbPro route";

        StringBuilder sb = new StringBuilder(256 + n * 64);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<gpx version=\"1.1\" creator=\"ClimbPro\" "
                + "xmlns=\"http://www.topografix.com/GPX/1/1\">\n");
        sb.append("  <trk>\n");
        sb.append("    <name>").append(escape(name)).append("</name>\n");
        sb.append("    <trkseg>\n");
        for (int i = 0; i < n; i++) {
            sb.append("      <trkpt lat=\"").append(fmt(lats[i]))
              .append("\" lon=\"").append(fmt(lons[i])).append("\">");
            if (eles != null && i < eles.length && !Double.isNaN(eles[i])) {
                sb.append("<ele>").append(fmtEle(eles[i])).append("</ele>");
            }
            sb.append("</trkpt>\n");
        }
        sb.append("    </trkseg>\n");
        sb.append("  </trk>\n");
        sb.append("</gpx>\n");
        return sb.toString();
    }

    private static String fmt(double coord) {
        return String.format(Locale.US, "%.7f", coord);
    }

    private static String fmtEle(double ele) {
        return String.format(Locale.US, "%.1f", ele);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
