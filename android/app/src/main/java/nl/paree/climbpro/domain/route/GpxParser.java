package nl.paree.climbpro.domain.route;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a GPX file and returns raw RoutePoints (distances set to 0; call
 * {@link CumulativeDistance#compute} afterwards).
 *
 * Handles both {@code <trkpt>} (track) and {@code <rtept>} (route) elements.
 * Missing {@code <ele>} is represented as {@link Double#NaN}.
 * Throws {@link GpxParseException} on malformed input.
 */
public final class GpxParser {

    private GpxParser() {}

    private static boolean isPointTag(String tag) {
        return "trkpt".equals(tag) || "rtept".equals(tag) || "wpt".equals(tag);
    }

    /** Collects track/route points and waypoints while the pull parser walks the file. */
    private static final class PointCollector {
        final List<RoutePoint> points = new ArrayList<>();
        // Waypoints are only route geometry in a waypoint-only file; next to a track or
        // route they are POIs (e.g. the climb markers of our own Wahoo/Hammerhead export,
        // issue #254) and must not be spliced into the line.
        final List<RoutePoint> waypoints = new ArrayList<>();
        boolean inWaypoint = false;
        double lat = Double.NaN;
        double lon = Double.NaN;
        double ele = Double.NaN;
        boolean inPoint = false;
        boolean inEle = false;

        void startTag(XmlPullParser xpp) {
            String tag = xpp.getName();
            if (isPointTag(tag)) {
                inPoint = true;
                inWaypoint = "wpt".equals(tag);
                lat = parseAttr(xpp, "lat");
                lon = parseAttr(xpp, "lon");
                ele = Double.NaN;
            } else if (inPoint && "ele".equals(tag)) {
                inEle = true;
            }
        }

        void text(XmlPullParser xpp) {
            if (!inEle) return;
            try {
                ele = Double.parseDouble(xpp.getText().trim());
            } catch (NumberFormatException e) {
                ele = Double.NaN;
            }
        }

        void endTag(String tag) {
            if (isPointTag(tag)) {
                if (!Double.isNaN(lat) && !Double.isNaN(lon)) {
                    (inWaypoint ? waypoints : points).add(new RoutePoint(lat, lon, ele, 0.0));
                }
                inPoint = false;
                inEle = false;
                lat = lon = ele = Double.NaN;
            } else if ("ele".equals(tag)) {
                inEle = false;
            }
        }
    }

    public static List<RoutePoint> parse(InputStream in) throws GpxParseException {
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(false);
            XmlPullParser xpp = factory.newPullParser();
            xpp.setInput(in, null);

            PointCollector c = new PointCollector();
            int event = xpp.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                switch (event) {
                    case XmlPullParser.START_TAG:
                        c.startTag(xpp);
                        break;
                    case XmlPullParser.TEXT:
                        c.text(xpp);
                        break;
                    case XmlPullParser.END_TAG:
                        c.endTag(xpp.getName());
                        break;
                    default:
                        break;
                }
                event = xpp.next();
            }

            List<RoutePoint> points = c.points;
            if (points.isEmpty()) points = c.waypoints;
            if (points.isEmpty()) {
                throw new GpxParseException("No track points found in GPX file");
            }
            return points;
        } catch (XmlPullParserException e) {
            throw new GpxParseException("Malformed GPX XML: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new GpxParseException("I/O error reading GPX: " + e.getMessage(), e);
        }
    }

    private static double parseAttr(XmlPullParser xpp, String name) {
        String v = xpp.getAttributeValue(null, name);
        if (v == null) return Double.NaN;
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
