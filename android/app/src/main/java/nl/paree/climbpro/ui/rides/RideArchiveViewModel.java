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
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.strava.StravaActivitiesRepository;
import nl.paree.climbpro.data.strava.StravaAuthRepository;
import nl.paree.climbpro.domain.climb.ClimbIdentity;
import nl.paree.climbpro.domain.ride.RideCategory;
import nl.paree.climbpro.domain.ride.RideClassifier;
import nl.paree.climbpro.domain.ride.SummitGroupPhotos;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ride archive (issue #160): every archived ride with its automatic {@link RideCategory},
 * newest first, optionally filtered to one category. Phone-only.
 */
public final class RideArchiveViewModel extends AndroidViewModel {

    private static final String TAG = "RideArchiveVM";

    /** One archived ride plus its automatic classification and group summit photos. */
    public static final class Row {
        public final StoredRide ride;
        public final RideCategory category;
        /** Group summit photos of this ride (issue #243), in ride order; never null. */
        public final List<SummitGroupPhotos.Moment> groupPhotos;

        Row(StoredRide ride, RideCategory category, List<SummitGroupPhotos.Moment> groupPhotos) {
            this.ride = ride;
            this.category = category;
            this.groupPhotos = groupPhotos != null
                    ? groupPhotos : Collections.<SummitGroupPhotos.Moment>emptyList();
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
                int n = new StravaActivitiesRepository(getApplication(), auth,
                        new RouteRepository(getApplication()),
                        new ClimbAttemptRepository(getApplication())).syncRideArchive();
                message.postValue(n + " rit(ten) bijgewerkt");
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
        Map<Long, List<SummitGroupPhotos.Moment>> groupPhotos = loadGroupPhotos();
        for (StoredRide r : rides) {
            out.add(new Row(r, categories.get(r.activityId), groupPhotos.get(r.activityId)));
        }
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

    /**
     * Group summit photos per activity id (issue #243). Route files are only read for climb
     * names when at least one group photo exists, so riders who never use the feature don't
     * pay for loading every route when they open the archive.
     */
    private Map<Long, List<SummitGroupPhotos.Moment>> loadGroupPhotos() {
        List<StoredClimbAttempt> attempts =
                new ClimbAttemptRepository(getApplication()).loadAll();
        boolean any = false;
        for (StoredClimbAttempt a : attempts) {
            if (SummitGroupPhotos.isGroupPhoto(a)) { any = true; break; }
        }
        if (!any) return Collections.emptyMap();
        return SummitGroupPhotos.byActivity(attempts, climbNames());
    }

    /** Display name per ClimbIdentity key, same resolution as the photo quiz (issue #252). */
    private Map<String, String> climbNames() {
        RouteRepository repo = new RouteRepository(getApplication());
        Map<String, String> names = new HashMap<>();
        for (RouteCatalogEntry e : repo.loadCatalog()) {
            try {
                StoredRoute r = repo.loadRoute(e.routeId);
                if (r.climbs == null) continue;
                for (StoredClimb c : r.climbs) {
                    String n = c.userDisplayName != null && !c.userDisplayName.isEmpty()
                            ? c.userDisplayName : c.name;
                    if (n != null && !n.isEmpty()) names.putIfAbsent(ClimbIdentity.of(c), n);
                }
            } catch (IOException ex) {
                Log.w(TAG, "Skipping route " + e.routeId, ex);
            }
        }
        return names;
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
