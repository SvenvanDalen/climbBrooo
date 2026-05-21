package nl.paree.climbpro.ui.routes;

import android.app.Application;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.preference.PreferenceManager;

import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.service.RouteSyncWorker;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RouteDetailViewModel extends AndroidViewModel {

    private final RouteRepository routeRepo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<StoredRoute> route   = new MutableLiveData<>();
    private final MutableLiveData<String>      error   = new MutableLiveData<>();
    private final MutableLiveData<Boolean>     saved   = new MutableLiveData<>(false);

    public RouteDetailViewModel(@NonNull Application app) {
        super(app);
        routeRepo = new RouteRepository(app);
    }

    public LiveData<StoredRoute> route() { return route; }
    public LiveData<String>      error() { return error; }
    public LiveData<Boolean>     saved() { return saved; }

    public void loadRoute(String routeId) {
        executor.execute(() -> {
            try {
                route.postValue(routeRepo.loadRoute(routeId));
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

    @Override
    protected void onCleared() { executor.shutdown(); }
}
