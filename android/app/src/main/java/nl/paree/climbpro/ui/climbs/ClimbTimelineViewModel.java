package nl.paree.climbpro.ui.climbs;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;
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

        TimelineRow(long dateEpochSec, int elapsedSec, String displayName, int lengthM,
                    double avgGradient, String routeId, int climbIndex, boolean routeDeviation,
                    Double avgTempC) {
            this.dateEpochSec = dateEpochSec;
            this.elapsedSec = elapsedSec;
            this.displayName = displayName;
            this.lengthM = lengthM;
            this.avgGradient = avgGradient;
            this.routeId = routeId;
            this.climbIndex = climbIndex;
            this.routeDeviation = routeDeviation;
            this.avgTempC = avgTempC;
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
            for (StoredClimbAttempt a : ordered) {
                ClimbInfo info = resolved.get(a.climbId);
                out.add(new TimelineRow(
                        a.dateEpochSec,
                        a.elapsedSec,
                        info != null ? info.displayName : "Klim",
                        info != null ? info.lengthM : 0,
                        info != null ? info.avgGradient : 0,
                        info != null ? info.routeId : null,
                        info != null ? info.index : -1,
                        a.routeDeviation,
                        a.avgTempC));
            }
            rows.postValue(out);
        });
    }

    /** Maps each wanted climbId to the first route+index that contains it. */
    private Map<String, ClimbInfo> resolveClimbInfo(Set<String> wanted) {
        Map<String, ClimbInfo> map = new HashMap<>();
        for (RouteCatalogEntry entry : routeRepo.loadCatalog()) {
            try {
                StoredRoute route = routeRepo.loadRoute(entry.routeId);
                if (route.climbs == null) continue;
                for (int i = 0; i < route.climbs.size(); i++) {
                    StoredClimb c = route.climbs.get(i);
                    int len = c.length > 0 ? c.length : (c.endDistance - c.startDistance);
                    String id = ClimbIdentity.of(c.startLat, c.startLon, len);
                    if (wanted.contains(id) && !map.containsKey(id)) {
                        String name = c.userDisplayName != null ? c.userDisplayName
                                : (c.name != null ? c.name : "Klim");
                        map.put(id, new ClimbInfo(entry.routeId, i, name, len, c.avgGradient));
                    }
                }
            } catch (Exception ignored) {
                // A route that fails to load just won't resolve its climbs' names/links.
            }
        }
        return map;
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
