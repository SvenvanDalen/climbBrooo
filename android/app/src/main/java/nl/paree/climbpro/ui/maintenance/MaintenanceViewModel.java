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

    /**
     * Creates ({@code id == null}) or edits a component, then stores its warranty (issue #239;
     * {@code warrantyMonths == 0} = none). See the repository for the corrected service date.
     */
    public void saveComponent(ComponentForm in) {
        executor.execute(() -> {
            try {
                String savedId = repo.upsertComponent(in.id, in.name, in.intervalKm, in.intervalMonths,
                        in.includeVirtualRides, in.correctedLastServicedEpochSec, in.bikeId);
                repo.setWarranty(savedId, in.warrantyPurchaseEpochSec, in.warrantyMonths);
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

    /** Arguments of {@link #saveComponent}. */
    public static final class ComponentForm {
        String id;
        String name;
        int intervalKm;
        int intervalMonths;
        boolean includeVirtualRides;
        long correctedLastServicedEpochSec;
        long warrantyPurchaseEpochSec;
        int warrantyMonths;
        String bikeId;

        public ComponentForm id(String id) {
            this.id = id;
            return this;
        }

        public ComponentForm name(String name) {
            this.name = name;
            return this;
        }

        public ComponentForm intervalKm(int intervalKm) {
            this.intervalKm = intervalKm;
            return this;
        }

        public ComponentForm intervalMonths(int intervalMonths) {
            this.intervalMonths = intervalMonths;
            return this;
        }

        public ComponentForm includeVirtualRides(boolean includeVirtualRides) {
            this.includeVirtualRides = includeVirtualRides;
            return this;
        }

        public ComponentForm correctedLastServicedEpochSec(long correctedLastServicedEpochSec) {
            this.correctedLastServicedEpochSec = correctedLastServicedEpochSec;
            return this;
        }

        public ComponentForm warrantyPurchaseEpochSec(long warrantyPurchaseEpochSec) {
            this.warrantyPurchaseEpochSec = warrantyPurchaseEpochSec;
            return this;
        }

        public ComponentForm warrantyMonths(int warrantyMonths) {
            this.warrantyMonths = warrantyMonths;
            return this;
        }

        public ComponentForm bikeId(String bikeId) {
            this.bikeId = bikeId;
            return this;
        }
    }
}
