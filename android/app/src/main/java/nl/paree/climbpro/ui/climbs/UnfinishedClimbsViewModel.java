package nl.paree.climbpro.ui.climbs;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.IncompleteClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.domain.climb.ClimbCatalogIndex;
import nl.paree.climbpro.domain.climb.ClimbIdentity;
import nl.paree.climbpro.domain.climb.UnfinishedClimbsCalculator;
import nl.paree.climbpro.domain.climb.UnfinishedClimbsCalculator.UnfinishedClimb;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Never completed" climbs overview (issue #37): climbs the rider has started (entered the
 * start gate, per {@link nl.paree.climbpro.domain.matching.ClimbEntryOnlyDetector}) but never
 * finished — and that have no successful {@link nl.paree.climbpro.data.route.StoredClimbAttempt}
 * on record, even if that success happened before or after the incomplete pass. Phone-only,
 * no wire-format impact. Pure roll-up lives in {@link UnfinishedClimbsCalculator}; this class
 * only resolves each climbId to a display name/route link for rendering.
 */
public final class UnfinishedClimbsViewModel extends AndroidViewModel {

    /** One row: an unfinished climb, resolved for display. */
    public static final class Row {
        public final String displayName;
        public final long   lastAttemptDateSec;
        public final int    bestDistanceCoveredM;
        public final int    lengthM;   // 0 if unresolved
        public final int    attemptCount;
        public final String routeId;   // null if no longer resolvable to a route
        public final int    climbIndex; // -1 if unresolved

        Row(String displayName, long lastAttemptDateSec, int bestDistanceCoveredM, int lengthM,
            int attemptCount, String routeId, int climbIndex) {
            this.displayName = displayName;
            this.lastAttemptDateSec = lastAttemptDateSec;
            this.bestDistanceCoveredM = bestDistanceCoveredM;
            this.lengthM = lengthM;
            this.attemptCount = attemptCount;
            this.routeId = routeId;
            this.climbIndex = climbIndex;
        }
    }

    private static final class ClimbInfo {
        final String routeId;
        final int    index;
        final String displayName;
        final int    lengthM;

        ClimbInfo(String routeId, int index, String displayName, int lengthM) {
            this.routeId = routeId;
            this.index = index;
            this.displayName = displayName;
            this.lengthM = lengthM;
        }
    }

    private final RouteRepository routeRepo;
    private final ClimbAttemptRepository attemptRepo;
    private final IncompleteClimbAttemptRepository incompleteRepo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<List<Row>> rows = new MutableLiveData<>();

    public UnfinishedClimbsViewModel(@NonNull Application app) {
        super(app);
        routeRepo      = new RouteRepository(app);
        attemptRepo    = new ClimbAttemptRepository(app);
        incompleteRepo = new IncompleteClimbAttemptRepository(app);
    }

    public LiveData<List<Row>> rows() { return rows; }

    public void loadUnfinished() {
        executor.execute(() -> {
            List<UnfinishedClimb> unfinished = UnfinishedClimbsCalculator.unfinished(
                    incompleteRepo.loadAll(), attemptRepo.loadAll());

            Set<String> wanted = new java.util.HashSet<>();
            for (UnfinishedClimb u : unfinished) wanted.add(u.climbId);
            Map<String, ClimbInfo> resolved = resolveClimbInfo(wanted);

            List<Row> out = new ArrayList<>(unfinished.size());
            for (UnfinishedClimb u : unfinished) out.add(row(u, resolved.get(u.climbId)));
            rows.postValue(out);
        });
    }

    /** A row; a climb no longer in any route shows as "Klim" without a link. */
    private static Row row(UnfinishedClimb u, ClimbInfo info) {
        return new Row(
                info != null ? info.displayName : "Klim",
                u.lastAttemptDateSec,
                u.bestDistanceCoveredM,
                info != null ? info.lengthM : 0,
                u.attemptCount,
                info != null ? info.routeId : null,
                info != null ? info.index : -1);
    }

    /** Maps each wanted climbId to the first route+index that contains it. */
    private Map<String, ClimbInfo> resolveClimbInfo(Set<String> wanted) {
        Map<String, ClimbInfo> map = new HashMap<>();
        for (Map.Entry<String, ClimbCatalogIndex.Entry> e
                : ClimbCatalogIndex.resolve(routeRepo, wanted).entrySet()) {
            ClimbCatalogIndex.Entry found = e.getValue();
            StoredClimb c = found.climb;
            map.put(e.getKey(), new ClimbInfo(found.routeId, found.index,
                    ClimbTimelineViewModel.climbName(c), ClimbIdentity.effectiveLength(c)));
        }
        return map;
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
