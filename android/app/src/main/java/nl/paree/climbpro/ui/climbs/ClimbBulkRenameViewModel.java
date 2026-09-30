package nl.paree.climbpro.ui.climbs;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Backs the bulk rename screen (backlog #108): renames every climb of a route in one save. */
public final class ClimbBulkRenameViewModel extends AndroidViewModel {

    private final RouteRepository routeRepo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<List<StoredClimb>> climbs = new MutableLiveData<>();
    private final MutableLiveData<String>             error = new MutableLiveData<>();
    private final MutableLiveData<Boolean>            saved = new MutableLiveData<>();

    public ClimbBulkRenameViewModel(@NonNull Application app) {
        super(app);
        routeRepo = new RouteRepository(app);
    }

    public LiveData<List<StoredClimb>> climbs() { return climbs; }
    public LiveData<String>            error()  { return error; }
    public LiveData<Boolean>           saved()  { return saved; }

    public void loadClimbs(String routeId) {
        executor.execute(() -> {
            try {
                List<StoredClimb> loaded = routeRepo.loadRoute(routeId).climbs;
                climbs.postValue(loaded != null ? loaded : Collections.emptyList());
            } catch (Exception e) {
                error.postValue("Kon klimmen niet laden: " + e.getMessage());
            }
        });
    }

    public void saveNames(String routeId, Map<Integer, String> namesByIndex) {
        executor.execute(() -> {
            try {
                routeRepo.renameClimbs(routeId, namesByIndex);
                saved.postValue(true);
            } catch (Exception e) {
                error.postValue("Opslaan mislukt: " + e.getMessage());
            }
        });
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
