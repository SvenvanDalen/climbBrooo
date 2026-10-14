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
import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.domain.mywhoosh.CadenceByGrade;
import nl.paree.climbpro.domain.mywhoosh.HeartRateRecoveryTrend;
import nl.paree.climbpro.domain.mywhoosh.IndoorRides;
import nl.paree.climbpro.domain.mywhoosh.IndoorSeasonSummary;
import nl.paree.climbpro.domain.mywhoosh.MyWhooshRouteCatalog;
import nl.paree.climbpro.domain.mywhoosh.RideIntensity;
import nl.paree.climbpro.domain.ride.RideStreamAnalyzer;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Everything on the MyWhoosh screen, computed off the main thread from the ride archive, the
 * stream analysis and the climb logbook (issues #391, #402, #403, #406, #409). Phone-only.
 */
public final class MyWhooshViewModel extends AndroidViewModel {

    /** Rides with NP/IF/TSS shown. */
    static final int MAX_INTENSITY_ROWS = 20;

    public static final class RideRow {
        public final StoredRide ride;
        /** Null without power or FTP. */
        public final RideIntensity intensity;

        RideRow(StoredRide ride, RideIntensity intensity) {
            this.ride = ride;
            this.intensity = intensity;
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
            int ftp = new RiderProfileRepository(app).load().ftpWatts;

            List<StoredRide> myWhoosh = new ArrayList<>();
            int awaiting = 0;
            for (StoredRide r : rides) {
                if (!IndoorRides.isMyWhoosh(r)) continue;
                myWhoosh.add(r);
                StoredRideStreamStats s = stats.get(r.activityId);
                if (s == null || s.version < RideStreamAnalyzer.VERSION) awaiting++;
            }
            myWhoosh.sort((a, b) -> Long.compare(b.startEpochSec, a.startEpochSec));
            List<RideRow> rows = new ArrayList<>();
            for (int i = 0; i < myWhoosh.size() && i < MAX_INTENSITY_ROWS; i++) {
                StoredRide r = myWhoosh.get(i);
                rows.add(new RideRow(r, RideIntensity.of(r, stats.get(r.activityId), ftp)));
            }
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
                    .build());
        });
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
