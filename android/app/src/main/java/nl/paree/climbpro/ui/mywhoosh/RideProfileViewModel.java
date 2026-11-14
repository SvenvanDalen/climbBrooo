package nl.paree.climbpro.ui.mywhoosh;

import android.app.Application;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.data.rider.RiderProfileRepository;
import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.climb.ClimbIdentity;
import nl.paree.climbpro.domain.power.IntervalBlock;
import nl.paree.climbpro.domain.power.WorkoutCompliance;
import nl.paree.climbpro.data.strava.StravaActivitiesRepository;
import nl.paree.climbpro.data.strava.StravaAuthRepository;
import nl.paree.climbpro.domain.mywhoosh.RideProfile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fetches one ride's streams from Strava on demand and bins them into a {@link RideProfile}
 * (issue #390). Nothing is stored; phone-only.
 */
public final class RideProfileViewModel extends AndroidViewModel {

    private static final String TAG = "RideProfileVM";

    public static final class Result {
        public final StoredRide ride;
        public final RideProfile profile;
        /** Planned-vs-ridden interval blocks of this ride (issue #401); empty without a plan. */
        public final List<WorkoutCompliance.Step> steps;

        Result(StoredRide ride, RideProfile profile, List<WorkoutCompliance.Step> steps) {
            this.ride = ride;
            this.profile = profile;
            this.steps = steps;
        }
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<Result> result = new MutableLiveData<>();
    private final MutableLiveData<String> error = new MutableLiveData<>();
    private boolean started;

    public RideProfileViewModel(@NonNull Application app) {
        super(app);
    }

    public LiveData<Result> result() { return result; }
    public LiveData<String> error() { return error; }

    /** Loads once per screen; a rotation keeps the result instead of refetching. */
    public void load(long activityId) {
        if (started) return;
        started = true;
        executor.execute(() -> loadRide(activityId));
    }

    private void loadRide(long activityId) {
        StoredRide ride = findRide(activityId);
        if (ride == null) {
            error.postValue("Rit niet gevonden in het archief");
            return;
        }
        StravaAuthRepository auth = new StravaAuthRepository(getApplication());
        if (!auth.isAuthorised()) {
            error.postValue("Verbind eerst Strava om het ritprofiel op te halen");
            return;
        }
        try {
            StravaActivitiesRepository repo = new StravaActivitiesRepository(
                    getApplication(), auth, new RouteRepository(getApplication()),
                    new ClimbAttemptRepository(getApplication()));
            RideProfile profile = RideProfile.of(repo.fetchRideStreams(activityId));
            if (profile == null) {
                error.postValue("Strava heeft geen bruikbare streams voor deze rit");
                return;
            }
            result.postValue(new Result(ride, profile, complianceSteps(activityId)));
        } catch (Exception e) {
            Log.w(TAG, "Stream fetch failed", e);
            error.postValue("Streams ophalen mislukt: "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    private StoredRide findRide(long activityId) {
        StoredRide ride = null;
        for (StoredRide r : new RideRepository(getApplication()).loadAll()) {
            if (r.activityId == activityId) ride = r;
        }
        return ride;
    }

    /**
     * The ride's matched climb attempts whose climb carries an interval block, in ride order,
     * compared against the rider's FTP. Empty when there is no FTP or no block (issue #401).
     */
    private List<WorkoutCompliance.Step> complianceSteps(long activityId) {
        try {
            int ftp = new RiderProfileRepository(getApplication()).load().ftpWatts;
            if (ftp <= 0) return new ArrayList<>();
            List<StoredClimbAttempt> attempts = attemptsForRide(activityId);
            if (attempts.isEmpty()) return new ArrayList<>();
            attempts.sort((x, y) -> Integer.compare(x.startOffsetSec, y.startOffsetSec));
            return WorkoutCompliance.compare(plannedSteps(attempts, intervalClimbsById()), ftp);
        } catch (Exception e) {
            Log.w(TAG, "Compliance failed", e);
            return new ArrayList<>();
        }
    }

    private List<StoredClimbAttempt> attemptsForRide(long activityId) {
        List<StoredClimbAttempt> attempts = new ArrayList<>();
        for (StoredClimbAttempt a : new ClimbAttemptRepository(getApplication()).loadAll()) {
            if (a.activityId == activityId) attempts.add(a);
        }
        return attempts;
    }

    private Map<String, StoredClimb> intervalClimbsById() throws IOException {
        Map<String, StoredClimb> byId = new HashMap<>();
        RouteRepository routes = new RouteRepository(getApplication());
        for (RouteCatalogEntry entry : routes.loadCatalog()) {
            StoredRoute route = routes.loadRoute(entry.routeId);
            if (route == null || route.climbs == null) continue;
            for (StoredClimb c : route.climbs) {
                if (c != null && c.intervalBlock != null) byId.put(ClimbIdentity.of(c), c);
            }
        }
        return byId;
    }

    private static List<WorkoutCompliance.Planned> plannedSteps(
            List<StoredClimbAttempt> attempts, Map<String, StoredClimb> byId) {
        List<WorkoutCompliance.Planned> planned = new ArrayList<>();
        for (StoredClimbAttempt a : attempts) {
            StoredClimb c = byId.get(a.climbId);
            if (c == null) continue;
            String name = c.userDisplayName != null && !c.userDisplayName.isEmpty()
                    ? c.userDisplayName : c.name;
            planned.add(new WorkoutCompliance.Planned(name != null ? name : "Klim",
                    IntervalBlock.fromStored(c.intervalBlock), a.avgWatts, a.elapsedSec));
        }
        return planned;
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
