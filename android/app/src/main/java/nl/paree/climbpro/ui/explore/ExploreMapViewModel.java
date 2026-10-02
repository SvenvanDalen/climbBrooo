package nl.paree.climbpro.ui.explore;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.explore.ExploreMapRepository;
import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.strava.StravaActivitiesRepository;
import nl.paree.climbpro.data.strava.StravaAuthRepository;
import nl.paree.climbpro.domain.explore.ExploreGrid;
import nl.paree.climbpro.domain.explore.ExploreMap;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Explore-the-region map (issue #194): the explored cells, their stats and how many archived
 * rides still have to be processed. Phone-only; nothing goes to the watch.
 */
public final class ExploreMapViewModel extends AndroidViewModel {

    /** Snapshot for the screen. */
    public static final class State {
        /** {south, west, north, east} per cell, flattened. */
        public final double[] bounds;
        public final int tileCount;
        public final int rideCount;
        public final double exploredKm;
        /** Outdoor rides not processed yet; -1 when unknown (Strava not connected). */
        public final int pending;

        State(double[] bounds, int tileCount, int rideCount, double exploredKm, int pending) {
            this.bounds = bounds;
            this.tileCount = tileCount;
            this.rideCount = rideCount;
            this.exploredKm = exploredKm;
            this.pending = pending;
        }
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<State> state = new MutableLiveData<>();
    private final MutableLiveData<String> message = new MutableLiveData<>();
    private final MutableLiveData<Boolean> busy = new MutableLiveData<>(false);

    public ExploreMapViewModel(@NonNull Application app) {
        super(app);
    }

    public LiveData<State> state() { return state; }
    public LiveData<String> message() { return message; }
    public LiveData<Boolean> busy() { return busy; }

    public void load() {
        executor.execute(this::loadNow);
    }

    /** Fetches a capped batch of ride tracks from Strava (backfill), then reloads. */
    public void processMore() {
        busy.setValue(true);
        executor.execute(() -> {
            StravaAuthRepository auth = new StravaAuthRepository(getApplication());
            if (!auth.isAuthorised()) {
                message.postValue(getApplication().getString(R.string.explore_map_no_strava));
            } else {
                try {
                    StravaActivitiesRepository repo = strava(auth);
                    repo.syncRideArchive();
                    int n = repo.exploreRideTracks();
                    message.postValue(getApplication().getString(R.string.explore_map_processed, n));
                } catch (Exception e) {
                    message.postValue(getApplication().getString(R.string.explore_map_failed,
                            e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                }
            }
            loadNow();
            busy.postValue(false);
        });
    }

    private StravaActivitiesRepository strava(StravaAuthRepository auth) {
        return new StravaActivitiesRepository(getApplication(), auth,
                new RouteRepository(getApplication()), new ClimbAttemptRepository(getApplication()));
    }

    private void loadNow() {
        ExploreMap map = new ExploreMapRepository(getApplication()).load();
        List<Long> tiles = map.tiles();
        double[] bounds = new double[tiles.size() * 4];
        for (int i = 0; i < tiles.size(); i++) {
            System.arraycopy(ExploreGrid.bounds(tiles.get(i)), 0, bounds, i * 4, 4);
        }
        StravaAuthRepository auth = new StravaAuthRepository(getApplication());
        int pending = auth.isAuthorised() ? strava(auth).pendingExploreRides() : -1;
        state.postValue(new State(bounds, map.tileCount(), map.rideCount(),
                ExploreGrid.exploredKm(map.tileCount()), pending));
    }

    @Override
    protected void onCleared() {
        executor.shutdown();
    }
}
