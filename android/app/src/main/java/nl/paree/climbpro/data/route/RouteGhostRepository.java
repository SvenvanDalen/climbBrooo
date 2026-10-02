package nl.paree.climbpro.data.route;

import android.content.Context;
import android.util.Log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import nl.paree.climbpro.domain.route.RouteGhostProfile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Persists the best ride per route ({@link StoredRouteGhost}, issue #178) as
 * {@code route_ghosts.json} in {@code getFilesDir()}. Only the fastest complete ride of each
 * route is kept; a ride of a route whose geometry has since changed replaces the stale entry.
 */
public final class RouteGhostRepository {

    private static final String TAG = "RouteGhostRepo";
    public static final String FILE = "route_ghosts.json";

    /** Process-wide: the Strava sync and the watch request handler each create instances. */
    private static final ReentrantLock WRITE_LOCK = new ReentrantLock();

    private final File file;
    private final ObjectMapper mapper;

    public RouteGhostRepository(Context context) {
        Context app = context.getApplicationContext();
        this.file   = new File(app.getFilesDir(), FILE);
        this.mapper = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }

    public List<StoredRouteGhost> loadAll() {
        if (!file.exists()) return new ArrayList<>();
        try (FileInputStream in = new FileInputStream(file)) {
            return new ArrayList<>(Arrays.asList(mapper.readValue(in, StoredRouteGhost[].class)));
        } catch (IOException e) {
            Log.e(TAG, "Failed to load route ghosts", e);
            return new ArrayList<>();
        }
    }

    /** The stored best ride of this route, or null. */
    public StoredRouteGhost find(String routeId) {
        if (routeId == null) return null;
        for (StoredRouteGhost g : loadAll()) {
            if (g != null && routeId.equals(g.routeId)) return g;
        }
        return null;
    }

    /**
     * The wire profile ('gh') for this route, or null when there is no stored ride or it no
     * longer fits the route's current length.
     */
    public int[] wireFor(StoredRoute route) {
        return wireFor(route, find(route != null ? route.routeId : null));
    }

    /** Pure part of {@link #wireFor(StoredRoute)}, for tests. */
    public static int[] wireFor(StoredRoute route, StoredRouteGhost ghost) {
        if (route == null || ghost == null || route.distances == null
                || route.distances.length == 0) {
            return null;
        }
        if (!ghost.fits(route.distances[route.distances.length - 1])) return null;
        return RouteGhostProfile.wire(ghost.stepM, ghost.stepSec);
    }

    /**
     * Keeps each offered ride when it beats the stored one for its route, or when the stored
     * one was made for another route length. Returns how many entries changed.
     */
    public int offer(List<StoredRouteGhost> candidates) throws IOException {
        if (candidates == null || candidates.isEmpty()) return 0;
        WRITE_LOCK.lock();
        try {
            Map<String, StoredRouteGhost> byRoute = new LinkedHashMap<>();
            for (StoredRouteGhost g : loadAll()) {
                if (g != null && g.routeId != null) byRoute.put(g.routeId, g);
            }
            int changed = 0;
            for (StoredRouteGhost c : candidates) {
                if (c == null || c.routeId == null || c.totalSec <= 0) continue;
                StoredRouteGhost cur = byRoute.get(c.routeId);
                if (better(c, cur)) {
                    byRoute.put(c.routeId, c);
                    changed++;
                }
            }
            if (changed > 0) {
                writeAtomic(file, mapper.writeValueAsBytes(new ArrayList<>(byRoute.values())));
            }
            return changed;
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    /** True when {@code candidate} should replace {@code current}. */
    static boolean better(StoredRouteGhost candidate, StoredRouteGhost current) {
        if (current == null || current.stepSec == null) return true;
        if (!current.fits(candidate.routeLengthM) || current.stepM != candidate.stepM) return true;
        return candidate.totalSec < current.totalSec;
    }

    /** Deletes the file (privacy dashboard). */
    public boolean deleteAll() {
        WRITE_LOCK.lock();
        try {
            return !file.exists() || file.delete();
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    private static void writeAtomic(File target, byte[] data) throws IOException {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.getFD().sync();
        }
        try {
            try {
                java.nio.file.Files.move(tmp.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                java.nio.file.Files.move(tmp.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException moveFailed) {
            tmp.delete();
            throw moveFailed;
        }
    }
}
