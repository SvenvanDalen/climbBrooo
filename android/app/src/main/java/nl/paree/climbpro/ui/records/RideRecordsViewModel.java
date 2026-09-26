package nl.paree.climbpro.ui.records;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.ride.RideStreamStatsRepository;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.ride.StoredRideStreamStats;
import nl.paree.climbpro.domain.ride.FastestDistanceCalculator;
import nl.paree.climbpro.domain.ride.RideRecordsCalculator;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Personal records outside climbing (issue #156), computed from the ride archive off the main
 * thread, plus the fastest 10/40/100 km from the stream analysis (issue #225). Phone-only.
 */
public final class RideRecordsViewModel extends AndroidViewModel {

    /** Everything the screen shows. */
    public static final class State {
        public final RideRecordsCalculator.Records records;
        public final List<FastestDistanceCalculator.Distance> fastest;
        /** Archived rides whose streams haven't been analyzed yet. */
        public final int ridesAwaitingAnalysis;

        State(RideRecordsCalculator.Records records,
              List<FastestDistanceCalculator.Distance> fastest, int ridesAwaitingAnalysis) {
            this.records = records;
            this.fastest = fastest;
            this.ridesAwaitingAnalysis = ridesAwaitingAnalysis;
        }
    }

    private final RideRepository rideRepo;
    private final RideStreamStatsRepository statsRepo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<State> state = new MutableLiveData<>();

    public RideRecordsViewModel(@NonNull Application app) {
        super(app);
        rideRepo = new RideRepository(app);
        statsRepo = new RideStreamStatsRepository(app);
    }

    public LiveData<State> state() { return state; }

    public void load() {
        executor.execute(() -> {
            List<StoredRide> rides = rideRepo.loadAll();
            Map<Long, StoredRideStreamStats> stats = statsRepo.loadById();
            int awaiting = 0;
            for (StoredRide r : rides) {
                if (!stats.containsKey(r.activityId)) awaiting++;
            }
            state.postValue(new State(
                    RideRecordsCalculator.compute(rides, ZoneId.systemDefault()),
                    FastestDistanceCalculator.compute(rides, new java.util.ArrayList<>(stats.values())),
                    awaiting));
        });
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
