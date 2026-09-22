package nl.paree.climbpro.ui.goals;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.preference.PreferenceManager;

import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.climb.ClimbIdentity;
import nl.paree.climbpro.domain.climb.ElevationGoalCalculator;
import nl.paree.climbpro.domain.climb.ElevationGoalCalculator.Period;
import nl.paree.climbpro.domain.climb.ElevationGoalCalculator.Progress;
import nl.paree.climbpro.ui.settings.SettingsViewModel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Issue #42: elevation-gain goal per week/month, tracked against attempts synced so far.
 * Phone-only, no wire-format impact — see {@link ElevationGoalCalculator}.
 *
 * <p>The weekly goal is the one user-configured setting ({@code SettingsViewModel}'s
 * {@code PREF_ELEVATION_GOAL_WEEKLY_M}); the monthly view derives its goal as 4x the weekly
 * goal (a simple, explainable approximation of ~4.3 weeks/month) rather than adding a second
 * independent setting.
 */
public final class ElevationGoalViewModel extends AndroidViewModel {

    private static final int WEEKS_PER_MONTH_APPROX = 4;

    private final RouteRepository routeRepo;
    private final ClimbAttemptRepository attemptRepo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<Progress> weekProgress = new MutableLiveData<>();
    private final MutableLiveData<Progress> monthProgress = new MutableLiveData<>();

    public ElevationGoalViewModel(@NonNull Application app) {
        super(app);
        routeRepo = new RouteRepository(app);
        attemptRepo = new ClimbAttemptRepository(app);
    }

    public LiveData<Progress> weekProgress()  { return weekProgress; }
    public LiveData<Progress> monthProgress() { return monthProgress; }

    public void load() {
        executor.execute(() -> {
            int weeklyGoalM = PreferenceManager.getDefaultSharedPreferences(getApplication())
                    .getInt(SettingsViewModel.PREF_ELEVATION_GOAL_WEEKLY_M, 0);
            int monthlyGoalM = weeklyGoalM * WEEKS_PER_MONTH_APPROX;

            List<StoredClimbAttempt> attempts = attemptRepo.loadAll();
            Map<String, Integer> elevationByClimbId = resolveElevationGains();

            int weekGained = ElevationGoalCalculator.cumulativeGainM(
                    attempts, elevationByClimbId, Period.WEEK);
            int monthGained = ElevationGoalCalculator.cumulativeGainM(
                    attempts, elevationByClimbId, Period.MONTH);

            weekProgress.postValue(new Progress(weekGained, weeklyGoalM));
            monthProgress.postValue(new Progress(monthGained, monthlyGoalM));
        });
    }

    /** Builds climbId -&gt; elevationGain(m) from the whole route catalog, first match wins. */
    private Map<String, Integer> resolveElevationGains() {
        Map<String, Integer> map = new HashMap<>();
        for (RouteCatalogEntry entry : routeRepo.loadCatalog()) {
            try {
                StoredRoute route = routeRepo.loadRoute(entry.routeId);
                if (route.climbs == null) continue;
                for (StoredClimb c : route.climbs) {
                    int len = c.length > 0 ? c.length : (c.endDistance - c.startDistance);
                    String id = ClimbIdentity.of(c.startLat, c.startLon, len);
                    if (!map.containsKey(id)) map.put(id, c.elevationGain);
                }
            } catch (Exception ignored) {
                // A route that fails to load just won't contribute its climbs' elevation gain.
            }
        }
        return map;
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
