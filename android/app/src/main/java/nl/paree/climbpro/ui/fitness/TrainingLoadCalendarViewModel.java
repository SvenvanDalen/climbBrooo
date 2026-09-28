package nl.paree.climbpro.ui.fitness;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.rider.RiderProfileRepository;
import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.domain.training.TrainingLoadCalendar;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Training-load calendar from the ride archive, climb attempts and FTP (issue #182). */
public final class TrainingLoadCalendarViewModel extends AndroidViewModel {

    /** A year of weeks, like the GitHub contribution graph. */
    public static final int WEEKS = 53;

    public static final class State {
        public final TrainingLoadCalendar.Result result;
        public final boolean ftpKnown;

        State(TrainingLoadCalendar.Result result, boolean ftpKnown) {
            this.result = result;
            this.ftpKnown = ftpKnown;
        }
    }

    private final RideRepository rideRepo;
    private final ClimbAttemptRepository attemptRepo;
    private final RiderProfileRepository riderRepo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<State> state = new MutableLiveData<>();

    public TrainingLoadCalendarViewModel(@NonNull Application app) {
        super(app);
        rideRepo = new RideRepository(app);
        attemptRepo = new ClimbAttemptRepository(app);
        riderRepo = new RiderProfileRepository(app);
    }

    public LiveData<State> state() { return state; }

    public void load() {
        executor.execute(() -> {
            int ftp = riderRepo.load().ftpWatts;
            ZoneId zone = ZoneId.systemDefault();
            state.postValue(new State(TrainingLoadCalendar.compute(rideRepo.loadAll(),
                    attemptRepo.loadAll(), ftp, LocalDate.now(zone), zone, WEEKS), ftp > 0));
        });
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
