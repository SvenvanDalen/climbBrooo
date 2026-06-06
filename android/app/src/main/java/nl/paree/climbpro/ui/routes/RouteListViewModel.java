package nl.paree.climbpro.ui.routes;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.strava.StravaAuthRepository;
import nl.paree.climbpro.domain.segment.SurfaceType;
import nl.paree.climbpro.service.SyncScheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RouteListViewModel extends AndroidViewModel {

    private final RouteRepository      routeRepo;
    private final StravaAuthRepository authRepo;
    private final ExecutorService      executor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<List<RouteCatalogEntry>> allRoutes   = new MutableLiveData<>();
    private final MutableLiveData<List<RouteCatalogEntry>> routes      = new MutableLiveData<>();
    private final MutableLiveData<String>                  error       = new MutableLiveData<>();
    private final MutableLiveData<Boolean>                 loading     = new MutableLiveData<>(false);

    /** -1 = show all; 0–4 = filter by SurfaceType constant */
    private int activeSurfaceFilter = -1;

    public RouteListViewModel(@NonNull Application app) {
        super(app);
        routeRepo = new RouteRepository(app);
        authRepo  = new StravaAuthRepository(app);
        loadRoutes();
    }

    public LiveData<List<RouteCatalogEntry>> routes()  { return routes;  }
    public LiveData<String>                  error()   { return error;   }
    public LiveData<Boolean>                 loading() { return loading; }
    public boolean isSignedInToStrava() { return authRepo.isAuthorised(); }

    public void loadRoutes() {
        executor.execute(() -> {
            List<RouteCatalogEntry> all = routeRepo.loadCatalog();
            allRoutes.postValue(all);
            routes.postValue(applyFilter(all, activeSurfaceFilter));
        });
    }

    public void setSurfaceFilter(int surfaceType) {
        activeSurfaceFilter = surfaceType;
        List<RouteCatalogEntry> all = allRoutes.getValue();
        if (all != null) {
            routes.postValue(applyFilter(all, surfaceType));
        }
    }

    public void deleteRoute(String routeId) {
        executor.execute(() -> {
            try {
                routeRepo.deleteRoute(routeId);
                loadRoutes();
            } catch (Exception e) {
                error.postValue("Delete failed: " + e.getMessage());
            }
        });
    }

    public void triggerSync() {
        SyncScheduler.triggerImmediateSync(getApplication());
    }

    /** Returns routes matching the filter. -1 means "all". */
    private static List<RouteCatalogEntry> applyFilter(List<RouteCatalogEntry> all, int surfaceType) {
        if (surfaceType == -1) return all;
        List<RouteCatalogEntry> result = new ArrayList<>();
        for (RouteCatalogEntry e : all) {
            if (hasSurfaceType(e, surfaceType)) result.add(e);
        }
        return result;
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
