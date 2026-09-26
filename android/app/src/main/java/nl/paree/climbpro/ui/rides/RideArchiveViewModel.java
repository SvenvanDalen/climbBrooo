package nl.paree.climbpro.ui.rides;

import android.app.Application;

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
import nl.paree.climbpro.domain.ride.RideCategory;
import nl.paree.climbpro.domain.ride.RideClassifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ride archive (issue #160): every archived ride with its automatic {@link RideCategory},
 * newest first, optionally filtered to one category. Phone-only.
 */
public final class RideArchiveViewModel extends AndroidViewModel {

    /** One archived ride plus its automatic classification. */
    public static final class Row {
        public final StoredRide ride;
        public final RideCategory category;

        Row(StoredRide ride, RideCategory category) {
            this.ride = ride;
            this.category = category;
        }
    }

    private final RideRepository rideRepo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<List<Row>> rows = new MutableLiveData<>();
    private final MutableLiveData<Map<RideCategory, Integer>> counts = new MutableLiveData<>();
    private final MutableLiveData<String> message = new MutableLiveData<>();

    private List<Row> allRows = Collections.emptyList();
    /** null = all categories. */
    private RideCategory filter;

    public RideArchiveViewModel(@NonNull Application app) {
        super(app);
        rideRepo = new RideRepository(app);
    }

    public LiveData<List<Row>> rows() { return rows; }
    public LiveData<Map<RideCategory, Integer>> counts() { return counts; }
    public LiveData<String> message() { return message; }

    public void load() {
        executor.execute(this::loadNow);
    }

    /** Pulls new ride summaries from Strava (list endpoint only), then reloads. */
    public void refreshFromStrava() {
        executor.execute(() -> {
            StravaAuthRepository auth = new StravaAuthRepository(getApplication());
            if (!auth.isAuthorised()) {
                message.postValue("Verbind eerst Strava om ritten op te halen");
                return;
            }
            try {
                StravaActivitiesRepository repo = new StravaActivitiesRepository(
                        getApplication(), auth, new RouteRepository(getApplication()),
                        new ClimbAttemptRepository(getApplication()));
                int n = repo.syncRideArchive();
                message.postValue(n + " rit(ten) bijgewerkt");
                try {
                    // Fastest 10/40/100 km (issue #225); a capped batch, the rest follows later.
                    repo.analyzeRideStreams();
                } catch (Exception e) {
                    android.util.Log.w("RideArchiveViewModel", "Stream analysis failed", e);
                }
            } catch (Exception e) {
                message.postValue("Ophalen mislukt: "
                        + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            }
            loadNow();
        });
    }

    public void setFilter(RideCategory category) {
        executor.execute(() -> {
            filter = category;
            publish();
        });
    }

    private void loadNow() {
        List<StoredRide> rides = rideRepo.loadAll();
        Map<Long, RideCategory> categories = RideClassifier.classifyAll(rides);
        List<Row> out = new ArrayList<>(rides.size());
        for (StoredRide r : rides) out.add(new Row(r, categories.get(r.activityId)));
        out.sort((a, b) -> Long.compare(b.ride.startEpochSec, a.ride.startEpochSec));
        allRows = out;

        Map<RideCategory, Integer> c = new EnumMap<>(RideCategory.class);
        for (RideCategory cat : RideCategory.values()) c.put(cat, 0);
        for (Row row : out) c.merge(row.category, 1, Integer::sum);
        counts.postValue(c);
        publish();
    }

    private void publish() {
        if (filter == null) {
            rows.postValue(allRows);
            return;
        }
        List<Row> filtered = new ArrayList<>();
        for (Row r : allRows) {
            if (r.category == filter) filtered.add(r);
        }
        rows.postValue(filtered);
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
