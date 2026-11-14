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
import nl.paree.climbpro.domain.mywhoosh.EfficiencyFactorTrend;
import nl.paree.climbpro.domain.mywhoosh.IndoorOutdoorPowerCurve;
import nl.paree.climbpro.domain.mywhoosh.HeartRateRecoveryTrend;
import nl.paree.climbpro.domain.mywhoosh.IndoorRides;
import nl.paree.climbpro.domain.mywhoosh.IndoorSeasonSummary;
import nl.paree.climbpro.domain.mywhoosh.MyWhooshRouteCatalog;
import nl.paree.climbpro.domain.mywhoosh.PowerDecoupling;
import nl.paree.climbpro.domain.mywhoosh.RaceDetector;
import nl.paree.climbpro.domain.mywhoosh.RideIntensity;
import nl.paree.climbpro.domain.power.RiderProfile;
import nl.paree.climbpro.domain.ride.PowerCurveCalculator;
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
 * #407, #409) and the EF trend and indoor/outdoor power curve (issues #398, #394). Phone-only.
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
        public final EfficiencyFactorTrend.Result efficiency;
        public final IndoorOutdoorPowerCurve.Result indoorOutdoor;

        private State(Builder b) {
            this.myWhooshRides = b.myWhooshRides;
            this.awaitingAnalysis = b.awaitingAnalysis;
            this.ftpWatts = b.ftpWatts;
            this.seasons = b.seasons;
            this.catalog = b.catalog;
            this.routeIds = b.routeIds;
            this.rides = b.rides;
            this.cadence = b.cadence;
            this.recovery = b.recovery;
            this.races = b.races;
            this.intervalRides = b.intervalRides;
            this.decoupling = b.decoupling;
            this.efficiency = b.efficiency;
            this.indoorOutdoor = b.indoorOutdoor;
        }

        /** Builds a {@link State}; every value defaults to Java's default for its type. */
        public static final class Builder {
            private int myWhooshRides;
            private int awaitingAnalysis;
            private int ftpWatts;
            private List<IndoorSeasonSummary.Season> seasons;
            private List<MyWhooshRouteCatalog.Entry> catalog;
            private Map<String, String> routeIds;
            private List<RideRow> rides;
            private CadenceByGrade.Result cadence;
            private HeartRateRecoveryTrend.Result recovery;
            private List<RaceDetector.Race> races;
            private List<IntervalRide> intervalRides;
            private List<PowerDecoupling.Entry> decoupling;
            private EfficiencyFactorTrend.Result efficiency;
            private IndoorOutdoorPowerCurve.Result indoorOutdoor;

            public Builder myWhooshRides(int myWhooshRides) {
                this.myWhooshRides = myWhooshRides;
                return this;
            }

            public Builder awaitingAnalysis(int awaitingAnalysis) {
                this.awaitingAnalysis = awaitingAnalysis;
                return this;
            }

            public Builder ftpWatts(int ftpWatts) {
                this.ftpWatts = ftpWatts;
                return this;
            }

            public Builder seasons(List<IndoorSeasonSummary.Season> seasons) {
                this.seasons = seasons;
                return this;
            }

            public Builder catalog(List<MyWhooshRouteCatalog.Entry> catalog) {
                this.catalog = catalog;
                return this;
            }

            public Builder routeIds(Map<String, String> routeIds) {
                this.routeIds = routeIds;
                return this;
            }

            public Builder rides(List<RideRow> rides) {
                this.rides = rides;
                return this;
            }

            public Builder cadence(CadenceByGrade.Result cadence) {
                this.cadence = cadence;
                return this;
            }

            public Builder recovery(HeartRateRecoveryTrend.Result recovery) {
                this.recovery = recovery;
                return this;
            }

            public Builder races(List<RaceDetector.Race> races) {
                this.races = races;
                return this;
            }

            public Builder intervalRides(List<IntervalRide> intervalRides) {
                this.intervalRides = intervalRides;
                return this;
            }

            public Builder decoupling(List<PowerDecoupling.Entry> decoupling) {
                this.decoupling = decoupling;
                return this;
            }

            public Builder efficiency(EfficiencyFactorTrend.Result efficiency) {
                this.efficiency = efficiency;
                return this;
            }

            public Builder indoorOutdoor(IndoorOutdoorPowerCurve.Result indoorOutdoor) {
                this.indoorOutdoor = indoorOutdoor;
                return this;
            }

            public State build() {
                return new State(this);
            }
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
            for (StoredRide r : rides) {
                if (IndoorRides.isMyWhoosh(r)) myWhoosh.add(r);
            }
            int awaiting = countAwaiting(myWhoosh, stats);
            myWhoosh.sort((a, b) -> Long.compare(b.startEpochSec, a.startEpochSec));
            // #407: races get their own list and stay out of the training rows.
            List<RaceDetector.Race> races = RaceDetector.detect(myWhoosh, stats, ftp, weights);
            List<StoredRide> training = withoutRaces(myWhoosh, races);

            List<RideRow> rows = buildRows(training, stats, ftp);
            List<IntervalRide> intervalRides = buildIntervalRides(training, stats);
            long now = System.currentTimeMillis() / 1000L;
            state.postValue(new State.Builder()
                    .myWhooshRides(myWhoosh.size())
                    .awaitingAnalysis(awaiting)
                    .ftpWatts(ftp)
                    .seasons(IndoorSeasonSummary.compute(rides, stats,
                            new ClimbAttemptRepository(app).loadAll(), ftp, ZoneId.systemDefault()))
                    .catalog(MyWhooshRouteCatalog.compute(rides))
                    .routeIds(routeIds(app))
                    .rides(rows)
                    .cadence(CadenceByGrade.compute(rides, stats, true))
                    .recovery(HeartRateRecoveryTrend.compute(rides, stats, now, true))
                    .races(races)
                    .intervalRides(intervalRides)
                    .decoupling(PowerDecoupling.compute(training, stats, MAX_DECOUPLING_ROWS))
                    .efficiency(EfficiencyFactorTrend.compute(training, stats, now, ZoneId.systemDefault()))
                    .indoorOutdoor(IndoorOutdoorPowerCurve.compute(rides, new ArrayList<>(stats.values()),
                            PowerCurveCalculator.Period.DAYS_90, now))
                    .build());
        });
    }

    private static int countAwaiting(List<StoredRide> myWhoosh,
                                     Map<Long, StoredRideStreamStats> stats) {
        int awaiting = 0;
        for (StoredRide r : myWhoosh) {
            StoredRideStreamStats s = stats.get(r.activityId);
            if (s == null || s.version < RideStreamAnalyzer.VERSION) awaiting++;
        }
        return awaiting;
    }

    private static List<StoredRide> withoutRaces(List<StoredRide> myWhoosh,
                                                 List<RaceDetector.Race> races) {
        Set<Long> raceIds = new HashSet<>();
        for (RaceDetector.Race race : races) raceIds.add(race.ride.activityId);
        List<StoredRide> training = new ArrayList<>();
        for (StoredRide r : myWhoosh) if (!raceIds.contains(r.activityId)) training.add(r);
        return training;
    }

    private static List<RideRow> buildRows(List<StoredRide> training,
                                           Map<Long, StoredRideStreamStats> stats, int ftp) {
        List<RideRow> rows = new ArrayList<>();
        for (StoredRide r : training) {
            if (rows.size() >= MAX_INTENSITY_ROWS) break;
            StoredRideStreamStats s = stats.get(r.activityId);
            rows.add(new RideRow(r, RideIntensity.of(r, s, ftp), s != null ? s.workKj : null));
        }
        return rows;
    }

    private static List<IntervalRide> buildIntervalRides(List<StoredRide> training,
                                                         Map<Long, StoredRideStreamStats> stats) {
        List<IntervalRide> intervalRides = new ArrayList<>();
        for (StoredRide r : training) {
            if (intervalRides.size() >= MAX_INTERVAL_RIDES) break;
            StoredRideStreamStats s = stats.get(r.activityId);
            if (s != null && s.intervals != null && !s.intervals.isEmpty()) {
                intervalRides.add(new IntervalRide(r, s.intervals));
            }
        }
        return intervalRides;
    }

    /** Imported MyWhoosh routes are named "MyWhoosh – &lt;title&gt;" (MyWhooshRouteStore). */
    private static Map<String, String> routeIds(Application app) {
        Map<String, String> out = new HashMap<>();
        for (RouteCatalogEntry e : new RouteRepository(app).loadCatalog()) {
            if (e.name == null) continue;
            String title = e.name.replaceFirst("(?i)^mywhoosh\\s*[–-]\\s*", "");
            if (!title.equals(e.name)) {
                out.putIfAbsent(MyWhooshRouteCatalog.key(title), e.routeId);
            }
        }
        return out;
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
