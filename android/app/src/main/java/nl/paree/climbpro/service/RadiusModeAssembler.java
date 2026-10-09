package nl.paree.climbpro.service;

import android.util.Log;

import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;

import nl.paree.climbpro.domain.weather.DryClimbFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Assembles a radius-mode climb list: finds all climbs within {@code radiusM} of the
 * given location, sorts closest first, and truncates to fit within the payload budget.
 * Emits a truncation warning when climbs are dropped.
 *
 * <p>Optionally (issue #12) only dry climbs are kept: a {@link DryCheck} looks up the closest
 * {@link DryClimbFilter#MAX_LOCATIONS} starts. Offline-first: when the check fails (no network,
 * rate limit, bad answer) the set is sent unfiltered, never blocked.
 */
public final class RadiusModeAssembler {

    private static final String TAG = "RadiusModeAssembler";

    private final RouteRepository     routeRepo;
    private final ClimbPayloadBuilder builder;
    private boolean truncated;
    private DryCheck dryCheck;
    private int wetDropped;

    /** Dry-or-unknown flag per climb start {lat, lon}, in the given order (issue #12). */
    public interface DryCheck {
        boolean[] dry(List<double[]> starts) throws IOException;
    }

    public RadiusModeAssembler(RouteRepository routeRepo, ClimbPayloadBuilder builder) {
        this.routeRepo = routeRepo;
        this.builder   = builder;
    }

    public boolean wasTruncated() { return truncated; }

    /** Climbs left out because it rains there (issue #12); 0 without a check or offline. */
    public int wetDropped() { return wetDropped; }

    /** Keep only dry climbs (issue #12); null = no weather filter. */
    public RadiusModeAssembler withDryCheck(DryCheck check) {
        this.dryCheck = check;
        return this;
    }

    /**
     * @param lat     current latitude
     * @param lon     current longitude
     * @param radiusM search radius in metres
     * @return serialised radius-mode JSON payload bytes
     */
    public byte[] assemble(double lat, double lon, double radiusM) throws IOException {
        truncated = false;

        List<RouteCatalogEntry> nearbyRoutes = routeRepo.findNearby(lat, lon, radiusM);
        List<ClimbWithDist> candidates = new ArrayList<>();

        for (RouteCatalogEntry entry : nearbyRoutes) {
            StoredRoute route;
            try { route = routeRepo.loadRoute(entry.routeId); }
            catch (IOException e) {
                Log.w(TAG, "Could not load route " + entry.routeId, e);
                continue;
            }
            if (route.climbs == null) continue;
            double[] coords = entry.climbStartCoords;
            for (int i = 0; i < route.climbs.size(); i++) {
                StoredClimb c = route.climbs.get(i);
                double d = (coords != null && (i * 2 + 1) < coords.length)
                        ? haversine(lat, lon, coords[i * 2], coords[i * 2 + 1])
                        : Double.MAX_VALUE;
                if (d <= radiusM) {
                    candidates.add(new ClimbWithDist(c, d));
                }
            }
        }

        candidates.sort(Comparator.comparingDouble(cwd -> cwd.distance));
        candidates = keepDry(candidates);

        // Accumulate climbs until over budget; cache last fitting payload to avoid a rebuild.
        List<StoredClimb> accepted = new ArrayList<>();
        byte[] lastFitting = builder.buildRadiusPayload(accepted);
        for (ClimbWithDist cwd : candidates) {
            accepted.add(cwd.climb);
            byte[] trial = builder.buildRadiusPayload(accepted);
            if (trial.length > PayloadBudget.MAX_BYTES) {
                accepted.remove(accepted.size() - 1);
                truncated = true;
                Log.w(TAG, "Payload budget exceeded — dropping climb " + cwd.climb.name);
                break;
            }
            lastFitting = trial;
        }

        Log.i(TAG, "Radius mode: " + accepted.size() + " climbs"
                + (truncated ? " (truncated)" : ""));
        return lastFitting;
    }

    /** Drops the checked climbs that are wet; any failure of the check keeps them all. */
    private List<ClimbWithDist> keepDry(List<ClimbWithDist> candidates) {
        wetDropped = 0;
        if (dryCheck == null || candidates.isEmpty()) return candidates;
        int n = Math.min(candidates.size(), DryClimbFilter.MAX_LOCATIONS);
        List<double[]> starts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            StoredClimb c = candidates.get(i).climb;
            starts.add(new double[] {c.startLat, c.startLon});
        }
        boolean[] dry;
        try {
            dry = dryCheck.dry(starts);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Weather check failed — radius set sent without weather filter", e);
            return candidates;
        }
        if (dry == null || dry.length != n) return candidates;
        List<ClimbWithDist> out = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            if (i < n && !dry[i]) {
                wetDropped++;
            } else {
                out.add(candidates.get(i));
            }
        }
        return out;
    }

    private static double haversine(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6_371_000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static final class ClimbWithDist {
        final StoredClimb climb;
        final double      distance;
        ClimbWithDist(StoredClimb c, double d) { climb = c; distance = d; }
    }
}
