package nl.paree.climbpro.ui.rides;

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
import nl.paree.climbpro.domain.ride.RideComparison;
import nl.paree.climbpro.domain.ride.RideStreams;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ride comparer (issue #199): fetches both rides' streams from Strava on demand and compares
 * them per kilometre. Ride A is always the older one, so a negative difference means you got
 * faster. Nothing is stored; phone-only.
 */
public final class RideCompareViewModel extends AndroidViewModel {

    private static final String TAG = "RideCompareVM";

    /** Both rides plus their per-kilometre comparison; rows empty when streams are missing. */
    public static final class Result {
        public final StoredRide a;
        public final StoredRide b;
        public final List<RideComparison.Km> rows;

        Result(StoredRide a, StoredRide b, List<RideComparison.Km> rows) {
            this.a = a;
            this.b = b;
            this.rows = rows;
        }
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<Result> result = new MutableLiveData<>();
    private final MutableLiveData<String> error = new MutableLiveData<>();
    private boolean started;

    public RideCompareViewModel(@NonNull Application app) {
        super(app);
    }

    public LiveData<Result> result() { return result; }
    public LiveData<String> error() { return error; }

    /** Loads once per screen; a rotation keeps the result instead of refetching. */
    public void load(long activityIdA, long activityIdB) {
        if (started) return;
        started = true;
        executor.execute(() -> {
            StoredRide a = null;
            StoredRide b = null;
            for (StoredRide r : new RideRepository(getApplication()).loadAll()) {
                if (r.activityId == activityIdA) a = r;
                if (r.activityId == activityIdB) b = r;
            }
            if (a == null || b == null) {
                error.postValue("Rit niet gevonden in het archief");
                return;
            }
            if (b.startEpochSec < a.startEpochSec) {
                StoredRide t = a;
                a = b;
                b = t;
            }
            StravaAuthRepository auth = new StravaAuthRepository(getApplication());
            if (!auth.isAuthorised()) {
                error.postValue("Verbind eerst Strava om ritten te vergelijken");
                return;
            }
            try {
                StravaActivitiesRepository repo = new StravaActivitiesRepository(
                        getApplication(), auth, new RouteRepository(getApplication()),
                        new ClimbAttemptRepository(getApplication()));
                RideStreams sa = repo.fetchRideStreams(a.activityId);
                RideStreams sb = repo.fetchRideStreams(b.activityId);
                result.postValue(new Result(a, b, RideComparison.compare(sa, sb)));
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
