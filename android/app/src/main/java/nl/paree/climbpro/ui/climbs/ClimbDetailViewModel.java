package nl.paree.climbpro.ui.climbs;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ClimbDetailViewModel extends AndroidViewModel {

    private final RouteRepository routeRepo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<StoredClimb> climb = new MutableLiveData<>();
    private final MutableLiveData<String>      error = new MutableLiveData<>();
    private final MutableLiveData<Boolean>     saved = new MutableLiveData<>(false);

    public ClimbDetailViewModel(@NonNull Application app) {
        super(app);
        routeRepo = new RouteRepository(app);
    }

    public LiveData<StoredClimb> climb() { return climb; }
    public LiveData<String>      error() { return error; }
    public LiveData<Boolean>     saved() { return saved; }

    public void loadClimb(String routeId, int climbIndex) {
        executor.execute(() -> {
            try {
                StoredRoute route = routeRepo.loadRoute(routeId);
                if (route.climbs != null && climbIndex < route.climbs.size()) {
                    climb.postValue(route.climbs.get(climbIndex));
                } else {
                    error.postValue("Climb not found");
                }
            } catch (Exception e) {
                error.postValue("Load failed: " + e.getMessage());
            }
        });
    }

    public void renameClimb(String routeId, int climbIndex, String newName) {
        executor.execute(() -> {
            try {
                routeRepo.renameClimb(routeId, climbIndex, newName);
                loadClimb(routeId, climbIndex);
                saved.postValue(true);
            } catch (Exception e) {
                error.postValue("Rename failed: " + e.getMessage());
            }
        });
    }

    public void reSegment(String routeId, int climbIndex, int newSegmentCount) {
        executor.execute(() -> {
            try {
                routeRepo.reSegmentClimb(routeId, climbIndex, newSegmentCount);
                loadClimb(routeId, climbIndex);
                saved.postValue(true);
            } catch (Exception e) {
                error.postValue("Herberekening mislukt: " + e.getMessage());
            }
        });
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
