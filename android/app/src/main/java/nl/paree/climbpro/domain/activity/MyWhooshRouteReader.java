package nl.paree.climbpro.domain.activity;

import nl.paree.climbpro.domain.route.GpxParseException;
import nl.paree.climbpro.domain.route.GpxParser;
import nl.paree.climbpro.domain.route.RoutePoint;
import nl.paree.climbpro.domain.route.RouteSimplifier;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a ride exported from MyWhoosh (issue #342) into route points for the normal climb
 * pipeline. MyWhoosh offers a ride as FIT (also what Strava / Garmin Connect "export
 * original" return); a GPX export is accepted too.
 *
 * <p>A virtual ride may carry no GPS positions, only distance and altitude per record. The
 * profile is then laid out on a straight line due north from 0,0 — one degree of latitude
 * per {@link #METERS_PER_DEGREE_LAT} m — so the pipeline's haversine distances reproduce the
 * ridden distance. Such a route is flagged {@link Result#virtual}: fine for viewing the
 * climbs, meaningless for radius mode or navigation. Pure — no Android.
 */
public final class MyWhooshRouteReader {

    /** Meters per degree of latitude on the haversine sphere (R = 6371 km). */
    static final double METERS_PER_DEGREE_LAT = 6_371_000.0 * Math.PI / 180.0;

    /** Spacing kept when thinning a virtual ride; MyWhoosh records about every 5-12 m. */
    static final double VIRTUAL_MIN_SPACING_M = 10.0;

    /** Same epsilon as the GPX import. */
    private static final double SIMPLIFY_EPSILON_M = 5.0;

    /** A ride counts as positioned when at least this share of its altitude records has GPS. */
    private static final double MIN_POSITIONED_SHARE = 0.5;

    /** Parsed route plus whether its coordinates are synthetic. */
    public static final class Result {
        public final List<RoutePoint> points;
        public final boolean virtual;

        Result(List<RoutePoint> points, boolean virtual) {
            this.points = points;
            this.virtual = virtual;
        }
    }

    private MyWhooshRouteReader() {}

    public static Result read(byte[] data) throws IOException {
        if (FitTrackDecoder.looksLikeFit(data)) return fromFit(FitTrackDecoder.decodeRecords(data));
        try {
            return new Result(GpxParser.parse(new ByteArrayInputStream(data)), false);
        } catch (GpxParseException e) {
            throw new IOException("Geen FIT- of GPX-bestand: " + e.getMessage(), e);
        }
    }

    /**
     * Geometry simplification for the climb pipeline. A GPS ride goes through the regular
     * Douglas-Peucker simplifier; a virtual ride can't — that simplifier only looks at
     * lat/lon, so the straight synthetic line would collapse to its two endpoints and lose
     * the whole elevation profile. It is thinned by distance instead.
     *
     * @param smoothed points with cumulative distance and smoothed elevation
     */
    public static List<RoutePoint> simplify(List<RoutePoint> smoothed, boolean virtual) {
        if (!virtual) return RouteSimplifier.simplify(smoothed, SIMPLIFY_EPSILON_M);
        List<RoutePoint> out = new ArrayList<>();
        for (int i = 0; i < smoothed.size(); i++) {
            RoutePoint p = smoothed.get(i);
            boolean last = i == smoothed.size() - 1;
            if (out.isEmpty() || last
                    || p.distance - out.get(out.size() - 1).distance >= VIRTUAL_MIN_SPACING_M) {
                out.add(p);
            }
        }
        return out;
    }

    static Result fromFit(List<FitTrackDecoder.Record> records) throws IOException {
        List<FitTrackDecoder.Record> withAltitude = new ArrayList<>();
        int positioned = 0;
        for (FitTrackDecoder.Record r : records) {
            if (Double.isNaN(r.altitudeM)) continue;
            withAltitude.add(r);
            if (r.hasPosition()) positioned++;
        }
        if (withAltitude.size() < 2) {
            throw new IOException("Geen hoogtedata in dit FIT-bestand");
        }

        List<RoutePoint> out = new ArrayList<>();
        if (positioned >= withAltitude.size() * MIN_POSITIONED_SHARE) {
            for (FitTrackDecoder.Record r : withAltitude) {
                if (r.hasPosition()) out.add(new RoutePoint(r.lat, r.lon, r.altitudeM, 0));
            }
            return new Result(out, false);
        }

        double lastDistance = -1;
        for (FitTrackDecoder.Record r : withAltitude) {
            if (Double.isNaN(r.distanceM) || r.distanceM <= lastDistance) continue; // standing still
            lastDistance = r.distanceM;
            out.add(new RoutePoint(r.distanceM / METERS_PER_DEGREE_LAT, 0, r.altitudeM, 0));
        }
        if (out.size() < 2) {
            throw new IOException("Geen GPS- of afstandsdata in dit FIT-bestand");
        }
        return new Result(out, true);
    }
}
