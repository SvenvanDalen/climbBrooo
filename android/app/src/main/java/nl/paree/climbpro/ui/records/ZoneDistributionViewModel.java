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
import nl.paree.climbpro.domain.ride.RideStreamAnalyzer;
import nl.paree.climbpro.domain.ride.ZoneCalculator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Time in heart-rate and power zones per ride (issue #218), off the main thread. */
public final class ZoneDistributionViewModel extends AndroidViewModel {

    public static final class State {
        public final ZoneCalculator.Result result;
        /** Max heart rate the zones use: the rider's own, else the observed one; 0 = none. */
        public final int maxHr;
        /** True when {@link #maxHr} was set by the rider, false when observed from rides. */
        public final boolean maxHrIsSet;
        public final int ftp;
        public final int ridesAwaitingAnalysis;
        public final int archivedRides;

        State(ZoneCalculator.Result result, int maxHr, boolean maxHrIsSet, int ftp,
              int ridesAwaitingAnalysis, int archivedRides) {
            this.result = result;
            this.maxHr = maxHr;
            this.maxHrIsSet = maxHrIsSet;
            this.ftp = ftp;
            this.ridesAwaitingAnalysis = ridesAwaitingAnalysis;
            this.archivedRides = archivedRides;
        }
    }

    private final RideRepository rideRepo;
    private final RideStreamStatsRepository statsRepo;
    private final RiderProfileRepository profileRepo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<State> state = new MutableLiveData<>();

    public ZoneDistributionViewModel(@NonNull Application app) {
        super(app);
        rideRepo = new RideRepository(app);
        statsRepo = new RideStreamStatsRepository(app);
        profileRepo = new RiderProfileRepository(app);
    }

    public LiveData<State> state() { return state; }

    /** Saves the rider's max heart rate (0 clears it) and recomputes the zones. */
    public void setMaxHeartRate(int bpm) {
        profileRepo.saveMaxHeartRate(bpm);
        load();
    }

    public void load() {
        executor.execute(() -> {
            List<StoredRide> rides = rideRepo.loadAll();
            Map<Long, StoredRideStreamStats> byId = statsRepo.loadById();
            int awaiting = 0;
            for (StoredRide r : rides) {
                StoredRideStreamStats s = byId.get(r.activityId);
                if (s == null || s.version < RideStreamAnalyzer.VERSION) awaiting++;
            }
            List<StoredRideStreamStats> stats = new ArrayList<>(byId.values());
            int setMax = profileRepo.loadMaxHeartRate();
            int maxHr = setMax > 0 ? setMax : ZoneCalculator.observedMaxHr(stats);
            int ftp = profileRepo.load().ftpWatts;
            state.postValue(new State(ZoneCalculator.compute(rides, stats, maxHr, ftp,
                    System.currentTimeMillis() / 1000L), maxHr, setMax > 0, ftp, awaiting,
                    rides.size()));
        });
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
