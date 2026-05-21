package nl.paree.climbpro.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.protocol.Climb;
import nl.paree.climbpro.protocol.ClimbPayload;
import nl.paree.climbpro.protocol.Segment;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts a {@link StoredRoute} + list of {@link StoredClimb}s into the wire-format
 * {@link ClimbPayload} and serialises it to JSON bytes.
 *
 * Encoding rules:
 *   - distances in metres, integers
 *   - gradients as fixed-point: percent × 10, rounded half-away-from-zero
 *   - color indices 0–5
 */
public final class ClimbPayloadBuilder {

    private static final int SCHEMA_VERSION = 1;
    private final ObjectMapper mapper;

    public ClimbPayloadBuilder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Build a route-follow payload.
     */
    public byte[] buildRoutePayload(StoredRoute route) throws IOException {
        ClimbPayload payload = new ClimbPayload();
        payload.setV(SCHEMA_VERSION);
        payload.setMode(ClimbPayload.Mode.ROUTE);
        payload.setRouteId(route.routeId);

        String displayName = route.userDisplayName != null ? route.userDisplayName : route.name;
        if (displayName != null && displayName.length() <= 32) {
            payload.setName(displayName);
        }

        payload.setClimbs(convertClimbs(route.climbs, false));
        return serialise(payload);
    }

    /**
     * Build a radius-mode payload from a cross-route list of climbs.
     */
    public byte[] buildRadiusPayload(List<StoredClimb> climbs) throws IOException {
        ClimbPayload payload = new ClimbPayload();
        payload.setV(SCHEMA_VERSION);
        payload.setMode(ClimbPayload.Mode.RADIUS);
        payload.setClimbs(convertClimbs(climbs, true));
        return serialise(payload);
    }

    // -------------------------------------------------------------------------

    private List<Climb> convertClimbs(List<StoredClimb> source, boolean radiusMode) {
        if (source == null) return new ArrayList<>();
        List<Climb> out = new ArrayList<>(source.size());
        for (StoredClimb sc : source) {
            Climb c = new Climb();
            c.setLength(sc.length);
            c.setElevationGain(sc.elevationGain);
            c.setAvgGradient(toFixedPoint(sc.avgGradient));

            if (!radiusMode) {
                c.setStartDistance(sc.startDistance);
                c.setEndDistance(sc.endDistance);
            } else {
                if (!Double.isNaN(sc.startLat) && !Double.isNaN(sc.startLon)) {
                    c.setStartLat(sc.startLat);
                    c.setStartLon(sc.startLon);
                }
            }

            String name = sc.userDisplayName != null ? sc.userDisplayName : sc.name;
            if (name != null && name.length() <= 32) c.setName(name);

            c.setSegments(convertSegments(sc.segments));
            out.add(c);
        }
        return out;
    }

    private List<Segment> convertSegments(List<StoredSegment> source) {
        if (source == null) return new ArrayList<>();
        List<Segment> out = new ArrayList<>(source.size());
        for (StoredSegment ss : source) {
            Segment s = new Segment();
            s.setDistance(ss.distance);
            s.setElevationGain(ss.elevationGain);
            s.setGradient(toFixedPoint(ss.gradient));
            s.setColorIndex(ss.colorIndex);
            out.add(s);
        }
        return out;
    }

    /** gradient fraction → fixed-point (percent × 10), rounded half-away-from-zero. */
    private static int toFixedPoint(double gradientFraction) {
        double pct = gradientFraction * 100.0;
        return (int) (pct >= 0 ? Math.floor(pct * 10 + 0.5) : Math.ceil(pct * 10 - 0.5));
    }

    private byte[] serialise(ClimbPayload payload) throws IOException {
        return mapper.writeValueAsBytes(payload);
    }
}
