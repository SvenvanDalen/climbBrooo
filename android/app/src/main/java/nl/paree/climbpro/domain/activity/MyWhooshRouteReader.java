package nl.paree.climbpro.domain.activity;

import nl.paree.climbpro.domain.climb.Climb;
import nl.paree.climbpro.domain.climb.ClimbDetector;
import nl.paree.climbpro.domain.route.CumulativeDistance;
import nl.paree.climbpro.domain.route.ElevationSmoother;
import nl.paree.climbpro.domain.route.GpxParseException;
import nl.paree.climbpro.domain.route.GpxParser;
import nl.paree.climbpro.domain.route.RoutePoint;
import nl.paree.climbpro.domain.route.RouteSimplifier;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns a ride exported from MyWhoosh (issue #342) into route points for the normal climb
 * pipeline. MyWhoosh offers a ride as FIT (also what Strava / Garmin Connect "export
 * original" return); a GPX export is accepted too. The automatic Strava import (issue #344)
 * feeds the activity's streams in through {@link #fromStreams}.
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

    /** Strava's sport type for rides on a trainer app. */
    private static final String VIRTUAL_RIDE = "VirtualRide";

    /** Smoothing window of the GPX import, reused so both imports detect the same climbs. */
    private static final int SMOOTHING_WINDOW = 5;

    /** Parsed route plus whether its coordinates are synthetic. */
    public static final class Result {
        public final List<RoutePoint> points;
        public final boolean virtual;

        Result(List<RoutePoint> points, boolean virtual) {
            this.points = points;
            this.virtual = virtual;
        }
    }

    /** Simplified route points and the climbs detected on them. */
    public static final class Detected {
        public final List<RoutePoint> points;
        public final List<Climb> climbs;

        Detected(List<RoutePoint> points, List<Climb> climbs) {
            this.points = points;
            this.climbs = climbs;
        }
    }

    private MyWhooshRouteReader() {}

    /**
     * MyWhoosh uploads to Strava as a {@code VirtualRide} named "MyWhoosh - &lt;route&gt;".
     * The name is the only marker in the activity list; a ride the user renamed isn't
     * recognised, which errs on the side of not importing Zwift or other trainer rides.
     */
    public static boolean isMyWhooshActivity(String name, String type, String sportType) {
        boolean virtualRide = VIRTUAL_RIDE.equals(sportType)
                || (sportType == null && VIRTUAL_RIDE.equals(type));
        return virtualRide && name != null
                && name.trim().toLowerCase(Locale.ROOT).startsWith("mywhoosh");
    }

    /** "MyWhoosh - Hautacam Summit" -> "Hautacam Summit"; falls back to the full name. */
    public static String routeTitle(String activityName) {
        if (activityName == null) return "rit";
        String name = activityName.trim();
        String rest = name.replaceFirst("(?i)^mywhoosh[\\s\\-–—:|]*", "").trim();
        return rest.isEmpty() ? name : rest;
    }

    /**
     * Streams of a Strava activity, index-aligned. {@code lat}/{@code lon} may be null (no GPS
     * stream); a NaN entry is a sample without a value.
     */
    public static Result fromStreams(double[] lat, double[] lon, double[] altitude,
                                     double[] distance) throws IOException {
        if (altitude == null || distance == null) {
            throw new IOException("Geen hoogte- of afstandsdata");
        }
        int n = Math.min(altitude.length, distance.length);
        boolean hasGps = lat != null && lon != null && lat.length >= n && lon.length >= n;
        List<FitTrackDecoder.Record> records = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            records.add(new FitTrackDecoder.Record(
                    hasGps ? lat[i] : Double.NaN, hasGps ? lon[i] : Double.NaN,
                    altitude[i], distance[i], -1));
        }
        return fromRecords(records);
    }

    /** The GPX import's pipeline: cumulative distance, smoothing, simplification, detection. */
    public static Detected detectClimbs(Result read) {
        List<RoutePoint> smoothed = ElevationSmoother.smooth(
                CumulativeDistance.compute(read.points), SMOOTHING_WINDOW);
        List<RoutePoint> simple = simplify(smoothed, read.virtual);
        return new Detected(simple, ClimbDetector.detect(simple));
    }

    public static Result read(byte[] data) throws IOException {
        if (FitTrackDecoder.looksLikeFit(data)) return fromRecords(FitTrackDecoder.decodeRecords(data));
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

    static Result fromRecords(List<FitTrackDecoder.Record> records) throws IOException {
        List<FitTrackDecoder.Record> withAltitude = new ArrayList<>();
        int positioned = 0;
        for (FitTrackDecoder.Record r : records) {
            if (Double.isNaN(r.altitudeM)) continue;
            withAltitude.add(r);
            if (r.hasPosition()) positioned++;
        }
        if (withAltitude.size() < 2) {
            throw new IOException("Geen hoogtedata in deze rit");
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
            throw new IOException("Geen GPS- of afstandsdata in deze rit");
        }
        return new Result(out, true);
    }
}
