package nl.paree.climbpro.ui.mywhoosh;

import android.app.Application;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.strava.StravaActivitiesRepository;
import nl.paree.climbpro.data.strava.StravaAuthRepository;
import nl.paree.climbpro.domain.mywhoosh.RideProfile;

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

        Result(StoredRide ride, RideProfile profile) {
            this.ride = ride;
            this.profile = profile;
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
        executor.execute(() -> {
            StoredRide ride = null;
            for (StoredRide r : new RideRepository(getApplication()).loadAll()) {
                if (r.activityId == activityId) ride = r;
            }
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
                result.postValue(new Result(ride, profile));
            } catch (Exception e) {
                Log.w(TAG, "Stream fetch failed", e);
                error.postValue("Streams ophalen mislukt: "
                        + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            }
        });
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
