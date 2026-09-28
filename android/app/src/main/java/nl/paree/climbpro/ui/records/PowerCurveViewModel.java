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
import nl.paree.climbpro.data.rider.RiderProfileRepository;
import nl.paree.climbpro.domain.ride.PowerCurveCalculator;
import nl.paree.climbpro.domain.ride.RideStreamAnalyzer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Power curve for the selected period plus all time (issue #219), off the main thread. */
public final class PowerCurveViewModel extends AndroidViewModel {

    public static final class State {
        public final PowerCurveCalculator.Period period;
        public final PowerCurveCalculator.Result result;
        public final PowerCurveCalculator.Result allTime;
        /** Rider weight for W/kg; 0 when not set. */
        public final double weightKg;
        /** Archived rides whose streams haven't been analyzed (for the curve) yet. */
        public final int ridesAwaitingAnalysis;
        public final int archivedRides;

        State(PowerCurveCalculator.Period period, PowerCurveCalculator.Result result,
              PowerCurveCalculator.Result allTime, double weightKg, int ridesAwaitingAnalysis,
              int archivedRides) {
            this.period = period;
            this.result = result;
            this.allTime = allTime;
            this.weightKg = weightKg;
            this.ridesAwaitingAnalysis = ridesAwaitingAnalysis;
            this.archivedRides = archivedRides;
        }
    }

    private final RideRepository rideRepo;
    private final RideStreamStatsRepository statsRepo;
    private final RiderProfileRepository profileRepo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<State> state = new MutableLiveData<>();
    private volatile PowerCurveCalculator.Period period = PowerCurveCalculator.Period.DAYS_90;

    public PowerCurveViewModel(@NonNull Application app) {
        super(app);
        rideRepo = new RideRepository(app);
        statsRepo = new RideStreamStatsRepository(app);
        profileRepo = new RiderProfileRepository(app);
    }

    public LiveData<State> state() { return state; }

    public PowerCurveCalculator.Period period() { return period; }

    public void setPeriod(PowerCurveCalculator.Period period) {
        if (period == this.period) return;
        this.period = period;
        load();
    }

    public void load() {
        PowerCurveCalculator.Period p = period;
        executor.execute(() -> {
            List<StoredRide> rides = rideRepo.loadAll();
            Map<Long, StoredRideStreamStats> byId = statsRepo.loadById();
            int awaiting = 0;
            for (StoredRide r : rides) {
                StoredRideStreamStats s = byId.get(r.activityId);
                if (s == null || s.version < RideStreamAnalyzer.VERSION) awaiting++;
            }
            List<StoredRideStreamStats> stats = new ArrayList<>(byId.values());
            long now = System.currentTimeMillis() / 1000L;
            state.postValue(new State(p,
                    PowerCurveCalculator.compute(rides, stats, p, now),
                    PowerCurveCalculator.compute(rides, stats, PowerCurveCalculator.Period.ALL, now),
                    profileRepo.load().riderWeightKg, awaiting, rides.size()));
        });
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
