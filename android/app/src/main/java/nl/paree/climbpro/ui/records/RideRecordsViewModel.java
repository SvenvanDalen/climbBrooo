package nl.paree.climbpro.ui.records;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.domain.ride.RideRecordsCalculator;

import java.time.ZoneId;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Personal records outside climbing (issue #156), computed from the ride archive off the main
 * thread. Phone-only.
 */
public final class RideRecordsViewModel extends AndroidViewModel {

    private final RideRepository rideRepo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<RideRecordsCalculator.Records> records = new MutableLiveData<>();

    public RideRecordsViewModel(@NonNull Application app) {
        super(app);
        rideRepo = new RideRepository(app);
    }

    public LiveData<RideRecordsCalculator.Records> records() { return records; }

    public void load() {
        executor.execute(() -> records.postValue(
                RideRecordsCalculator.compute(rideRepo.loadAll(), ZoneId.systemDefault())));
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
