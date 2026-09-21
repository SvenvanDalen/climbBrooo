package nl.paree.climbpro.data.route;

import nl.paree.climbpro.domain.climb.ClimbIdentity;
import nl.paree.climbpro.domain.climb.NearDuplicateClimbFinder;

import java.io.IOException;
import java.util.List;

/**
 * Executes a user-confirmed merge of two near-duplicate stored climbs (issue #76): keeps one
 * {@link StoredClimb}, removes the other from its route, carries over the removed climb's
 * {@code userDisplayName} if the kept climb doesn't already have one, and remaps any
 * {@link StoredClimbAttempt} history from the removed climb's {@link ClimbIdentity} key onto the
 * kept climb's key so PRs/history survive. Never called automatically — only from an explicit
 * per-pair user confirmation, since it deletes data.
 */
public final class ClimbMergeService {

    private final RouteRepository routeRepo;
    private final ClimbAttemptRepository attemptRepo;

    public ClimbMergeService(RouteRepository routeRepo, ClimbAttemptRepository attemptRepo) {
        this.routeRepo   = routeRepo;
        this.attemptRepo = attemptRepo;
    }

    /**
     * Merges {@code remove} into {@code keep}. Both refs must come from different routes
     * (guaranteed by {@link NearDuplicateClimbFinder}, which never pairs same-route climbs).
     */
    public void merge(NearDuplicateClimbFinder.ClimbRef keep,
                       NearDuplicateClimbFinder.ClimbRef remove) throws IOException {
        if (keep.routeId.equals(remove.routeId)) {
            throw new IllegalArgumentException(
                    "Cannot merge two climbs from the same route: " + keep.routeId);
        }

        StoredClimb keepClimb   = loadClimb(keep);
        StoredClimb removeClimb = loadClimb(remove);

        if (keepClimb.userDisplayName == null && removeClimb.userDisplayName != null) {
            routeRepo.renameClimb(keep.routeId, keep.climbIndex, removeClimb.userDisplayName);
        }

        String removeClimbId = ClimbIdentity.of(
                removeClimb.startLat, removeClimb.startLon, removeClimb.length);
        String keepClimbId = ClimbIdentity.of(
                keepClimb.startLat, keepClimb.startLon, keepClimb.length);
        remapAttempts(removeClimbId, keepClimbId);

        routeRepo.removeClimb(remove.routeId, remove.climbIndex);
    }

    private StoredClimb loadClimb(NearDuplicateClimbFinder.ClimbRef ref) throws IOException {
        StoredRoute route = routeRepo.loadRoute(ref.routeId);
        if (route.climbs == null || ref.climbIndex < 0 || ref.climbIndex >= route.climbs.size()) {
            throw new IOException("Climb index out of range for route " + ref.routeId
                    + ": " + ref.climbIndex);
        }
        return route.climbs.get(ref.climbIndex);
    }

    private void remapAttempts(String fromClimbId, String toClimbId) throws IOException {
        if (fromClimbId.equals(toClimbId)) return;
        List<StoredClimbAttempt> attempts = attemptRepo.loadAll();
        boolean changed = false;
        for (StoredClimbAttempt a : attempts) {
            if (fromClimbId.equals(a.climbId)) {
                a.climbId = toClimbId;
                changed = true;
            }
        }
        if (changed) attemptRepo.overwriteAll(attempts);
    }
}
