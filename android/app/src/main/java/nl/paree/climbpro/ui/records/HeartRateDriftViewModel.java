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
import nl.paree.climbpro.domain.ride.HeartRateDriftCalculator;
import nl.paree.climbpro.domain.ride.RideStreamAnalyzer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Heart-rate drift per ride and its six-week trend (issue #222), off the main thread. */
public final class HeartRateDriftViewModel extends AndroidViewModel {

    public static final class State {
        public final HeartRateDriftCalculator.Result result;
        /** Archived rides whose streams haven't been analyzed (for drift) yet. */
        public final int ridesAwaitingAnalysis;
        public final int archivedRides;

        State(HeartRateDriftCalculator.Result result, int ridesAwaitingAnalysis, int archivedRides) {
            this.result = result;
            this.ridesAwaitingAnalysis = ridesAwaitingAnalysis;
            this.archivedRides = archivedRides;
        }
    }

    private final RideRepository rideRepo;
    private final RideStreamStatsRepository statsRepo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<State> state = new MutableLiveData<>();

    public HeartRateDriftViewModel(@NonNull Application app) {
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
                StoredRideStreamStats s = stats.get(r.activityId);
                if (s == null || s.version < RideStreamAnalyzer.VERSION) awaiting++;
            }
            state.postValue(new State(HeartRateDriftCalculator.compute(rides,
                    new java.util.ArrayList<>(stats.values()),
                    System.currentTimeMillis() / 1000L), awaiting, rides.size()));
        });
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
