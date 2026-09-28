package nl.paree.climbpro.ui.fitness;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.rider.RiderProfileRepository;
import nl.paree.climbpro.domain.training.FitnessCalculator;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Fitness/fatigue/form from the ride archive and the rider's FTP (issue #220). */
public final class FitnessViewModel extends AndroidViewModel {

    public static final int WINDOW_DAYS = 90;

    public static final class State {
        public final FitnessCalculator.Result result;
        public final boolean ftpKnown;

        State(FitnessCalculator.Result result, boolean ftpKnown) {
            this.result = result;
            this.ftpKnown = ftpKnown;
        }
    }

    private final RideRepository rideRepo;
    private final RiderProfileRepository riderRepo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<State> state = new MutableLiveData<>();

    public FitnessViewModel(@NonNull Application app) {
        super(app);
        rideRepo = new RideRepository(app);
        riderRepo = new RiderProfileRepository(app);
    }

    public LiveData<State> state() { return state; }

    public void load() {
        executor.execute(() -> {
            int ftp = riderRepo.load().ftpWatts;
            ZoneId zone = ZoneId.systemDefault();
            state.postValue(new State(FitnessCalculator.compute(rideRepo.loadAll(), ftp,
                    LocalDate.now(zone), zone, WINDOW_DAYS), ftp > 0));
        });
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
