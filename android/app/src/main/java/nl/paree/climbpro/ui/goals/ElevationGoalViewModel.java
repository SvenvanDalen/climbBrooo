package nl.paree.climbpro.ui.goals;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.preference.PreferenceManager;

import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.ride.StoredRide;
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
import nl.paree.climbpro.domain.mywhoosh.IndoorRides;
import nl.paree.climbpro.domain.mywhoosh.VirtualElevation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Issue #42: elevation-gain goal per week/month, tracked against attempts synced so far.
 * Phone-only, no wire-format impact — see {@link ElevationGoalCalculator}.
 *
 * <p>The weekly goal is the one user-configured setting ({@link #PREF_ELEVATION_GOAL_WEEKLY_M});
 * the monthly view derives its goal from it, scaled to the current month's length (see
 * {@link ElevationGoalCalculator#monthlyGoalFromWeekly}), rather than adding a second
 * independent setting.
 */
public final class ElevationGoalViewModel extends AndroidViewModel {

    /** Issue #42: weekly elevation-gain training goal, in metres. 0 means "not set". */
    public static final String PREF_ELEVATION_GOAL_WEEKLY_M = "elevation_goal_weekly_m";

    private final RouteRepository routeRepo;
    private final ClimbAttemptRepository attemptRepo;
    private final RideRepository rideRepo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<Progress> weekProgress = new MutableLiveData<>();
    private final MutableLiveData<Progress> monthProgress = new MutableLiveData<>();
    /** Virtual hm this month and whether they count (issue #393); null without indoor hm. */
    private final MutableLiveData<String> virtualNote = new MutableLiveData<>();

    public ElevationGoalViewModel(@NonNull Application app) {
        super(app);
        routeRepo = new RouteRepository(app);
        attemptRepo = new ClimbAttemptRepository(app);
        rideRepo = new RideRepository(app);
    }

    public LiveData<Progress> weekProgress()  { return weekProgress; }
    public LiveData<Progress> monthProgress() { return monthProgress; }
    public LiveData<String> virtualNote() { return virtualNote; }

    /** @param metres 0 clears the goal ("not set"). Reloads progress afterwards. */
    public void setWeeklyGoalM(int metres) {
        PreferenceManager.getDefaultSharedPreferences(getApplication())
                .edit().putInt(PREF_ELEVATION_GOAL_WEEKLY_M, Math.max(0, metres)).apply();
        load();
    }

    public void load() {
        executor.execute(() -> {
            int weeklyGoalM = PreferenceManager.getDefaultSharedPreferences(getApplication())
                    .getInt(PREF_ELEVATION_GOAL_WEEKLY_M, 0);
            int monthlyGoalM = ElevationGoalCalculator.monthlyGoalFromWeekly(
                    weeklyGoalM, java.time.LocalDate.now());

            int weekGained;
            int monthGained;
            List<StoredRide> allRides = rideRepo.loadAll();
            // Issue #393: MyWhoosh / indoor hm count unless the rider switched them off.
            boolean countVirtual = PreferenceManager.getDefaultSharedPreferences(getApplication())
                    .getBoolean(VirtualElevation.PREF_COUNT_VIRTUAL, true);
            List<StoredRide> rides = VirtualElevation.countedRides(allRides, countVirtual);
            java.time.ZoneId zone = java.time.ZoneId.systemDefault();
            java.time.LocalDate today = java.time.LocalDate.now(zone);
            int virtualMonth = ElevationGoalCalculator.cumulativeRideGainM(
                    VirtualElevation.virtualRides(allRides), Period.MONTH, zone, today);
            virtualNote.postValue(virtualMonth <= 0 ? null : countVirtual
                    ? "Waarvan " + virtualMonth + " m virtueel (MyWhoosh/indoor) deze maand"
                    : virtualMonth + " m virtueel (MyWhoosh/indoor) niet meegeteld deze maand");
            if (!allRides.isEmpty()) {
                // The ride archive (#160) has each ride's total hm, not just its climbs.
                weekGained = ElevationGoalCalculator.cumulativeRideGainM(
                        rides, Period.WEEK, zone, today);
                monthGained = ElevationGoalCalculator.cumulativeRideGainM(
                        rides, Period.MONTH, zone, today);
            } else {
                // No archive yet (no Strava link, or only file imports): climb hm only.
                List<StoredClimbAttempt> attempts = VirtualElevation.countedAttempts(
                        attemptRepo.loadAll(), IndoorRides.indoorIds(allRides), countVirtual);
                Map<String, Integer> elevationByClimbId = resolveElevationGains();
                weekGained = ElevationGoalCalculator.cumulativeGainM(
                        attempts, elevationByClimbId, Period.WEEK);
                monthGained = ElevationGoalCalculator.cumulativeGainM(
                        attempts, elevationByClimbId, Period.MONTH);
            }

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
