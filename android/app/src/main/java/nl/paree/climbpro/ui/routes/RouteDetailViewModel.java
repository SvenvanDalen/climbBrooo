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
import nl.paree.climbpro.data.route.StoredSurfaceSection;
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
    private final MutableLiveData<List<StoredSurfaceSection>> surfaceSections = new MutableLiveData<>();

    public RouteDetailViewModel(@NonNull Application app) {
        super(app);
        routeRepo = new RouteRepository(app);
    }

    public LiveData<StoredRoute>  route()      { return route; }
    public LiveData<List<Object>> routeItems() { return routeItems; }
    public LiveData<String>       error()      { return error; }
    public LiveData<Boolean>      saved()      { return saved; }
    public LiveData<List<StoredSurfaceSection>> surfaceSections() { return surfaceSections; }

    public void loadRoute(String routeId) {
        executor.execute(() -> {
            try {
                StoredRoute r = routeRepo.loadRoute(routeId);
                route.postValue(r);
                routeItems.postValue(buildRouteItems(r));
                surfaceSections.postValue(
                        r.surfaceSections != null ? r.surfaceSections : Collections.emptyList());
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
