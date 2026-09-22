package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Verifies the caching behaviour that fixes the full-catalog rescan-on-every-open bug: a second
 * {@link HistoricClimbScoreCache#get} call must not re-scan the catalog (RouteRepository) unless
 * {@link HistoricClimbScoreCache#invalidate()} was called in between (e.g. after a resync/attempt
 * write, mirroring RouteRepository#saveRoute / ClimbAttemptRepository#append in production).
 */
public class HistoricClimbScoreCacheTest {

    private static StoredClimbAttempt attempt(String climbId) {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = climbId;
        return a;
    }

    private static StoredClimb climb(double lat, double lon, int length,
                                      int elevationGain, double avgGradient) {
        StoredClimb c = new StoredClimb();
        c.startLat = lat;
        c.startLon = lon;
        c.length = length;
        c.elevationGain = elevationGain;
        c.avgGradient = avgGradient;
        return c;
    }

    @Test
    public void get_secondCall_doesNotRescanCatalog_untilInvalidated() throws Exception {
        RouteRepository routeRepo = mock(RouteRepository.class);
        ClimbAttemptRepository attemptRepo = mock(ClimbAttemptRepository.class);

        String climbId = ClimbIdentity.of(50.5, 5.8, 1000);
        when(attemptRepo.loadAll()).thenReturn(Collections.singletonList(attempt(climbId)));

        RouteCatalogEntry entry = new RouteCatalogEntry();
        entry.routeId = "r1";
        when(routeRepo.loadCatalog()).thenReturn(new ArrayList<>(Collections.singletonList(entry)));

        StoredRoute route = new StoredRoute();
        route.climbs = Arrays.asList(climb(50.5, 5.8, 1000, 300, 0.08));
        when(routeRepo.loadRoute("r1")).thenReturn(route);

        HistoricClimbScoreCache cache = new HistoricClimbScoreCache();

        List<Double> first = cache.get(routeRepo, attemptRepo);
        List<Double> second = cache.get(routeRepo, attemptRepo);

        assertEquals(1, first.size());
        assertEquals(300 * 0.08, first.get(0), 1e-9);
        assertEquals(first, second);
        // The catalog scan happens once, not once per get() call — this is the fix for the
        // "full-catalog rescan on every route-detail open" bug.
        verify(routeRepo, times(1)).loadCatalog();
        verify(routeRepo, times(1)).loadRoute("r1");

        cache.invalidate();
        cache.get(routeRepo, attemptRepo);

        // After invalidation the scan runs again.
        verify(routeRepo, times(2)).loadCatalog();
    }

    @Test
    public void get_noAttempts_returnsEmptyWithoutScanningCatalog() {
        RouteRepository routeRepo = mock(RouteRepository.class);
        ClimbAttemptRepository attemptRepo = mock(ClimbAttemptRepository.class);
        when(attemptRepo.loadAll()).thenReturn(Collections.emptyList());

        HistoricClimbScoreCache cache = new HistoricClimbScoreCache();
        List<Double> result = cache.get(routeRepo, attemptRepo);

        assertEquals(0, result.size());
        verify(routeRepo, times(0)).loadCatalog();
    }
}
