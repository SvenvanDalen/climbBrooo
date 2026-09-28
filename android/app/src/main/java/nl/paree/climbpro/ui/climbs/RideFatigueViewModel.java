package nl.paree.climbpro.ui.climbs;

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
import nl.paree.climbpro.domain.climb.RideFatigueCurveCalculator;
import nl.paree.climbpro.domain.climb.RideFatigueCurveCalculator.ClimbRef;
import nl.paree.climbpro.domain.climb.RideFatigueCurveCalculator.FatiguePoint;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Resolves one activity's stored climb attempts into a {@link FatiguePoint} curve for
 * {@link RideFatigueActivity}. Pure ordering/metric logic lives in
 * {@link RideFatigueCurveCalculator}; this class only resolves each attempt's climbId to the
 * {@link ClimbRef} the calculator needs (display name, elevation gain, route position) by
 * scanning the route catalog — mirroring {@link ClimbTimelineViewModel#resolveClimbInfo}.
 */
public final class RideFatigueViewModel extends AndroidViewModel {

    private final RouteRepository routeRepo;
    private final ClimbAttemptRepository attemptRepo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<List<FatiguePoint>> curve = new MutableLiveData<>();
    private final MutableLiveData<Boolean> chronological = new MutableLiveData<>();

    public RideFatigueViewModel(@NonNull Application app) {
        super(app);
        routeRepo = new RouteRepository(app);
        attemptRepo = new ClimbAttemptRepository(app);
    }

    public LiveData<List<FatiguePoint>> curve() { return curve; }
    /** False when the curve uses the route-position ordering fallback (show the caveat). */
    public LiveData<Boolean> chronological() { return chronological; }

    public void loadForActivity(long activityId) {
        executor.execute(() -> {
            List<StoredClimbAttempt> forActivity = new ArrayList<>();
            for (StoredClimbAttempt a : attemptRepo.loadAll()) {
                if (a.activityId == activityId) forActivity.add(a);
            }

            Set<String> wanted = new HashSet<>();
            for (StoredClimbAttempt a : forActivity) wanted.add(a.climbId);
            Map<String, ClimbRef> refs = resolveClimbRefs(wanted);

            chronological.postValue(RideFatigueCurveCalculator.isChronological(forActivity, refs));
            curve.postValue(RideFatigueCurveCalculator.computeForActivity(forActivity, refs));
        });
    }

    /** Maps each wanted climbId to the first route (+ its climb index) that contains it. */
    private Map<String, ClimbRef> resolveClimbRefs(Set<String> wanted) {
        Map<String, ClimbRef> map = new HashMap<>();
        for (RouteCatalogEntry entry : routeRepo.loadCatalog()) {
            try {
                StoredRoute route = routeRepo.loadRoute(entry.routeId);
                if (route.climbs == null) continue;
                for (int i = 0; i < route.climbs.size(); i++) {
                    StoredClimb c = route.climbs.get(i);
                    String id = ClimbIdentity.of(c);
                    if (wanted.contains(id) && !map.containsKey(id)) {
                        String name = c.userDisplayName != null ? c.userDisplayName
                                : (c.name != null ? c.name : "Klim");
                        map.put(id, new ClimbRef(name, c.elevationGain, entry.routeId, i));
                    }
                }
            } catch (Exception ignored) {
                // A route that fails to load just won't resolve its climbs.
            }
        }
        return map;
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
