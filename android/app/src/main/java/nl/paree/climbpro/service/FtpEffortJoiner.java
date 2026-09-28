package nl.paree.climbpro.service;

import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.domain.climb.ClimbIdentity;
import nl.paree.climbpro.domain.power.FtpEstimator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Joins {@link StoredClimbAttempt} rows (elapsed time only) with the segment geometry of
 * the {@link StoredClimb} they belong to (looked up across every stored route by the same
 * {@link ClimbIdentity} key the logbook uses), producing the {@link FtpEstimator.Effort}
 * list the FTP calculator needs. File I/O only — no physics here, that stays in
 * {@link FtpEstimator}.
 */
public final class FtpEffortJoiner {

    private FtpEffortJoiner() {}

    public static List<FtpEstimator.Effort> build(RouteRepository routeRepo,
                                                   List<StoredClimbAttempt> attempts) {
        List<FtpEstimator.Effort> out = new ArrayList<>();
        if (attempts == null || attempts.isEmpty()) {
            return out;
        }

        // climbId -> segment geometry, built once from every route's climbs.
        Map<String, StoredClimb> byClimbId = new HashMap<>();
        List<RouteCatalogEntry> catalog = routeRepo.loadCatalog();
        for (RouteCatalogEntry entry : catalog) {
            StoredRoute route;
            try {
                route = routeRepo.loadRoute(entry.routeId);
            } catch (Exception e) {
                continue; // corrupted/missing route file — skip, don't fail the whole join
            }
            if (route == null || route.climbs == null) continue;
            for (StoredClimb climb : route.climbs) {
                if (climb.segments == null || climb.segments.isEmpty()) continue;
                int len = climb.length > 0 ? climb.length : (climb.endDistance - climb.startDistance);
                if (len <= 0) continue;
                String climbId = ClimbIdentity.of(climb.startLat, climb.startLon, len);
                // Keep the first match; duplicates across routes describe the same
                // physical climb closely enough for an FTP estimate.
                byClimbId.putIfAbsent(climbId, climb);
            }
        }

        for (StoredClimbAttempt attempt : attempts) {
            if (attempt.climbId == null || attempt.elapsedSec <= 0) continue;
            StoredClimb climb = byClimbId.get(attempt.climbId);
            if (climb == null || climb.segments == null || climb.segments.isEmpty()) continue;
            List<StoredSegment> segs = climb.segments;
            int[] dist = new int[segs.size()];
            double[] grad = new double[segs.size()];
            int[] surface = new int[segs.size()];
            for (int i = 0; i < segs.size(); i++) {
                dist[i] = segs.get(i).distance;
                grad[i] = segs.get(i).gradient;
                surface[i] = segs.get(i).surfaceType;
            }
            out.add(new FtpEstimator.Effort(dist, grad, surface, attempt.elapsedSec));
        }
        return out;
    }
}
