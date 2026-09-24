package nl.paree.climbpro.ui.routes;

import android.app.Application;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.ride.YearlyDistanceGoalRepository;
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteCollectionRepository;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.strava.StravaAuthRepository;
import nl.paree.climbpro.domain.ride.YearlyDistanceGoalCalculator;
import nl.paree.climbpro.service.SyncScheduler;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RouteListViewModel extends AndroidViewModel {

    private final RouteRepository           routeRepo;
    private final RouteCollectionRepository collectionRepo;
    private final StravaAuthRepository      authRepo;
    private final RideRepository            rideRepo;
    private final YearlyDistanceGoalRepository goalRepo;
    private final ExecutorService      executor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<List<RouteCatalogEntry>> allRoutes   = new MutableLiveData<>();
    private final MutableLiveData<List<RouteCatalogEntry>> routes      = new MutableLiveData<>();
    private final MutableLiveData<String>                  error       = new MutableLiveData<>();
    private final MutableLiveData<Boolean>                 loading     = new MutableLiveData<>(false);
    private final MutableLiveData<YearlyDistanceGoalCalculator.Progress> yearlyGoal =
            new MutableLiveData<>();

    /** -1 = show all; 0–4 = filter by SurfaceType constant */
    private volatile int activeSurfaceFilter = -1;

    private static final String PREF_SORT_MODE = "route_sort_mode";

    /** Sort mode from {@link RouteSorting}; defaults to newest-at-bottom. */
    private volatile int activeSortMode = RouteSorting.SORT_IMPORT_ASC;

    /** Bucket-list filter from {@link RouteStatusFilter}; not persisted, starts at "Alle". */
    private volatile int activeStatusFilter = RouteStatusFilter.FILTER_ALL;

    public RouteListViewModel(@NonNull Application app) {
        super(app);
        routeRepo      = new RouteRepository(app);
        collectionRepo = new RouteCollectionRepository(app);
        authRepo       = new StravaAuthRepository(app);
        rideRepo       = new RideRepository(app);
        goalRepo       = new YearlyDistanceGoalRepository(app);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
        activeSortMode = prefs.getInt(PREF_SORT_MODE, RouteSorting.SORT_IMPORT_ASC);
        loadRoutes();
    }

    public LiveData<List<RouteCatalogEntry>> routes()  { return routes;  }
    /** Unfiltered catalog (the surface filter only narrows {@link #routes()}). */
    public LiveData<List<RouteCatalogEntry>> catalog() { return allRoutes; }
    public LiveData<String>                  error()   { return error;   }
    public LiveData<Boolean>                 loading() { return loading; }
    public boolean isSignedInToStrava() { return authRepo.isAuthorised(); }
    public LiveData<YearlyDistanceGoalCalculator.Progress> yearlyGoal() { return yearlyGoal; }

    /** Current yearly goal in km (0 = none); cheap SharedPreferences read. */
    public int getYearlyGoalKm() { return goalRepo.getGoalKm(); }

    /**
     * Recomputes the yearly km goal card (issue #157) from the ride archive, off the main
     * thread. Called on every resume so rides pulled in by a background sync show up.
     */
    public void loadYearlyGoal() {
        executor.execute(() -> yearlyGoal.postValue(YearlyDistanceGoalCalculator.compute(
                rideRepo.loadAll(), goalRepo.getGoalKm(),
                LocalDate.now(ZoneId.systemDefault()), ZoneId.systemDefault())));
    }

    /** Stores a new yearly goal ({@code ≤ 0} clears it) and refreshes the card. */
    public void setYearlyGoalKm(int goalKm) {
        goalRepo.setGoalKm(goalKm);
        loadYearlyGoal();
    }

    public void loadRoutes() {
        executor.execute(() -> {
            List<RouteCatalogEntry> all = routeRepo.loadCatalog();
            allRoutes.postValue(all);
            routes.postValue(applyView(all, activeSurfaceFilter, activeStatusFilter, activeSortMode));
        });
    }

    public void setSurfaceFilter(int surfaceType) {
        activeSurfaceFilter = surfaceType;
        List<RouteCatalogEntry> all = allRoutes.getValue();
        if (all != null) {
            routes.postValue(applyView(all, surfaceType, activeStatusFilter, activeSortMode));
        }
    }

    public int getSortMode() { return activeSortMode; }

    public void setSortMode(int sortMode) {
        activeSortMode = sortMode;
        PreferenceManager.getDefaultSharedPreferences(getApplication())
                .edit().putInt(PREF_SORT_MODE, sortMode).apply();
        List<RouteCatalogEntry> all = allRoutes.getValue();
        if (all != null) {
            routes.postValue(applyView(all, activeSurfaceFilter, activeStatusFilter, sortMode));
        }
    }

    public int getStatusFilter() { return activeStatusFilter; }

    public void setStatusFilter(int statusFilter) {
        activeStatusFilter = statusFilter;
        List<RouteCatalogEntry> all = allRoutes.getValue();
        if (all != null) {
            routes.postValue(applyView(all, activeSurfaceFilter, statusFilter, activeSortMode));
        }
    }

    public void deleteRoute(String routeId) {
        executor.execute(() -> {
            try {
                routeRepo.deleteRoute(routeId);
                collectionRepo.removeRouteEverywhere(routeId); // no dangling refs in collections
                loadRoutes();
            } catch (Exception e) {
                error.postValue("Delete failed: " + e.getMessage());
            }
        });
    }

    public void triggerSync() {
        SyncScheduler.triggerImmediateSync(getApplication());
    }

    /**
     * Applies the surface-type filter and the bucket-list status filter, then sorts
     * according to {@code sortMode}.
     */
    private static List<RouteCatalogEntry> applyView(
            List<RouteCatalogEntry> all, int surfaceType, int statusFilter, int sortMode) {
        List<RouteCatalogEntry> filtered;
        if (surfaceType == -1) {
            filtered = new ArrayList<>(all);
        } else {
            filtered = new ArrayList<>();
            for (RouteCatalogEntry e : all) {
                if (hasSurfaceType(e, surfaceType)) filtered.add(e);
            }
        }
        return RouteSorting.sort(RouteStatusFilter.apply(filtered, statusFilter), sortMode);
    }

    private static boolean hasSurfaceType(RouteCatalogEntry e, int surfaceType) {
        if (e.surfaceTypes == null) return false;
        for (int t : e.surfaceTypes) {
            if (t == surfaceType) return true;
        }
        return false;
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
