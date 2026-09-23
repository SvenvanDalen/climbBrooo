package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimbAttempt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Caches the rider's historic per-climb difficulty scores (see {@link RestSplitAdvisor}) so that
 * opening a route-detail screen doesn't re-scan the whole route catalog from disk every time.
 *
 * <p>Held one-per-{@code Application} instance (see {@code ClimbProApplication}), not a JVM-wide
 * static, so it lives exactly as long as the app process and resets cleanly between Robolectric
 * tests, which each get a fresh Application. {@code RouteDetailViewModel} instances come and go
 * per screen visit, so scoping the cache to the ViewModel itself (as {@code SettingsViewModel}'s
 * FTP-effort cache does) would not help here — a *different* route-detail screen creates a new
 * ViewModel and would still re-scan. This cache is shared by all of them for the process lifetime.
 *
 * <p>Self-validating: each {@link #get} compares the catalog's and the attempts file's
 * {@code dataVersion()} (mtime + size) with the versions the cached result was built from, so
 * any write (resync, route/climb delete or merge, attempt append/update/remap) triggers a
 * rebuild without every write path needing an invalidation hook. {@link #invalidate()} forces
 * a rebuild explicitly.
 */
public final class HistoricClimbScoreCache {

    private List<Double> cached; // null = stale/uncomputed
    private String cachedVersion;

    public synchronized List<Double> get(RouteRepository routeRepo, ClimbAttemptRepository attemptRepo) {
        // Read the version BEFORE computing: a write racing with compute() then leaves a
        // mismatching version behind and the next get() rebuilds.
        String version = routeRepo.dataVersion() + "|" + attemptRepo.dataVersion();
        if (cached != null && version.equals(cachedVersion)) return cached;
        cached = compute(routeRepo, attemptRepo);
        cachedVersion = version;
        return cached;
    }

    /** Call after any write that can change which climbs exist or their gain/gradient/attempts. */
    public synchronized void invalidate() {
        cached = null;
    }

    private static List<Double> compute(RouteRepository routeRepo, ClimbAttemptRepository attemptRepo) {
        List<StoredClimbAttempt> attempts = attemptRepo.loadAll();
        Set<String> wanted = new HashSet<>();
        for (StoredClimbAttempt a : attempts) {
            if (a != null && a.climbId != null) wanted.add(a.climbId);
        }
        if (wanted.isEmpty()) return new ArrayList<>();

        Map<String, ClimbCatalogIndex.Entry> located = ClimbCatalogIndex.resolve(routeRepo, wanted);
        List<Double> scores = new ArrayList<>(located.size());
        for (ClimbCatalogIndex.Entry e : located.values()) {
            scores.add(DifficultyScoreCalculator.score(e.climb.elevationGain, e.climb.avgGradient, 0));
        }
        return scores;
    }
}
