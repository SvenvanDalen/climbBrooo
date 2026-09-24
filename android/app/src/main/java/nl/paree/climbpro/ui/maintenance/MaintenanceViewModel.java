package nl.paree.climbpro.ui.maintenance;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.data.maintenance.MaintenanceRepository;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Maintenance tracker screen (issue #154): components with their wear status. */
public final class MaintenanceViewModel extends AndroidViewModel {

    private final MaintenanceRepository repo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<MaintenanceStatusLoader.Snapshot> snapshot = new MutableLiveData<>();
    private final MutableLiveData<String> message = new MutableLiveData<>();

    public MaintenanceViewModel(@NonNull Application app) {
        super(app);
        repo = new MaintenanceRepository(app);
    }

    public LiveData<MaintenanceStatusLoader.Snapshot> snapshot() { return snapshot; }
    public LiveData<String> message() { return message; }

    public void load() {
        executor.execute(this::loadNow);
    }

    /** "Gedaan": resets the component's last-serviced date to now. */
    public void markServiced(String id, String name) {
        executor.execute(() -> {
            try {
                repo.markServiced(id, System.currentTimeMillis() / 1000L);
                message.postValue(name + " bijgewerkt");
            } catch (Exception e) {
                message.postValue("Opslaan mislukt: " + e.getMessage());
            }
            loadNow();
        });
    }

    /** Creates ({@code id == null}) or edits a component; see the repository for the date. */
    public void saveComponent(String id, String name, int intervalKm, int intervalMonths,
                              boolean includeVirtualRides, long correctedLastServicedEpochSec) {
        executor.execute(() -> {
            try {
                repo.upsertComponent(id, name, intervalKm, intervalMonths,
                        includeVirtualRides, correctedLastServicedEpochSec);
            } catch (Exception e) {
                message.postValue("Opslaan mislukt: " + e.getMessage());
            }
            loadNow();
        });
    }

    public void deleteComponent(String id) {
        executor.execute(() -> {
            try {
                repo.deleteComponent(id);
            } catch (Exception e) {
                message.postValue("Verwijderen mislukt: " + e.getMessage());
            }
            loadNow();
        });
    }

    private void loadNow() {
        snapshot.postValue(
                MaintenanceStatusLoader.load(getApplication(), System.currentTimeMillis() / 1000L));
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
