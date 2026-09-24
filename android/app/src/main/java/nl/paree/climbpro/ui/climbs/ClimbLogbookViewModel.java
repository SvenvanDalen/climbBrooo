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
import nl.paree.climbpro.domain.climb.ClimbStreakCalculator;
import nl.paree.climbpro.domain.climb.ClimbStreakCalculator.Streak;
import nl.paree.climbpro.domain.climb.LogbookCalculator;
import nl.paree.climbpro.domain.climb.LogbookCalculator.Summary;
import nl.paree.climbpro.domain.climb.XpCalculator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ClimbLogbookViewModel extends AndroidViewModel {

    /** One logbook list row: a climb with a PR, resolved to a route+index for navigation. */
    public static final class LogbookRow {
        public final String climbId;
        public final String displayName;
        public final int    prSec;
        public final int    attemptCount;
        public final long   lastDateSec;
        public final String routeId;     // null if no longer resolvable to a route
        public final int    climbIndex;  // -1 if unresolved

        LogbookRow(String climbId, String displayName, int prSec, int attemptCount,
                   long lastDateSec, String routeId, int climbIndex) {
            this.climbId = climbId;
            this.displayName = displayName;
            this.prSec = prSec;
            this.attemptCount = attemptCount;
            this.lastDateSec = lastDateSec;
            this.routeId = routeId;
            this.climbIndex = climbIndex;
        }
    }

    /** Where a climb lives, for navigation + display name. */
    private static final class Location {
        final String routeId;
        final int    index;
        final String displayName;
        final int    gainM;
        final int    lengthM;
        Location(String routeId, int index, String displayName, int gainM, int lengthM) {
            this.routeId = routeId;
            this.index = index;
            this.displayName = displayName;
            this.gainM = gainM;
            this.lengthM = lengthM;
        }
    }

    private final RouteRepository routeRepo;
    private final ClimbAttemptRepository attemptRepo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<List<LogbookRow>> rows = new MutableLiveData<>();
    private final MutableLiveData<Streak> streak = new MutableLiveData<>();
    private final MutableLiveData<XpCalculator.Progress> progress = new MutableLiveData<>();

    public ClimbLogbookViewModel(@NonNull Application app) {
        super(app);
        routeRepo   = new RouteRepository(app);
        attemptRepo = new ClimbAttemptRepository(app);
    }

    public LiveData<List<LogbookRow>> rows() { return rows; }

    /** Current + longest consecutive-day climb streak, see {@link ClimbStreakCalculator}. */
    public LiveData<Streak> streak() { return streak; }

    /** Level + XP derived from all attempts, see {@link XpCalculator}. */
    public LiveData<XpCalculator.Progress> progress() { return progress; }

    public void loadLogbook() {
        executor.execute(() -> {
            List<StoredClimbAttempt> attempts = attemptRepo.loadAll();
            Map<String, Summary> summaries = LogbookCalculator.summaries(attempts);
            Map<String, Location> located = resolveLocations(summaries.keySet());

            List<LogbookRow> out = new ArrayList<>();
            for (Summary s : summaries.values()) {
                Location loc = located.get(s.climbId);
                out.add(new LogbookRow(
                        s.climbId,
                        loc != null ? loc.displayName : "Klim",
                        s.prSec, s.attemptCount, s.lastDateSec,
                        loc != null ? loc.routeId : null,
                        loc != null ? loc.index : -1));
            }
            out.sort(Comparator.comparingLong((LogbookRow r) -> r.lastDateSec).reversed());
            rows.postValue(out);
            streak.postValue(ClimbStreakCalculator.compute(attempts));

            Map<String, XpCalculator.ClimbStats> stats = new HashMap<>();
            for (Map.Entry<String, Location> e : located.entrySet()) {
                stats.put(e.getKey(),
                        new XpCalculator.ClimbStats(e.getValue().gainM, e.getValue().lengthM));
            }
            progress.postValue(XpCalculator.compute(attempts, stats));
        });
    }

    /** Maps each wanted climbId to the first route+index that contains it, with a display name. */
    private Map<String, Location> resolveLocations(Set<String> wanted) {
        Map<String, Location> map = new HashMap<>();
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
                        map.put(id, new Location(entry.routeId, i, name, c.elevationGain, len));
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
