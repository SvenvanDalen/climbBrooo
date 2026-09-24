package nl.paree.climbpro.ui.planning;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.data.planning.PlannedClimb;
import nl.paree.climbpro.data.planning.PlannedClimbRepository;
import nl.paree.climbpro.data.rider.RiderProfileRepository;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.planning.MultiDayTourPlan;
import nl.paree.climbpro.domain.planning.MultiDayTourPlanner;
import nl.paree.climbpro.domain.planning.PlannedClimbScheduler;
import nl.paree.climbpro.domain.planning.TourStop;
import nl.paree.climbpro.domain.planning.TourStopFactory;
import nl.paree.climbpro.domain.power.RiderProfile;
import nl.paree.climbpro.service.RoutePacingPlanner;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Backs {@link MultiDayTourActivity} (issue #67). Candidates are the upcoming entries of the
 * Klimplanning, deduplicated per route/climb. The tour plan itself is computed on the fly by
 * {@link MultiDayTourPlanner} and never persisted — it is fully derived from the Klimplanning
 * plus route data, so a stored copy would only go stale on rename, resync or deletion.
 */
public final class MultiDayTourViewModel extends AndroidViewModel {

    private final PlannedClimbRepository planRepo;
    private final RouteRepository routeRepo;
    private final RiderProfileRepository profileRepo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<List<TourStop>> candidates = new MutableLiveData<>();
    private final MutableLiveData<MultiDayTourPlan> plan = new MutableLiveData<>();
    private final MutableLiveData<Boolean> profileComplete = new MutableLiveData<>(false);
    private final MutableLiveData<String> error = new MutableLiveData<>();

    /** Keys of the selected candidates; null until the first load (then: all selected). */
    private volatile Set<String> selectedKeys;

    public MultiDayTourViewModel(@NonNull Application app) {
        super(app);
        planRepo = new PlannedClimbRepository(app);
        routeRepo = new RouteRepository(app);
        profileRepo = new RiderProfileRepository(app);
    }

    public LiveData<List<TourStop>> candidates() { return candidates; }
    public LiveData<MultiDayTourPlan> plan() { return plan; }
    public LiveData<Boolean> profileComplete() { return profileComplete; }
    public LiveData<String> error() { return error; }

    /** Loads the Klimplanning candidates once (subsequent calls keep the current selection). */
    public void loadCandidates() {
        if (candidates.getValue() != null) return;
        executor.execute(() -> {
            try {
                RiderProfile profile = profileRepo.load();
                profileComplete.postValue(profile.isComplete());
                List<TourStop> out = buildCandidates(profile);
                if (selectedKeys == null) {
                    selectedKeys = new LinkedHashSet<>();
                    for (TourStop s : out) selectedKeys.add(s.key);
                }
                candidates.postValue(out);
            } catch (Exception e) {
                error.postValue("Klimplanning laden mislukt: " + e.getMessage());
                candidates.postValue(new ArrayList<>());
            }
        });
    }

    public Set<String> selectedKeys() {
        return selectedKeys != null ? Collections.unmodifiableSet(selectedKeys) : Collections.emptySet();
    }

    public void setSelectedKeys(Set<String> keys) {
        selectedKeys = new LinkedHashSet<>(keys);
    }

    /** Computes the plan off the main thread for the current selection. */
    public void computePlan(MultiDayTourPlanner.Request request) {
        List<TourStop> all = candidates.getValue();
        if (all == null) return;
        List<TourStop> chosen = new ArrayList<>();
        for (TourStop s : all) if (selectedKeys().contains(s.key)) chosen.add(s);
        executor.execute(() -> plan.postValue(MultiDayTourPlanner.plan(chosen, request)));
    }

    private List<TourStop> buildCandidates(RiderProfile profile) {
        long nowSec = System.currentTimeMillis() / 1000L;
        List<PlannedClimb> upcoming = PlannedClimbScheduler.upcoming(
                planRepo.loadAll(), nowSec, ZoneId.systemDefault());

        Map<String, StoredRoute> routes = new HashMap<>();
        Map<String, int[][]> pacing = new HashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        List<TourStop> out = new ArrayList<>();
        for (PlannedClimb p : upcoming) {
            if (p.routeId == null) continue;
            String key = TourStopFactory.key(p.routeId, p.climbIndex);
            if (!seen.add(key)) continue; // the same climb planned on two dates is one stop

            StoredRoute route = routes.get(p.routeId);
            if (route == null && !routes.containsKey(p.routeId)) {
                try {
                    route = routeRepo.loadRoute(p.routeId);
                } catch (Exception ignored) {
                    route = null; // deleted/corrupt route: skip this plan entry
                }
                routes.put(p.routeId, route);
                pacing.put(p.routeId, route != null && profile.isComplete()
                        ? RoutePacingPlanner.plan(route, profile) : null);
            }
            if (route == null) continue;
            int[][] perClimb = pacing.get(p.routeId);

            TourStop stop;
            if (p.climbIndex == PlannedClimb.WHOLE_ROUTE) {
                stop = TourStopFactory.fromWholeRoute(route, p.climbIndex,
                        routeName(route, p), sumAll(perClimb, route));
            } else if (route.climbs == null || p.climbIndex < 0
                    || p.climbIndex >= route.climbs.size()) {
                continue; // climb disappeared after a resync/merge: nothing to place
            } else {
                stop = TourStopFactory.fromClimb(route, p.climbIndex,
                        climbName(route, p), sumOne(perClimb, p.climbIndex));
            }
            if (stop != null) out.add(stop);
        }
        return out;
    }

    private static String routeName(StoredRoute route, PlannedClimb p) {
        if (route.userDisplayName != null) return route.userDisplayName;
        if (route.name != null) return route.name;
        return p.displayName != null ? p.displayName.trim() : "Route";
    }

    private static String climbName(StoredRoute route, PlannedClimb p) {
        StoredClimb c = route.climbs.get(p.climbIndex);
        if (c.userDisplayName != null) return c.userDisplayName;
        if (c.name != null) return c.name;
        return "Klim " + (p.climbIndex + 1) + " (" + routeName(route, p) + ")";
    }

    private static Integer sumOne(int[][] perClimb, int climbIndex) {
        if (perClimb == null || climbIndex < 0 || climbIndex >= perClimb.length
                || perClimb[climbIndex] == null) {
            return null;
        }
        int total = 0;
        for (int s : perClimb[climbIndex]) total += s;
        return total;
    }

    /** Whole-route climbing time: only when every climb on it has an estimate. */
    private static Integer sumAll(int[][] perClimb, StoredRoute route) {
        if (perClimb == null) return null;
        if (route.climbs == null || route.climbs.isEmpty()) return 0;
        int total = 0;
        for (int i = 0; i < perClimb.length; i++) {
            Integer one = sumOne(perClimb, i);
            if (one == null) return null;
            total += one;
        }
        return total;
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
