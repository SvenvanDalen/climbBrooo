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
 * <p>Invalidated by {@link #invalidate()} whenever the data it was built from actually changes:
 * a route is saved (import/resync) or a new climb attempt is recorded.
 */
public final class HistoricClimbScoreCache {

    private List<Double> cached; // null = stale/uncomputed

    public synchronized List<Double> get(RouteRepository routeRepo, ClimbAttemptRepository attemptRepo) {
        if (cached != null) return cached;
        cached = compute(routeRepo, attemptRepo);
        return cached;
    }

    /** Call after any write that can change which climbs exist or their gain/gradient/attempts. */
    public synchronized void invalidate() {
        cached = null;
    }

    private static List<Double> compute(RouteRepository routeRepo, ClimbAttemptRepository attemptRepo) {
        List<StoredClimbAttempt> attempts = attemptRepo.loadAll();
        Set<String> wanted = new HashSet<>();
        for (StoredClimbAttempt a : attempts) wanted.add(a.climbId);
        if (wanted.isEmpty()) return new ArrayList<>();

        Map<String, ClimbCatalogIndex.Entry> located = ClimbCatalogIndex.resolve(routeRepo, wanted);
        List<Double> scores = new ArrayList<>(located.size());
        for (ClimbCatalogIndex.Entry e : located.values()) {
            scores.add(DifficultyScoreCalculator.score(e.climb.elevationGain, e.climb.avgGradient, 0));
        }
        return scores;
    }
}
