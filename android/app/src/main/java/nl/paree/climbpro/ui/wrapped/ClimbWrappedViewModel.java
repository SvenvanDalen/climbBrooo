package nl.paree.climbpro.ui.wrapped;

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
import nl.paree.climbpro.domain.climb.WrappedCalculator;
import nl.paree.climbpro.domain.climb.WrappedCalculator.ClimbInfo;
import nl.paree.climbpro.domain.climb.WrappedCalculator.Summary;

import java.time.ZoneOffset;
import java.time.Year;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ClimbWrappedViewModel extends AndroidViewModel {

    private final RouteRepository routeRepo;
    private final ClimbAttemptRepository attemptRepo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<Summary> summary = new MutableLiveData<>();

    public ClimbWrappedViewModel(@NonNull Application app) {
        super(app);
        routeRepo = new RouteRepository(app);
        attemptRepo = new ClimbAttemptRepository(app);
    }

    public LiveData<Summary> summary() { return summary; }

    /** Current calendar year in UTC, matching {@link WrappedCalculator#yearOf}. */
    public static int currentYear() {
        return java.time.Instant.now().atZone(ZoneOffset.UTC).get(java.time.temporal.ChronoField.YEAR);
    }

    public void load(int year) {
        executor.execute(() -> {
            java.util.List<StoredClimbAttempt> attempts = attemptRepo.loadAll();
            Map<String, ClimbInfo> climbInfo = resolveClimbInfo();
            summary.postValue(WrappedCalculator.compute(year, attempts, climbInfo));
        });
    }

    /** Maps each known climbId to its display name + elevation gain, across all stored routes. */
    private Map<String, ClimbInfo> resolveClimbInfo() {
        Map<String, ClimbInfo> map = new HashMap<>();
        for (RouteCatalogEntry entry : routeRepo.loadCatalog()) {
            try {
                StoredRoute route = routeRepo.loadRoute(entry.routeId);
                if (route.climbs == null) continue;
                for (StoredClimb c : route.climbs) {
                    int len = c.length > 0 ? c.length : (c.endDistance - c.startDistance);
                    String id = ClimbIdentity.of(c.startLat, c.startLon, len);
                    if (!map.containsKey(id)) {
                        String name = c.userDisplayName != null ? c.userDisplayName
                                : (c.name != null ? c.name : "Klim");
                        map.put(id, new ClimbInfo(name, c.elevationGain));
                    }
                }
            } catch (Exception ignored) {
                // A route that fails to load just won't resolve its climbs' names/elevation.
            }
        }
        return map;
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
