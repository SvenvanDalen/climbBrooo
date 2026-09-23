package nl.paree.climbpro.data.route;

import android.content.Context;
import android.util.Log;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * JSON-file persistence for user-defined route/climb collections, mirroring the
 * atomic-write pattern of {@link RouteRepository} and {@link nl.paree.climbpro.data.sync.SyncStateRepository}.
 * Layout: getFilesDir()/collections.json — a flat array, kept separate from catalog.json
 * since collections reference routes/climbs rather than describing them.
 */
public final class RouteCollectionRepository {

    private static final String TAG  = "RouteCollectionRepo";
    private static final String FILE = "collections.json";

    private final File collectionsFile;
    private final ObjectMapper mapper = new ObjectMapper();

    public RouteCollectionRepository(Context context) {
        this.collectionsFile = new File(context.getApplicationContext().getFilesDir(), FILE);
    }

    public List<RouteCollection> loadAll() {
        if (!collectionsFile.exists()) return new ArrayList<>();
        try (FileInputStream in = new FileInputStream(collectionsFile)) {
            RouteCollection[] arr = mapper.readValue(in, RouteCollection[].class);
            return new ArrayList<>(Arrays.asList(arr));
        } catch (IOException e) {
            Log.e(TAG, "Failed to load collections", e);
            return new ArrayList<>();
        }
    }

    public RouteCollection get(String collectionId) {
        for (RouteCollection c : loadAll()) {
            if (c.id.equals(collectionId)) return c;
        }
        return null;
    }

    /** Creates a new, empty collection with a fresh id. Returns the created collection. */
    public RouteCollection create(String name) {
        RouteCollection c = new RouteCollection();
        c.id   = UUID.randomUUID().toString();
        c.name = name;
        c.createdAtMs = c.lastModifiedMs = System.currentTimeMillis();
        List<RouteCollection> all = loadAll();
        all.add(c);
        saveAll(all);
        return c;
    }

    public void rename(String collectionId, String name) {
        List<RouteCollection> all = loadAll();
        for (RouteCollection c : all) {
            if (c.id.equals(collectionId)) {
                c.name = name;
                c.lastModifiedMs = System.currentTimeMillis();
                break;
            }
        }
        saveAll(all);
    }

    public void delete(String collectionId) {
        List<RouteCollection> all = loadAll();
        all.removeIf(c -> c.id.equals(collectionId));
        saveAll(all);
    }

    /** Adds a route to a collection. No-op (not a duplicate add) if already a member. */
    public void addRoute(String collectionId, String routeId) {
        List<RouteCollection> all = loadAll();
        for (RouteCollection c : all) {
            if (!c.id.equals(collectionId)) continue;
            if (c.routeIds == null) c.routeIds = new ArrayList<>();
            if (!c.routeIds.contains(routeId)) c.routeIds.add(routeId);
            c.lastModifiedMs = System.currentTimeMillis();
            break;
        }
        saveAll(all);
    }

    public void removeRoute(String collectionId, String routeId) {
        List<RouteCollection> all = loadAll();
        for (RouteCollection c : all) {
            if (!c.id.equals(collectionId)) continue;
            if (c.routeIds != null && c.routeIds.remove(routeId)) {
                c.lastModifiedMs = System.currentTimeMillis();
            }
            break;
        }
        saveAll(all);
    }

    /** Adds a climb (routeId + index) to a collection. No-op if already a member. */
    public void addClimb(String collectionId, String routeId, int climbIndex) {
        List<RouteCollection> all = loadAll();
        for (RouteCollection c : all) {
            if (!c.id.equals(collectionId)) continue;
            if (c.climbs == null) c.climbs = new ArrayList<>();
            ClimbMembership m = new ClimbMembership(routeId, climbIndex);
            if (!c.climbs.contains(m)) c.climbs.add(m);
            c.lastModifiedMs = System.currentTimeMillis();
            break;
        }
        saveAll(all);
    }

    public void removeClimb(String collectionId, String routeId, int climbIndex) {
        List<RouteCollection> all = loadAll();
        for (RouteCollection c : all) {
            if (!c.id.equals(collectionId)) continue;
            if (c.climbs != null
                    && c.climbs.remove(new ClimbMembership(routeId, climbIndex))) {
                c.lastModifiedMs = System.currentTimeMillis();
            }
            break;
        }
        saveAll(all);
    }

    /**
     * Keeps every {@link ClimbMembership} pointing into {@code routeId}'s climb list consistent
     * with a single climb having been removed at {@code removedIndex} (e.g. by
     * {@link ClimbMergeService#merge}, which calls this right after
     * {@link RouteRepository#removeClimb}). {@code climbIndex} is a raw index into
     * {@code StoredRoute#climbs}, not a stable climb identity, so removing element
     * {@code removedIndex} shifts every later climb's index down by one:
     * <ul>
     *   <li>a membership pointing exactly at {@code removedIndex} referenced the climb that was
     *       just removed — that membership is dropped, since the climb it points to no longer
     *       exists;</li>
     *   <li>a membership pointing after {@code removedIndex} is decremented by one so it keeps
     *       resolving to the same climb it did before the removal;</li>
     *   <li>a membership pointing before {@code removedIndex}, or belonging to a different route,
     *       is untouched.</li>
     * </ul>
     * Without this, {@link nl.paree.climbpro.ui.collections.CollectionDetailViewModel#resolveMembers}
     * — which only bounds-checks {@code climbIndex} against the current climb list — would
     * silently resolve a shifted membership to the WRONG climb, or silently drop an
     * out-of-range one, with no error surfaced to the user.
     */
    public void onClimbRemoved(String routeId, int removedIndex) {
        List<RouteCollection> all = loadAll();
        boolean changed = false;
        for (RouteCollection c : all) {
            if (c.climbs == null || c.climbs.isEmpty()) continue;
            List<ClimbMembership> updated = new ArrayList<>(c.climbs.size());
            boolean localChanged = false;
            for (ClimbMembership m : c.climbs) {
                if (!routeId.equals(m.routeId)) {
                    updated.add(m);
                    continue;
                }
                if (m.climbIndex == removedIndex) {
                    localChanged = true; // the climb this pointed at is gone; drop the membership
                    continue;
                }
                if (m.climbIndex > removedIndex) {
                    m.climbIndex -= 1;
                    localChanged = true;
                }
                updated.add(m);
            }
            if (localChanged) {
                c.climbs = updated;
                c.lastModifiedMs = System.currentTimeMillis();
                changed = true;
            }
        }
        if (changed) saveAll(all);
    }

    /** Every route that was deleted must be cleared from all collections it belonged to. */
    public void removeRouteEverywhere(String routeId) {
        List<RouteCollection> all = loadAll();
        boolean changed = false;
        for (RouteCollection c : all) {
            boolean routeRemoved = c.routeIds != null && c.routeIds.remove(routeId);
            boolean climbsRemoved = c.climbs != null
                    && c.climbs.removeIf(m -> routeId.equals(m.routeId));
            if (routeRemoved || climbsRemoved) {
                c.lastModifiedMs = System.currentTimeMillis();
                changed = true;
            }
        }
        if (changed) saveAll(all);
    }

    public List<RouteCollection> collectionsContainingRoute(String routeId) {
        List<RouteCollection> result = new ArrayList<>();
        for (RouteCollection c : loadAll()) {
            if (c.routeIds != null && c.routeIds.contains(routeId)) result.add(c);
        }
        return result;
    }

    public List<RouteCollection> collectionsContainingClimb(String routeId, int climbIndex) {
        List<RouteCollection> result = new ArrayList<>();
        ClimbMembership target = new ClimbMembership(routeId, climbIndex);
        for (RouteCollection c : loadAll()) {
            if (c.climbs != null && c.climbs.contains(target)) result.add(c);
        }
        return result;
    }

    // -------------------------------------------------------------------------

    private void saveAll(List<RouteCollection> all) {
        File tmp = new File(collectionsFile.getParentFile(), FILE + ".tmp");
        try {
            byte[] data = mapper.writeValueAsBytes(all);
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(data);
                out.getFD().sync();
            }
            try {
                java.nio.file.Files.move(tmp.toPath(), collectionsFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                java.nio.file.Files.move(tmp.toPath(), collectionsFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to save collections", e);
            tmp.delete();
        }
    }
}
