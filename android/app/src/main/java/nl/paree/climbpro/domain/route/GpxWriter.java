package nl.paree.climbpro.domain.route;

import java.util.Locale;

import nl.paree.climbpro.data.route.StoredClimb;
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
        return toGpx(route, false);
    }

    /**
     * @param climbWaypoints also write a {@code <wpt>} at the start and top of every climb
     *                       (name, length, gradient, gain) — used for the Wahoo/Hammerhead
     *                       export (issue #254), whose units show GPX waypoints as POIs
     *                       along the route. Garmin's handoff keeps the plain track.
     */
    public static String toGpx(StoredRoute route, boolean climbWaypoints) {
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
        if (climbWaypoints) appendClimbWaypoints(sb, route, n);
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

    private static void appendClimbWaypoints(StringBuilder sb, StoredRoute route, int n) {
        if (route.climbs == null) return;
        for (int ci = 0; ci < route.climbs.size(); ci++) {
            StoredClimb c = route.climbs.get(ci);
            String name = c.userDisplayName != null && !c.userDisplayName.isEmpty()
                    ? c.userDisplayName
                    : c.name != null && !c.name.isEmpty() ? c.name : "Klim " + (ci + 1);
            int length = c.length > 0 ? c.length : c.endDistance - c.startDistance;
            String desc = String.format(Locale.US, "%.1f km, %.1f%%, %d hm",
                    length / 1000.0, c.avgGradient * 100, c.elevationGain);
            double[] start = positionAt(route, n, c.startDistance);
            if (start == null) start = new double[]{c.startLat, c.startLon};
            appendWpt(sb, start, "Start " + name, desc, "Summit");
            double[] top = positionAt(route, n, c.endDistance);
            if (top != null) appendWpt(sb, top, "Top " + name, desc, "Summit");
        }
    }

    private static void appendWpt(StringBuilder sb, double[] pos, String name, String desc,
                                  String sym) {
        sb.append("  <wpt lat=\"").append(fmt(pos[0])).append("\" lon=\"").append(fmt(pos[1]))
          .append("\">\n");
        sb.append("    <name>").append(escape(name)).append("</name>\n");
        sb.append("    <desc>").append(escape(desc)).append("</desc>\n");
        sb.append("    <sym>").append(sym).append("</sym>\n");
        sb.append("  </wpt>\n");
    }

    /** Route position at {@code distance} m, linearly interpolated; null without distances. */
    private static double[] positionAt(StoredRoute route, int n, double distance) {
        double[] d = route.distances;
        if (d == null || d.length < n || n == 0) return null;
        if (distance <= d[0]) return new double[]{route.lats[0], route.lons[0]};
        for (int i = 1; i < n; i++) {
            if (d[i] >= distance) {
                double span = d[i] - d[i - 1];
                double t = span > 0 ? (distance - d[i - 1]) / span : 0;
                return new double[]{
                        route.lats[i - 1] + t * (route.lats[i] - route.lats[i - 1]),
                        route.lons[i - 1] + t * (route.lons[i] - route.lons[i - 1])};
            }
        }
        return new double[]{route.lats[n - 1], route.lons[n - 1]};
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
