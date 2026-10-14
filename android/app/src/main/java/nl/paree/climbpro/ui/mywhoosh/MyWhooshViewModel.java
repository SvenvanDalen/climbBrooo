package nl.paree.climbpro.ui.mywhoosh;

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
import nl.paree.climbpro.data.rider.WeightLogStore;
import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.domain.mywhoosh.CadenceByGrade;
import nl.paree.climbpro.domain.mywhoosh.HeartRateRecoveryTrend;
import nl.paree.climbpro.domain.mywhoosh.IndoorRides;
import nl.paree.climbpro.domain.mywhoosh.IndoorSeasonSummary;
import nl.paree.climbpro.domain.mywhoosh.MyWhooshRouteCatalog;
import nl.paree.climbpro.domain.mywhoosh.PowerDecoupling;
import nl.paree.climbpro.domain.mywhoosh.RaceDetector;
import nl.paree.climbpro.domain.mywhoosh.RideIntensity;
import nl.paree.climbpro.domain.power.RiderProfile;
import nl.paree.climbpro.domain.ride.RideStreamAnalyzer;
import nl.paree.climbpro.domain.rider.WeightHistory;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Everything on the MyWhoosh screen, computed off the main thread from the ride archive, the
 * stream analysis and the climb logbook (issues #391, #399, #400, #402, #403, #404, #406,
 * #407, #409). Phone-only.
 */
public final class MyWhooshViewModel extends AndroidViewModel {

    /** Rides with NP/IF/TSS shown. */
    static final int MAX_INTENSITY_ROWS = 20;
    /** Most recent rides whose intervals are listed (#400). */
    static final int MAX_INTERVAL_RIDES = 5;
    /** Rides with a Pw:HR decoupling verdict (#399). */
    static final int MAX_DECOUPLING_ROWS = 10;

    public static final class RideRow {
        public final StoredRide ride;
        /** Null without power or FTP. */
        public final RideIntensity intensity;
        /** Mechanical work from the power stream (#404); null without power. */
        public final Integer workKj;

        RideRow(StoredRide ride, RideIntensity intensity, Integer workKj) {
            this.ride = ride;
            this.intensity = intensity;
            this.workKj = workKj;
        }
    }

    /** A ride and its intervals (#400). */
    public static final class IntervalRide {
        public final StoredRide ride;
        public final List<StoredRideStreamStats.Interval> intervals;

        IntervalRide(StoredRide ride, List<StoredRideStreamStats.Interval> intervals) {
            this.ride = ride;
            this.intervals = intervals;
        }
    }

    public static final class State {
        public final int myWhooshRides;
        public final int awaitingAnalysis;
        public final int ftpWatts;
        public final List<IndoorSeasonSummary.Season> seasons;
        public final List<MyWhooshRouteCatalog.Entry> catalog;
        /** Catalog key → route id of the imported MyWhoosh route, when there is one. */
        public final Map<String, String> routeIds;
        public final List<RideRow> rides;
        public final CadenceByGrade.Result cadence;
        public final HeartRateRecoveryTrend.Result recovery;
        public final List<RaceDetector.Race> races;
        public final List<IntervalRide> intervalRides;
        public final List<PowerDecoupling.Entry> decoupling;

        @SuppressWarnings("java:S107") // one immutable snapshot of the whole screen
        State(int myWhooshRides, int awaitingAnalysis, int ftpWatts,
              List<IndoorSeasonSummary.Season> seasons, List<MyWhooshRouteCatalog.Entry> catalog,
              Map<String, String> routeIds, List<RideRow> rides, CadenceByGrade.Result cadence,
              HeartRateRecoveryTrend.Result recovery, List<RaceDetector.Race> races,
              List<IntervalRide> intervalRides, List<PowerDecoupling.Entry> decoupling) {
            this.myWhooshRides = myWhooshRides;
            this.awaitingAnalysis = awaitingAnalysis;
            this.ftpWatts = ftpWatts;
            this.seasons = seasons;
            this.catalog = catalog;
            this.routeIds = routeIds;
            this.rides = rides;
            this.cadence = cadence;
            this.recovery = recovery;
            this.races = races;
            this.intervalRides = intervalRides;
            this.decoupling = decoupling;
        }
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<State> state = new MutableLiveData<>();

    public MyWhooshViewModel(@NonNull Application app) {
        super(app);
    }

    public LiveData<State> state() { return state; }

    public void load() {
        executor.execute(() -> {
            Application app = getApplication();
            List<StoredRide> rides = new RideRepository(app).loadAll();
            Map<Long, StoredRideStreamStats> stats = new RideStreamStatsRepository(app).loadById();
            RiderProfile profile = new RiderProfileRepository(app).load();
            int ftp = profile.ftpWatts;
            WeightHistory weights = WeightLogStore.of(app).history(profile.riderWeightKg,
                    ZoneId.systemDefault());

            List<StoredRide> myWhoosh = new ArrayList<>();
            int awaiting = 0;
            for (StoredRide r : rides) {
                if (!IndoorRides.isMyWhoosh(r)) continue;
                myWhoosh.add(r);
                StoredRideStreamStats s = stats.get(r.activityId);
                if (s == null || s.version < RideStreamAnalyzer.VERSION) awaiting++;
            }
            myWhoosh.sort((a, b) -> Long.compare(b.startEpochSec, a.startEpochSec));
            // #407: races get their own list and stay out of the training rows.
            List<RaceDetector.Race> races = RaceDetector.detect(myWhoosh, stats, ftp, weights);
            Set<Long> raceIds = new HashSet<>();
            for (RaceDetector.Race race : races) raceIds.add(race.ride.activityId);
            List<StoredRide> training = new ArrayList<>();
            for (StoredRide r : myWhoosh) if (!raceIds.contains(r.activityId)) training.add(r);

            List<RideRow> rows = new ArrayList<>();
            List<IntervalRide> intervalRides = new ArrayList<>();
            for (StoredRide r : training) {
                StoredRideStreamStats s = stats.get(r.activityId);
                if (rows.size() < MAX_INTENSITY_ROWS) {
                    rows.add(new RideRow(r, RideIntensity.of(r, s, ftp), s != null ? s.workKj : null));
                }
                if (intervalRides.size() < MAX_INTERVAL_RIDES && s != null && s.intervals != null
                        && !s.intervals.isEmpty()) {
                    intervalRides.add(new IntervalRide(r, s.intervals));
                }
            }
            long now = System.currentTimeMillis() / 1000L;
            state.postValue(new State(myWhoosh.size(), awaiting, ftp,
                    IndoorSeasonSummary.compute(rides, stats,
                            new ClimbAttemptRepository(app).loadAll(), ftp, ZoneId.systemDefault()),
                    MyWhooshRouteCatalog.compute(rides), routeIds(app), rows,
                    CadenceByGrade.compute(rides, stats, true),
                    HeartRateRecoveryTrend.compute(rides, stats, now, true), races,
                    intervalRides, PowerDecoupling.compute(training, stats, MAX_DECOUPLING_ROWS)));
        });
    }

    /** Imported MyWhoosh routes are named "MyWhoosh – &lt;title&gt;" (MyWhooshRouteStore). */
    private static Map<String, String> routeIds(Application app) {
        Map<String, String> out = new HashMap<>();
        for (RouteCatalogEntry e : new RouteRepository(app).loadCatalog()) {
            if (e.name == null) continue;
            String title = e.name.replaceFirst("(?i)^mywhoosh\\s*[–-]\\s*", "");
            if (title.equals(e.name)) continue;
            out.putIfAbsent(MyWhooshRouteCatalog.key(title), e.routeId);
        }
        return out;
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
