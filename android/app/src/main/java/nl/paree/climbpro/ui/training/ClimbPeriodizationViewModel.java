package nl.paree.climbpro.ui.training;

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
import nl.paree.climbpro.domain.training.ClimbPeriodizationPlanner;
import nl.paree.climbpro.domain.training.ClimbPeriodizationPlanner.Candidate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Backs {@link ClimbPeriodizationActivity} (issue #63). Loads the catalog climbs and the
 * attempt logbook on a background executor, derives the rider's recent weekly climbing load
 * via {@link RecoveryAdvisor} (same climb-hm axis) and hands both to the pure
 * {@link ClimbPeriodizationPlanner}.
 */
public final class ClimbPeriodizationViewModel extends AndroidViewModel {

    /** Wrapper so "loaded, but nothing to plan" (null plan) is distinguishable from "loading". */
    public static final class State {
        public final ClimbPeriodizationPlanner.Plan plan;
        State(ClimbPeriodizationPlanner.Plan plan) { this.plan = plan; }
    }

    private final RouteRepository routeRepo;
    private final ClimbAttemptRepository attemptRepo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<State> state = new MutableLiveData<>();

    public ClimbPeriodizationViewModel(@NonNull Application app) {
        super(app);
        routeRepo = new RouteRepository(app);
        attemptRepo = new ClimbAttemptRepository(app);
    }

    public LiveData<State> state() { return state; }

    public void load() {
        executor.execute(() -> {
            List<StoredClimbAttempt> attempts = attemptRepo.loadAll();
            Map<String, Integer> attemptCount = new HashMap<>();
            for (StoredClimbAttempt a : attempts) {
                if (a.climbId == null) continue;
                Integer n = attemptCount.get(a.climbId);
                attemptCount.put(a.climbId, n == null ? 1 : n + 1);
            }

            Map<String, Candidate> candidates = new LinkedHashMap<>();
            Map<String, Integer> gainById = new HashMap<>();
            for (RouteCatalogEntry entry : routeRepo.loadCatalog()) {
                try {
                    StoredRoute route = routeRepo.loadRoute(entry.routeId);
                    if (route.climbs == null) continue;
                    for (StoredClimb c : route.climbs) {
                        String id = ClimbIdentity.of(c);
                        if (candidates.containsKey(id)) continue;
                        Integer n = attemptCount.get(id);
                        candidates.put(id, new Candidate(id, displayName(c), c.elevationGain,
                                ClimbIdentity.effectiveLength(c), c.avgGradient,
                                n == null ? 0 : n));
                        gainById.put(id, c.elevationGain);
                    }
                } catch (Exception ignored) {
                    // A route that fails to load just doesn't contribute climbs.
                }
            }

            double baseline = 0;
            if (!attempts.isEmpty()) {
                RecoveryAdvisor.Advice advice = RecoveryAdvisor.compute(attempts, gainById);
                if (advice.hasData) baseline = advice.baselineWeeklyAvgGainM;
            }
            state.postValue(new State(ClimbPeriodizationPlanner.plan(
                    new ArrayList<>(candidates.values()), baseline)));
        });
    }

    private static String displayName(StoredClimb c) {
        if (c.userDisplayName != null && !c.userDisplayName.trim().isEmpty()) {
            return c.userDisplayName;
        }
        return c.name != null && !c.name.trim().isEmpty() ? c.name : null;
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
