package nl.paree.climbpro.ui.routes;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.preference.PreferenceManager;

import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredFlatSegment;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.service.RouteSyncWorker;
import nl.paree.climbpro.service.SyncScheduler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RouteDetailViewModel extends AndroidViewModel {

    private final RouteRepository routeRepo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<StoredRoute> route      = new MutableLiveData<>();
    private final MutableLiveData<List<Object>> routeItems = new MutableLiveData<>();
    private final MutableLiveData<String>       error      = new MutableLiveData<>();
    private final MutableLiveData<Boolean>      saved      = new MutableLiveData<>(false);

    public RouteDetailViewModel(@NonNull Application app) {
        super(app);
        routeRepo = new RouteRepository(app);
    }

    public LiveData<StoredRoute>  route()      { return route; }
    public LiveData<List<Object>> routeItems() { return routeItems; }
    public LiveData<String>       error()      { return error; }
    public LiveData<Boolean>      saved()      { return saved; }

    public void loadRoute(String routeId) {
        executor.execute(() -> {
            try {
                StoredRoute r = routeRepo.loadRoute(routeId);
                route.postValue(r);
                routeItems.postValue(buildRouteItems(r));
            } catch (Exception e) {
                error.postValue("Could not load route: " + e.getMessage());
            }
        });
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

    public void setActiveRoute(String routeId) {
        PreferenceManager.getDefaultSharedPreferences(getApplication())
                .edit()
                .putString(RouteSyncWorker.PREF_ROUTE_ID, routeId)
                .putString(RouteSyncWorker.PREF_MODE, RouteSyncWorker.MODE_ROUTE)
                .apply();
    }

    /** Persists the surface type for a flat segment identified by its startDistance. */
    public void setFlatSegmentSurface(String routeId, int startDistance, int surfaceType) {
        executor.execute(() -> {
            try {
                routeRepo.setFlatSegmentSurfaceType(routeId, startDistance, surfaceType);
                SyncScheduler.triggerImmediateSync(getApplication());
                loadRoute(routeId);
            } catch (Exception e) {
                error.postValue("Opslaan mislukt: " + e.getMessage());
            }
        });
    }

    private static List<Object> buildRouteItems(StoredRoute r) {
        List<StoredFlatSegment> flats  = r.flatSegments != null ? r.flatSegments : Collections.emptyList();
        List<StoredClimb>       climbs = r.climbs       != null ? r.climbs       : Collections.emptyList();

        List<Object> result = new ArrayList<>(flats.size() + climbs.size());
        int fi = 0, ci = 0;
        while (fi < flats.size() || ci < climbs.size()) {
            StoredFlatSegment flat  = fi < flats.size()  ? flats.get(fi)  : null;
            StoredClimb       climb = ci < climbs.size() ? climbs.get(ci) : null;

            if (flat != null && (climb == null || flat.startDistance <= climb.startDistance)) {
                result.add(flat);
                fi++;
            } else {
                result.add(climb);
                ci++;
            }
        }
        return result;
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
