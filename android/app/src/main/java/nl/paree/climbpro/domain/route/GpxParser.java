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

    public static List<RoutePoint> parse(InputStream in) throws GpxParseException {
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(false);
            XmlPullParser xpp = factory.newPullParser();
            xpp.setInput(in, null);

            List<RoutePoint> points = new ArrayList<>();
            double lat = Double.NaN, lon = Double.NaN, ele = Double.NaN;
            boolean inPoint = false;
            boolean inEle = false;

            int event = xpp.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                switch (event) {
                    case XmlPullParser.START_TAG: {
                        String tag = xpp.getName();
                        if ("trkpt".equals(tag) || "rtept".equals(tag) || "wpt".equals(tag)) {
                            inPoint = true;
                            lat = parseAttr(xpp, "lat");
                            lon = parseAttr(xpp, "lon");
                            ele = Double.NaN;
                        } else if (inPoint && "ele".equals(tag)) {
                            inEle = true;
                        }
                        break;
                    }
                    case XmlPullParser.TEXT: {
                        if (inEle) {
                            try {
                                ele = Double.parseDouble(xpp.getText().trim());
                            } catch (NumberFormatException e) {
                                ele = Double.NaN;
                            }
                        }
                        break;
                    }
                    case XmlPullParser.END_TAG: {
                        String tag = xpp.getName();
                        if ("trkpt".equals(tag) || "rtept".equals(tag) || "wpt".equals(tag)) {
                            if (!Double.isNaN(lat) && !Double.isNaN(lon)) {
                                points.add(new RoutePoint(lat, lon, ele, 0.0));
                            }
                            inPoint = false;
                            inEle = false;
                            lat = lon = ele = Double.NaN;
                        } else if ("ele".equals(tag)) {
                            inEle = false;
                        }
                        break;
                    }
                }
                event = xpp.next();
            }

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
