package nl.paree.climbpro.ui.routes;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.preference.PreferenceManager;

import nl.paree.climbpro.ClimbProApplication;
import nl.paree.climbpro.data.rider.RiderProfileRepository;
import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.RouteRideStatus;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredFlatSegment;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredStarredSegment;
import nl.paree.climbpro.data.route.StoredSurfaceSection;
import nl.paree.climbpro.domain.climb.ClimbUsageClassifier;
import nl.paree.climbpro.domain.climb.ClimbUsageType;
import nl.paree.climbpro.domain.climb.HistoricClimbScoreCache;
import nl.paree.climbpro.domain.climb.RestSplitAdvisor;
import nl.paree.climbpro.domain.power.RiderProfile;
import nl.paree.climbpro.service.OnboardPushService;
import nl.paree.climbpro.service.RoutePacingPlanner;
import nl.paree.climbpro.service.RouteSyncWorker;
import nl.paree.climbpro.service.SyncScheduler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RouteDetailViewModel extends AndroidViewModel {

    private final RouteRepository routeRepo;
    private final RiderProfileRepository riderRepo;
    private final ClimbAttemptRepository attemptRepo;
    private final OnboardPushService onboardPushService;
    private final HistoricClimbScoreCache historicClimbScoreCache;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<StoredRoute> route      = new MutableLiveData<>();
    private final MutableLiveData<List<Object>> routeItems = new MutableLiveData<>();
    private final MutableLiveData<String>       error      = new MutableLiveData<>();
    private final MutableLiveData<Boolean>      saved      = new MutableLiveData<>(false);
    private final MutableLiveData<List<StoredSurfaceSection>> surfaceSections = new MutableLiveData<>();
    private final MutableLiveData<RoutePassport> passport = new MutableLiveData<>();
    private final MutableLiveData<int[]> climbTargetSeconds = new MutableLiveData<>();
    private final MutableLiveData<ClimbUsageType[]> climbUsageTypes = new MutableLiveData<>();
    private final MutableLiveData<String> onboardPushMessage = new MutableLiveData<>();
    /** Bucket-list status; separate from {@link #route} so a change doesn't re-render (and wipe unsaved) notes. */
    private final MutableLiveData<String> rideStatus = new MutableLiveData<>();
    private final MutableLiveData<List<RestSplitAdvisor.Suggestion>> restSuggestions =
            new MutableLiveData<>();

    public RouteDetailViewModel(@NonNull Application app) {
        super(app);
        routeRepo = new RouteRepository(app);
        riderRepo = new RiderProfileRepository(app);
        attemptRepo = new ClimbAttemptRepository(app);
        onboardPushService = new OnboardPushService(
                ((ClimbProApplication) app).connectIqClient());
        historicClimbScoreCache = ((ClimbProApplication) app).historicClimbScoreCache();
    }

    public LiveData<StoredRoute>  route()      { return route; }
    public LiveData<List<Object>> routeItems() { return routeItems; }
    public LiveData<String>       error()      { return error; }
    public LiveData<Boolean>      saved()      { return saved; }
    public LiveData<List<StoredSurfaceSection>> surfaceSections() { return surfaceSections; }
    public LiveData<RoutePassport> passport()           { return passport; }
    public LiveData<int[]>         climbTargetSeconds()  { return climbTargetSeconds; }
    public LiveData<ClimbUsageType[]> climbUsageTypes()  { return climbUsageTypes; }
    public LiveData<String> onboardPushMessage() { return onboardPushMessage; }
    public LiveData<String> rideStatus() { return rideStatus; }
    /** Rest-split suggestions (issue #22); see {@link RestSplitAdvisor}. */
    public LiveData<List<RestSplitAdvisor.Suggestion>> restSuggestions() { return restSuggestions; }

    public void loadRoute(String routeId) {
        executor.execute(() -> {
            try {
                StoredRoute r = routeRepo.loadRoute(routeId);
                route.postValue(r);
                rideStatus.postValue(RouteRideStatus.normalize(r.rideStatus));
                routeItems.postValue(buildRouteItems(r));
                RiderProfile profile = riderRepo.load();
                int[][] plan = RoutePacingPlanner.plan(r, profile);
                plan = nl.paree.climbpro.service.SegmentTargetOverrideMerger.merge(r, plan);
                passport.postValue(RoutePassport.from(r, plan));
                climbTargetSeconds.postValue(perClimbTotals(r, plan));
                List<StoredClimb> climbs = r.climbs != null ? r.climbs : Collections.emptyList();
                List<StoredClimbAttempt> attempts = attemptRepo.loadAll();
                climbUsageTypes.postValue(ClimbUsageClassifier.classifyAll(climbs, attempts));
                surfaceSections.postValue(
                        r.surfaceSections != null ? r.surfaceSections : Collections.emptyList());
                restSuggestions.postValue(computeRestSuggestions(r));
            } catch (Exception e) {
                error.postValue("Could not load route: " + e.getMessage());
            }
        });
    }

    /**
     * Builds the rider's historic per-climb difficulty baseline from stored attempts, then asks
     * {@link RestSplitAdvisor} which of this route's climbs are long + unusually hard for this
     * rider relative to that baseline. See {@link RestSplitAdvisor} class doc for the rationale.
     */
    private List<RestSplitAdvisor.Suggestion> computeRestSuggestions(StoredRoute r) {
        if (r == null || r.climbs == null || r.climbs.isEmpty()) return Collections.emptyList();
        // Cached at Application scope: this would otherwise re-scan the whole route catalog
        // from disk on every route-detail screen open. See HistoricClimbScoreCache class doc.
        List<Double> historicScores = historicClimbScoreCache.get(routeRepo, attemptRepo);
        return RestSplitAdvisor.suggest(r.climbs, historicScores);
    }

    public void renameRoute(String routeId, String newName) {
        executor.execute(() -> {
            try {
                routeRepo.renameRoute(routeId, newName);
                loadRoute(routeId);
                saved.postValue(true);
            } catch (Exception e) {
                error.postValue("Rename failed: " + e.getMessage());
            }
        });
    }

    public void saveNotes(String routeId, String notes) {
        executor.execute(() -> {
            try {
                routeRepo.saveNotes(routeId, notes);
                saved.postValue(true);
            } catch (Exception e) {
                error.postValue("Save failed: " + e.getMessage());
            }
        });
    }

    /** Sets the bucket-list status (issue #158); null clears it. */
    public void setRideStatus(String routeId, String status) {
        executor.execute(() -> {
            try {
                routeRepo.setRideStatus(routeId, status);
                rideStatus.postValue(RouteRideStatus.normalize(status));
            } catch (Exception e) {
                error.postValue("Status opslaan mislukt: " + e.getMessage());
            }
        });
    }

    public void setActiveRoute(String routeId) {
        PreferenceManager.getDefaultSharedPreferences(getApplication())
                .edit()
                .putString(RouteSyncWorker.PREF_ROUTE_ID, routeId)
                .putString(RouteSyncWorker.PREF_MODE, RouteSyncWorker.MODE_ROUTE)
                .apply();
    }

    /** Loads the route fresh and pushes its raw geometry to the onboard watch app. */
    public void sendToOnboard(String routeId) {
        executor.execute(() -> {
            try {
                StoredRoute r = routeRepo.loadRoute(routeId);
                boolean ok = onboardPushService.pushRoute(r);
                onboardPushMessage.postValue(
                        ok ? "Verstuurd naar horloge" : "Versturen naar horloge mislukt");
            } catch (Exception e) {
                onboardPushMessage.postValue("Versturen mislukt: " + e.getMessage());
            }
        });
    }

    /** Persists the surface type for a flat segment identified by its startDistance. */
    public void setFlatSegmentSurface(String routeId, int startDistance, int surfaceType) {
        updateFlatSegment(routeId, startDistance, surfaceType, null);
    }

    /** Sets a flat segment's surface type and optional name (phone + watch). */
    public void updateFlatSegment(String routeId, int startDistance,
                                  int surfaceType, String name) {
        executor.execute(() -> {
            try {
                routeRepo.updateFlatSegment(routeId, startDistance, surfaceType, name);
                loadRoute(routeId);
                saved.postValue(true);
            } catch (Exception e) {
                error.postValue("Opslaan mislukt: " + e.getMessage());
            }
        });
    }

    /** Sets a starred segment's surface type and optional name (phone + watch). */
    public void updateStarredSegment(String routeId, long stravaId, int surfaceType, String name) {
        executor.execute(() -> {
            try {
                routeRepo.updateStarredSegment(routeId, stravaId, surfaceType, name);
                loadRoute(routeId);
                saved.postValue(true);
            } catch (Exception e) {
                error.postValue("Kon ster-segment niet opslaan: " + e.getMessage());
            }
        });
    }

    /** Adds a user-defined surface override for an arbitrary stretch (phone + watch). */
    public void addSurfaceSection(String routeId, int startDistance, int endDistance,
                                  int surfaceType) {
        addSurfaceSection(routeId, startDistance, endDistance, surfaceType, null);
    }

    /** Adds a user-defined surface override for an arbitrary stretch (phone + watch). */
    public void addSurfaceSection(String routeId, int startDistance, int endDistance,
                                  int surfaceType, String name) {
        executor.execute(() -> {
            try {
                routeRepo.addSurfaceSection(routeId, startDistance, endDistance,
                        surfaceType, name);
                loadRoute(routeId);
                saved.postValue(true);
            } catch (IllegalArgumentException e) {
                error.postValue("Ongeldig stuk: " + e.getMessage());
            } catch (Exception e) {
                error.postValue("Opslaan mislukt: " + e.getMessage());
            }
        });
    }

    /** Renames the surface section at the given index (phone + watch). */
    public void setSurfaceSectionName(String routeId, int index, String name) {
        executor.execute(() -> {
            try {
                routeRepo.setSurfaceSectionName(routeId, index, name);
                loadRoute(routeId);
                saved.postValue(true);
            } catch (Exception e) {
                error.postValue("Hernoemen mislukt: " + e.getMessage());
            }
        });
    }

    /** Removes the surface section at the given index (phone-only). */
    public void deleteSurfaceSection(String routeId, int index) {
        executor.execute(() -> {
            try {
                routeRepo.deleteSurfaceSection(routeId, index);
                loadRoute(routeId);
                saved.postValue(true);
            } catch (Exception e) {
                error.postValue("Verwijderen mislukt: " + e.getMessage());
            }
        });
    }

    /** Updates a surface section's surface type and name by index (phone + watch). */
    public void updateSurfaceSection(String routeId, int index, int surfaceType, String name) {
        executor.execute(() -> {
            try {
                routeRepo.updateSurfaceSection(routeId, index, surfaceType, name);
                loadRoute(routeId);
                saved.postValue(true);
            } catch (Exception e) {
                error.postValue("Kon ondergrond-stuk niet opslaan: " + e.getMessage());
            }
        });
    }

    static List<Object> buildRouteItems(StoredRoute r) {
        List<StoredFlatSegment>    flats   = r.flatSegments    != null ? r.flatSegments    : Collections.emptyList();
        List<StoredClimb>          climbs  = r.climbs          != null ? r.climbs          : Collections.emptyList();
        List<StoredStarredSegment> starred = r.starredSegments != null ? r.starredSegments : Collections.emptyList();
        List<StoredSurfaceSection> surfsec = r.surfaceSections != null ? r.surfaceSections : Collections.emptyList();

        List<Object> result = new ArrayList<>(
                flats.size() + climbs.size() + starred.size() + surfsec.size());
        int fi = 0, ci = 0, si = 0, ui = 0;
        while (fi < flats.size() || ci < climbs.size() || si < starred.size() || ui < surfsec.size()) {
            int flatPos    = fi < flats.size()   ? flats.get(fi).startDistance    : Integer.MAX_VALUE;
            int climbPos   = ci < climbs.size()  ? climbs.get(ci).startDistance   : Integer.MAX_VALUE;
            int starredPos = si < starred.size() ? starred.get(si).startDistance  : Integer.MAX_VALUE;
            int surfPos    = ui < surfsec.size() ? surfsec.get(ui).startDistance  : Integer.MAX_VALUE;

            int min = Math.min(Math.min(flatPos, climbPos), Math.min(starredPos, surfPos));
            if (flatPos == min)         result.add(flats.get(fi++));
            else if (surfPos == min)    result.add(surfsec.get(ui++));
            else if (starredPos == min) result.add(starred.get(si++));
            else                        result.add(climbs.get(ci++));
        }
        return result;
    }

    /** Per-climb total target seconds (index = climb position); -1 when that climb has no plan. */
    private static int[] perClimbTotals(StoredRoute r, int[][] plan) {
        int n = r.climbs != null ? r.climbs.size() : 0;
        int[] totals = new int[n];
        for (int ci = 0; ci < n; ci++) {
            int[] segs = (plan != null && ci < plan.length) ? plan[ci] : null;
            if (segs == null) {
                totals[ci] = -1;
            } else {
                int sum = 0;
                for (int s : segs) sum += s;
                totals[ci] = sum;
            }
        }
        return totals;
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
