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
    private final RouteCollectionRepository collectionRepo;

    /**
     * @param collectionRepo used to keep {@link ClimbMembership} indices consistent after a
     *                        climb is removed (issue: merges corrupted collection membership
     *                        indices). {@code RouteRepository} intentionally does not depend on
     *                        {@code RouteCollectionRepository} itself, so this orchestration
     *                        happens here, where a merge already touches multiple repositories.
     */
    public ClimbMergeService(RouteRepository routeRepo, ClimbAttemptRepository attemptRepo,
                              RouteCollectionRepository collectionRepo) {
        this.routeRepo      = routeRepo;
        this.attemptRepo    = attemptRepo;
        this.collectionRepo = collectionRepo;
    }

    /**
     * Merges {@code remove} into {@code keep}. Both refs must come from different routes
     * (guaranteed by {@link NearDuplicateClimbFinder}, which never pairs same-route climbs).
     *
     * <p>The {@link NearDuplicateClimbFinder.ClimbRef#climbIndex} captured in {@code keep}/
     * {@code remove} can be stale: the candidate list is a snapshot from the last scan, and an
     * earlier merge affecting the same route shifts every later climb's index down by one. Rather
     * than trust the captured index, this re-locates each climb inside the CURRENT on-disk route
     * state by its immutable detection fields (see {@link #resolve}) before touching anything. If
     * a climb can't be found any more (e.g. it was already merged away by a previous confirm),
     * this throws instead of silently doing nothing — a merge action must never look like it
     * succeeded when it didn't.
     */
    public void merge(NearDuplicateClimbFinder.ClimbRef keep,
                       NearDuplicateClimbFinder.ClimbRef remove) throws IOException {
        if (keep.routeId.equals(remove.routeId)) {
            throw new IllegalArgumentException(
                    "Cannot merge two climbs from the same route: " + keep.routeId);
        }

        ResolvedClimb keepResolved   = resolve(keep);
        ResolvedClimb removeResolved = resolve(remove);
        StoredClimb keepClimb   = keepResolved.climb;
        StoredClimb removeClimb = removeResolved.climb;

        if (keepClimb.userDisplayName == null && removeClimb.userDisplayName != null) {
            routeRepo.renameClimb(keep.routeId, keepResolved.index, removeClimb.userDisplayName);
        }

        String removeClimbId = ClimbIdentity.of(
                removeClimb.startLat, removeClimb.startLon, removeClimb.length);
        String keepClimbId = ClimbIdentity.of(
                keepClimb.startLat, keepClimb.startLon, keepClimb.length);
        remapAttempts(removeClimbId, keepClimbId);

        routeRepo.removeClimb(remove.routeId, removeResolved.index);
        collectionRepo.onClimbRemoved(remove.routeId, removeResolved.index);
    }

    /** A climb re-located inside the current route state, paired with its up-to-date index. */
    private static final class ResolvedClimb {
        final int index;
        final StoredClimb climb;

        ResolvedClimb(int index, StoredClimb climb) {
            this.index = index;
            this.climb = climb;
        }
    }

    /**
     * Re-locates the climb a {@link NearDuplicateClimbFinder.ClimbRef} points at inside the
     * CURRENT on-disk route state, rather than trusting the (possibly stale) captured
     * {@code climbIndex}. Fast path: the index still points at a climb matching the ref's
     * immutable detection fields (startDistance/length/start coordinate), which is the common
     * case when no other merge has touched this route since the candidate list was built. Slow
     * path: some earlier merge shifted indices, so this falls back to a linear scan for the
     * climb by those same fields. If neither finds a match, the climb is gone (already merged
     * away) and this throws rather than letting the caller silently no-op.
     */
    private ResolvedClimb resolve(NearDuplicateClimbFinder.ClimbRef ref) throws IOException {
        StoredRoute route = routeRepo.loadRoute(ref.routeId);
        if (route.climbs == null || route.climbs.isEmpty()) {
            throw new IOException("Route " + ref.routeId + " has no climbs; the climb to merge "
                    + "(index " + ref.climbIndex + ") no longer exists.");
        }

        if (ref.climbIndex >= 0 && ref.climbIndex < route.climbs.size()) {
            StoredClimb candidate = route.climbs.get(ref.climbIndex);
            if (matches(candidate, ref.climb)) {
                return new ResolvedClimb(ref.climbIndex, candidate);
            }
        }

        for (int i = 0; i < route.climbs.size(); i++) {
            StoredClimb candidate = route.climbs.get(i);
            if (matches(candidate, ref.climb)) {
                return new ResolvedClimb(i, candidate);
            }
        }

        throw new IOException("Climb could not be re-located in route " + ref.routeId
                + " (it may already have been merged in another action). Refresh and try again.");
    }

    /** Matches on the fields the climb detector produces, not on user-editable display names. */
    private static boolean matches(StoredClimb a, StoredClimb b) {
        return a.startDistance == b.startDistance
                && a.length == b.length
                && Double.compare(a.startLat, b.startLat) == 0
                && Double.compare(a.startLon, b.startLon) == 0;
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
