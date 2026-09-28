package nl.paree.climbpro.ui.bike;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.data.bike.Bike;
import nl.paree.climbpro.data.bike.BikeCostLog;
import nl.paree.climbpro.data.bike.BikeCostRepository;
import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.domain.bike.BikeCostCalculator;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Bike cost overview (issue #233): all file IO on a single background thread. */
public final class BikeCostViewModel extends AndroidViewModel {

    private final BikeCostRepository repo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<List<BikeCostCalculator.Summary>> summaries =
            new MutableLiveData<>();
    private final MutableLiveData<String> message = new MutableLiveData<>();

    public BikeCostViewModel(@NonNull Application app) {
        super(app);
        repo = new BikeCostRepository(app);
    }

    public LiveData<List<BikeCostCalculator.Summary>> summaries() { return summaries; }
    public LiveData<String> message() { return message; }

    public void load() {
        executor.execute(this::loadNow);
    }

    public void saveBike(String id, String name, long sinceEpochSec, boolean countArchiveRides,
                         boolean includeVirtualRides, int extraKm, boolean retired) {
        executor.execute(() -> {
            try {
                repo.upsertBike(id, name, sinceEpochSec, countArchiveRides, includeVirtualRides,
                        extraKm, retired, System.currentTimeMillis() / 1000L);
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

    public void addCost(String bikeId, String kind, String description, long amountCents) {
        executor.execute(() -> {
            try {
                if (repo.addCost(bikeId, kind, description, amountCents,
                        System.currentTimeMillis() / 1000L) == null) {
                    message.postValue("Kosten niet opgeslagen");
                }
            } catch (Exception e) {
                message.postValue("Opslaan mislukt: " + e.getMessage());
            }
            loadNow();
        });
    }

    public void deleteCost(String bikeId, String costId) {
        executor.execute(() -> {
            try {
                repo.deleteCost(bikeId, costId);
            } catch (Exception e) {
                message.postValue("Verwijderen mislukt: " + e.getMessage());
            }
            loadNow();
        });
    }

    private void loadNow() {
        BikeCostLog log = repo.load();
        boolean needRides = false;
        for (Bike b : log.bikes) {
            if (b.countArchiveRides && b.sinceEpochSec > 0) {
                needRides = true;
                break;
            }
        }
        List<StoredRide> rides = needRides
                ? new RideRepository(getApplication()).loadAll() : null;
        summaries.postValue(BikeCostCalculator.evaluateAll(log.bikes, rides));
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
