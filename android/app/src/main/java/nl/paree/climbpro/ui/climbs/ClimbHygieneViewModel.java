package nl.paree.climbpro.ui.climbs;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.ClimbMergeService;
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.climb.NearDuplicateClimbFinder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Backs the "klim-opschoning" screen (issue #76): scans the whole catalog for near-duplicate
 * stored climbs and lets the user confirm merges one pair at a time. Never merges without an
 * explicit per-pair confirmation from the activity.
 */
public final class ClimbHygieneViewModel extends AndroidViewModel {

    private final RouteRepository       routeRepo;
    private final ClimbAttemptRepository attemptRepo;
    private final ExecutorService       executor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<List<NearDuplicateClimbFinder.Candidate>> candidates =
            new MutableLiveData<>();
    private final MutableLiveData<String>  error    = new MutableLiveData<>();
    private final MutableLiveData<Boolean> scanning  = new MutableLiveData<>(false);

    public ClimbHygieneViewModel(@NonNull Application app) {
        super(app);
        routeRepo   = new RouteRepository(app);
        attemptRepo = new ClimbAttemptRepository(app);
    }

    public LiveData<List<NearDuplicateClimbFinder.Candidate>> candidates() { return candidates; }
    public LiveData<String>  error()    { return error; }
    public LiveData<Boolean> scanning() { return scanning; }

    public void scan() {
        scanning.postValue(true);
        executor.execute(() -> {
            try {
                List<RouteCatalogEntry> catalog = routeRepo.loadCatalog();
                List<NearDuplicateClimbFinder.RouteClimbs> routeClimbs = new ArrayList<>();
                for (RouteCatalogEntry entry : catalog) {
                    StoredRoute route = routeRepo.loadRoute(entry.routeId);
                    if (route.climbs == null || route.climbs.isEmpty()) continue;
                    String displayName = entry.userDisplayName != null
                            ? entry.userDisplayName : entry.name;
                    routeClimbs.add(new NearDuplicateClimbFinder.RouteClimbs(
                            entry.routeId, displayName, route.climbs));
                }
                candidates.postValue(NearDuplicateClimbFinder.find(routeClimbs));
            } catch (Exception e) {
                candidates.postValue(Collections.emptyList());
                error.postValue("Scan mislukt: " + e.getMessage());
            } finally {
                scanning.postValue(false);
            }
        });
    }

    /** Merges {@code remove} into {@code keep}, then re-scans so the list reflects the change. */
    public void merge(NearDuplicateClimbFinder.ClimbRef keep,
                       NearDuplicateClimbFinder.ClimbRef remove) {
        executor.execute(() -> {
            try {
                new ClimbMergeService(routeRepo, attemptRepo).merge(keep, remove);
            } catch (Exception e) {
                error.postValue("Samenvoegen mislukt: " + e.getMessage());
            }
            scan();
        });
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
