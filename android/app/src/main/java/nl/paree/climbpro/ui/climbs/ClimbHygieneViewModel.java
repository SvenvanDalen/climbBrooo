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
    private final MutableLiveData<Event<String>> mergeSuccess = new MutableLiveData<>();

    public ClimbHygieneViewModel(@NonNull Application app) {
        super(app);
        routeRepo   = new RouteRepository(app);
        attemptRepo = new ClimbAttemptRepository(app);
    }

    public LiveData<List<NearDuplicateClimbFinder.Candidate>> candidates() { return candidates; }
    public LiveData<String>  error()    { return error; }
    public LiveData<Boolean> scanning() { return scanning; }
    /**
     * Fires once per merge that {@link ClimbMergeService#merge} actually completed without
     * throwing — this is the only signal the activity should use to show a success toast.
     * Wrapped in {@link Event} so a config-change re-subscription doesn't replay a stale success.
     */
    public LiveData<Event<String>> mergeSuccess() { return mergeSuccess; }

    /** One-shot LiveData payload: consumed at most once via {@link #consume}. */
    public static final class Event<T> {
        private final T value;
        private boolean consumed;

        Event(T value) { this.value = value; }

        /** Returns the payload the first time it's called, null on every call after that. */
        public T consume() {
            if (consumed) return null;
            consumed = true;
            return value;
        }
    }

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

    /**
     * Merges {@code remove} into {@code keep}, then re-scans so the list reflects the change.
     * Only posts to {@link #mergeSuccess()} once {@link ClimbMergeService#merge} has returned
     * without throwing — the activity must not show a success toast before this fires, since the
     * merge can fail (I/O error, or the climb having already been merged away by a stale index).
     */
    public void merge(NearDuplicateClimbFinder.ClimbRef keep,
                       NearDuplicateClimbFinder.ClimbRef remove) {
        executor.execute(() -> {
            try {
                new ClimbMergeService(routeRepo, attemptRepo).merge(keep, remove);
                mergeSuccess.postValue(new Event<>("Samengevoegd"));
            } catch (Exception e) {
                error.postValue("Samenvoegen mislukt: " + e.getMessage());
            }
            scan();
        });
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
