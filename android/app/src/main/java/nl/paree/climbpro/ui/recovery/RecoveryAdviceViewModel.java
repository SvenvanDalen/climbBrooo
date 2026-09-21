package nl.paree.climbpro.ui.recovery;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.climb.ClimbIdentity;
import nl.paree.climbpro.domain.climb.RecoveryAdvisor;
import nl.paree.climbpro.domain.climb.RecoveryAdvisor.Advice;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loads stored climb attempts + resolves each climb's elevation gain across the whole
 * route catalog, then hands both to {@link RecoveryAdvisor}. Mirrors the
 * attempt-loading/climb-resolution shape of {@code ClimbWrappedViewModel} and {@code
 * ClimbLogbookViewModel}.
 */
public final class RecoveryAdviceViewModel extends AndroidViewModel {

    private final RouteRepository routeRepo;
    private final ClimbAttemptRepository attemptRepo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<Advice> advice = new MutableLiveData<>();

    public RecoveryAdviceViewModel(@NonNull Application app) {
        super(app);
        routeRepo = new RouteRepository(app);
        attemptRepo = new ClimbAttemptRepository(app);
    }

    public LiveData<Advice> advice() { return advice; }

    public void load() {
        executor.execute(() -> {
            List<StoredClimbAttempt> attempts = attemptRepo.loadAll();
            Map<String, Integer> elevationGainByClimbId = resolveElevationGain();
            advice.postValue(RecoveryAdvisor.compute(attempts, elevationGainByClimbId));
        });
    }

    /** Maps each known climbId to its elevation gain (m), across all stored routes. */
    private Map<String, Integer> resolveElevationGain() {
        Map<String, Integer> map = new HashMap<>();
        for (RouteCatalogEntry entry : routeRepo.loadCatalog()) {
            try {
                StoredRoute route = routeRepo.loadRoute(entry.routeId);
                if (route.climbs == null) continue;
                for (StoredClimb c : route.climbs) {
                    int len = c.length > 0 ? c.length : (c.endDistance - c.startDistance);
                    String id = ClimbIdentity.of(c.startLat, c.startLon, len);
                    if (!map.containsKey(id)) {
                        map.put(id, c.elevationGain);
                    }
                }
            } catch (Exception ignored) {
                // A route that fails to load just won't contribute its climbs' elevation gain.
            }
        }
        return map;
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
