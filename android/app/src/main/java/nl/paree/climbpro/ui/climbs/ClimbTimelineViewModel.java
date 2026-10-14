package nl.paree.climbpro.ui.climbs;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.domain.climb.ClimbCatalogIndex;
import nl.paree.climbpro.domain.climb.ClimbIdentity;
import nl.paree.climbpro.domain.climb.LogbookCalculator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Chronological (most-recent-first) view over every climb attempt, independent of
 * route grouping — "what did I climb this month" rather than the per-route/per-climb
 * logbook in {@link ClimbLogbookViewModel}. Pure ordering lives in
 * {@link LogbookCalculator#timeline}; this class only resolves each attempt's
 * climbId to a display name/length/gradient/route link for rendering.
 */
public final class ClimbTimelineViewModel extends AndroidViewModel {

    /** One row in the chronological timeline: a single attempt, resolved for display. */
    public static final class TimelineRow {
        public final long   dateEpochSec;
        public final long   activityId;      // Strava activity id; groups rows from the same ride
        public final int    elapsedSec;
        public final String displayName;
        public final int    lengthM;         // 0 if unresolved
        public final double avgGradient;      // 0 if unresolved
        public final String routeId;          // null if no longer resolvable to a route
        public final int    climbIndex;       // -1 if unresolved
        /**
         * True when this attempt's GPS track diverged from the climb's known geometry
         * and was excluded from PR calculations — see
         * {@link nl.paree.climbpro.domain.matching.ClimbRouteDeviationDetector} (issue #77).
         * Still shown here, just marked, since this is the plain chronological view.
         */
        public final boolean routeDeviation;
        /** Average device temperature over the pass (°C), null when unknown (issue #80). */
        public final Double avgTempC;

        private TimelineRow(Builder b) {
            this.dateEpochSec = b.dateEpochSec;
            this.activityId = b.activityId;
            this.elapsedSec = b.elapsedSec;
            this.displayName = b.displayName;
            this.lengthM = b.lengthM;
            this.avgGradient = b.avgGradient;
            this.routeId = b.routeId;
            this.climbIndex = b.climbIndex;
            this.routeDeviation = b.routeDeviation;
            this.avgTempC = b.avgTempC;
        }

        /** Builds a {@link TimelineRow}; every value defaults to Java's default for its type. */
        public static final class Builder {
            private long dateEpochSec;
            private long activityId;
            private int elapsedSec;
            private String displayName;
            private int lengthM;
            private double avgGradient;
            private String routeId;
            private int climbIndex;
            private boolean routeDeviation;
            private Double avgTempC;

            public Builder dateEpochSec(long dateEpochSec) {
                this.dateEpochSec = dateEpochSec;
                return this;
            }

            public Builder activityId(long activityId) {
                this.activityId = activityId;
                return this;
            }

            public Builder elapsedSec(int elapsedSec) {
                this.elapsedSec = elapsedSec;
                return this;
            }

            public Builder displayName(String displayName) {
                this.displayName = displayName;
                return this;
            }

            public Builder lengthM(int lengthM) {
                this.lengthM = lengthM;
                return this;
            }

            public Builder avgGradient(double avgGradient) {
                this.avgGradient = avgGradient;
                return this;
            }

            public Builder routeId(String routeId) {
                this.routeId = routeId;
                return this;
            }

            public Builder climbIndex(int climbIndex) {
                this.climbIndex = climbIndex;
                return this;
            }

            public Builder routeDeviation(boolean routeDeviation) {
                this.routeDeviation = routeDeviation;
                return this;
            }

            public Builder avgTempC(Double avgTempC) {
                this.avgTempC = avgTempC;
                return this;
            }

            public TimelineRow build() {
                return new TimelineRow(this);
            }
        }
    }

    /** Where a climb lives, plus the display fields sourced from the route. */
    private static final class ClimbInfo {
        final String routeId;
        final int    index;
        final String displayName;
        final int    lengthM;
        final double avgGradient;

        ClimbInfo(String routeId, int index, String displayName, int lengthM, double avgGradient) {
            this.routeId = routeId;
            this.index = index;
            this.displayName = displayName;
            this.lengthM = lengthM;
            this.avgGradient = avgGradient;
        }
    }

    private final RouteRepository routeRepo;
    private final ClimbAttemptRepository attemptRepo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<List<TimelineRow>> rows = new MutableLiveData<>();

    public ClimbTimelineViewModel(@NonNull Application app) {
        super(app);
        routeRepo   = new RouteRepository(app);
        attemptRepo = new ClimbAttemptRepository(app);
    }

    public LiveData<List<TimelineRow>> rows() { return rows; }

    public void loadTimeline() {
        executor.execute(() -> {
            List<StoredClimbAttempt> ordered = LogbookCalculator.timeline(attemptRepo.loadAll());

            Set<String> wanted = new java.util.HashSet<>();
            for (StoredClimbAttempt a : ordered) wanted.add(a.climbId);
            Map<String, ClimbInfo> resolved = resolveClimbInfo(wanted);

            List<TimelineRow> out = new ArrayList<>(ordered.size());
            for (StoredClimbAttempt a : ordered) out.add(timelineRow(a, resolved.get(a.climbId)));
            rows.postValue(out);
        });
    }

    /** A timeline row; a climb no longer in any route shows as "Klim" without a link. */
    private static TimelineRow timelineRow(StoredClimbAttempt a, ClimbInfo info) {
        return new TimelineRow.Builder()
                .dateEpochSec(a.dateEpochSec)
                .activityId(a.activityId)
                .elapsedSec(a.elapsedSec)
                .displayName(info != null ? info.displayName : "Klim")
                .lengthM(info != null ? info.lengthM : 0)
                .avgGradient(info != null ? info.avgGradient : 0)
                .routeId(info != null ? info.routeId : null)
                .climbIndex(info != null ? info.index : -1)
                .routeDeviation(a.routeDeviation)
                .avgTempC(a.avgTempC)
                .build();
    }

    /** Maps each wanted climbId to the first route+index that contains it. */
    private Map<String, ClimbInfo> resolveClimbInfo(Set<String> wanted) {
        Map<String, ClimbInfo> map = new HashMap<>();
        for (Map.Entry<String, ClimbCatalogIndex.Entry> e
                : ClimbCatalogIndex.resolve(routeRepo, wanted).entrySet()) {
            ClimbCatalogIndex.Entry found = e.getValue();
            StoredClimb c = found.climb;
            map.put(e.getKey(), new ClimbInfo(found.routeId, found.index, climbName(c),
                    ClimbIdentity.effectiveLength(c), c.avgGradient));
        }
        return map;
    }

    static String climbName(StoredClimb c) {
        if (c.userDisplayName != null) return c.userDisplayName;
        return c.name != null ? c.name : "Klim";
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
