package nl.paree.climbpro.ui.bike;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.data.bike.BikeCostLog;
import nl.paree.climbpro.data.bike.BikeCostRepository;
import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.domain.bike.BikeGarage;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Bike garage screen (issue #187): all file IO on a single background thread. */
public final class BikeGarageViewModel extends AndroidViewModel {

    /** The garage plus what the ride archive says about it. */
    public static final class State {
        public final BikeCostLog garage;
        /** Per bike id: {@code {rides, meters}} of the rides assigned to it. */
        public final Map<String, long[]> totals;
        /** Strava gear ids seen in the archive, most-ridden first. */
        public final List<BikeGarage.GearUsage> gears;

        State(BikeCostLog garage, Map<String, long[]> totals, List<BikeGarage.GearUsage> gears) {
            this.garage = garage;
            this.totals = totals;
            this.gears = gears;
        }
    }

    private final BikeCostRepository repo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<State> state = new MutableLiveData<>();
    private final MutableLiveData<String> message = new MutableLiveData<>();

    public BikeGarageViewModel(@NonNull Application app) {
        super(app);
        repo = new BikeCostRepository(app);
    }

    public LiveData<State> state() { return state; }
    public LiveData<String> message() { return message; }

    public void load() {
        executor.execute(this::loadNow);
    }

    public void saveBike(String id, String name, String type, double weightKg, int tyreWidthMm,
                         String chainrings, String cassette, String stravaGearId,
                         boolean active, boolean indoor) {
        executor.execute(() -> {
            try {
                repo.saveGarageBike(id, name, type, weightKg, tyreWidthMm, chainrings, cassette,
                        stravaGearId, active, indoor);
            } catch (Exception e) {
                message.postValue("Opslaan mislukt: " + e.getMessage());
            }
            loadNow();
        });
    }

    public void deleteBike(String id) {
        executor.execute(() -> {
            try {
                repo.deleteBike(id);
            } catch (Exception e) {
                message.postValue("Verwijderen mislukt: " + e.getMessage());
            }
            loadNow();
        });
    }

    private void loadNow() {
        BikeCostLog garage = repo.load();
        List<StoredRide> rides = new RideRepository(getApplication()).loadAll();
        state.postValue(new State(garage, BikeGarage.totalsPerBike(rides, garage),
                BikeGarage.gearIdsInArchive(rides)));
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
