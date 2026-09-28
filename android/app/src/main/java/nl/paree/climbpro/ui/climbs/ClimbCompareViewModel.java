package nl.paree.climbpro.ui.climbs;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.data.rider.RiderProfileRepository;
import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.climb.ClimbComparison;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loads both climbs and the pick list for the comparison screen (issue #214). */
public final class ClimbCompareViewModel extends AndroidViewModel {

    private final RouteRepository routeRepo;
    private final ClimbAttemptRepository attemptRepo;
    private final RiderProfileRepository profileRepo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<ClimbComparison.Side> first = new MutableLiveData<>();
    private final MutableLiveData<ClimbComparison.Side> second = new MutableLiveData<>();
    private final MutableLiveData<List<ClimbComparison.Candidate>> candidates =
            new MutableLiveData<>();
    private final MutableLiveData<String> error = new MutableLiveData<>();

    /** Set once the pick list was shown automatically, so rotation doesn't reopen it. */
    private boolean pickerShown;

    public ClimbCompareViewModel(@NonNull Application app) {
        super(app);
        routeRepo = new RouteRepository(app);
        attemptRepo = new ClimbAttemptRepository(app);
        profileRepo = new RiderProfileRepository(app);
    }

    public LiveData<ClimbComparison.Side> first() { return first; }
    public LiveData<ClimbComparison.Side> second() { return second; }
    public LiveData<List<ClimbComparison.Candidate>> candidates() { return candidates; }
    public LiveData<String> error() { return error; }

    /** True the first time only; the screen opens the pick list automatically then. */
    public boolean takeAutoPicker() {
        if (pickerShown || second.getValue() != null) return false;
        pickerShown = true;
        return true;
    }

    /** Loads the first climb and every other climb to choose from. No-op when already loaded. */
    public void load(String routeId, int climbIndex) {
        if (first.getValue() != null) return;
        executor.execute(() -> {
            StoredClimb climb = loadClimb(routeId, climbIndex);
            if (climb == null) {
                error.postValue("Klim niet gevonden");
                return;
            }
            first.postValue(ClimbComparison.side(routeId, climbIndex, climb,
                    attemptRepo.loadAll(), profileRepo.load()));
            List<StoredRoute> routes = new ArrayList<>();
            for (RouteCatalogEntry e : routeRepo.loadCatalog()) {
                try {
                    routes.add(routeRepo.loadRoute(e.routeId));
                } catch (IOException ignored) {
                    // An unreadable route just isn't offered.
                }
            }
            candidates.postValue(ClimbComparison.candidates(routes, climb));
        });
    }

    public void pick(ClimbComparison.Candidate c) {
        executor.execute(() -> {
            StoredClimb climb = loadClimb(c.routeId, c.climbIndex);
            if (climb == null) {
                error.postValue("Klim niet gevonden");
                return;
            }
            second.postValue(ClimbComparison.side(c.routeId, c.climbIndex, climb,
                    attemptRepo.loadAll(), profileRepo.load()));
        });
    }

    private StoredClimb loadClimb(String routeId, int climbIndex) {
        try {
            StoredRoute r = routeRepo.loadRoute(routeId);
            if (r.climbs == null || climbIndex < 0 || climbIndex >= r.climbs.size()) return null;
            return r.climbs.get(climbIndex);
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
