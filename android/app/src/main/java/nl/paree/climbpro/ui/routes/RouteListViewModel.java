package nl.paree.climbpro.ui.routes;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.strava.StravaAuthRepository;
import nl.paree.climbpro.service.SyncScheduler;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RouteListViewModel extends AndroidViewModel {

    private final RouteRepository      routeRepo;
    private final StravaAuthRepository authRepo;
    private final ExecutorService      executor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<List<RouteCatalogEntry>> routes = new MutableLiveData<>();
    private final MutableLiveData<String>                  error  = new MutableLiveData<>();
    private final MutableLiveData<Boolean>                 loading = new MutableLiveData<>(false);

    public RouteListViewModel(@NonNull Application app) {
        super(app);
        routeRepo = new RouteRepository(app);
        authRepo  = new StravaAuthRepository(app);
        loadRoutes();
    }

    public LiveData<List<RouteCatalogEntry>> routes()  { return routes;  }
    public LiveData<String>                  error()   { return error;   }
    public LiveData<Boolean>                 loading() { return loading; }
    public boolean                           isSignedInToStrava() { return authRepo.isAuthorised(); }

    public void loadRoutes() {
        executor.execute(() -> routes.postValue(routeRepo.loadCatalog()));
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

    @Override
    protected void onCleared() {
        executor.shutdown();
    }
}
