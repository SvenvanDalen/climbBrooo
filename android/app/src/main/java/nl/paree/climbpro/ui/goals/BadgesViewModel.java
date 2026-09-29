package nl.paree.climbpro.ui.goals;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.ClimbMembership;
import nl.paree.climbpro.data.route.RouteCollection;
import nl.paree.climbpro.data.route.RouteCollectionRepository;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.climb.ClimbIdentity;
import nl.paree.climbpro.domain.ride.BadgeCalculator;
import nl.paree.climbpro.domain.ride.BadgeCalculator.Badge;
import nl.paree.climbpro.domain.ride.BadgeCalculator.CollectionClimbs;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Issue #191: badges from the ride archive, climb attempts and collections. Collections are
 * resolved to climb ids here (explicit climb members plus every climb of a member route), so
 * {@link BadgeCalculator} stays pure. Phone-only.
 */
public final class BadgesViewModel extends AndroidViewModel {

    private final RideRepository rideRepo;
    private final ClimbAttemptRepository attemptRepo;
    private final RouteCollectionRepository collectionRepo;
    private final RouteRepository routeRepo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<List<Badge>> badges = new MutableLiveData<>();

    public BadgesViewModel(@NonNull Application app) {
        super(app);
        rideRepo = new RideRepository(app);
        attemptRepo = new ClimbAttemptRepository(app);
        collectionRepo = new RouteCollectionRepository(app);
        routeRepo = new RouteRepository(app);
    }

    public LiveData<List<Badge>> badges() { return badges; }

    public void load() {
        executor.execute(() -> badges.postValue(BadgeCalculator.compute(
                rideRepo.loadAll(), attemptRepo.loadAll(), resolveCollections(),
                ZoneId.systemDefault())));
    }

    private List<CollectionClimbs> resolveCollections() {
        Map<String, StoredRoute> routeCache = new HashMap<>();
        List<CollectionClimbs> out = new ArrayList<>();
        for (RouteCollection c : collectionRepo.loadAll()) {
            Set<String> ids = new HashSet<>();
            if (c.routeIds != null) {
                for (String routeId : c.routeIds) {
                    StoredRoute route = route(routeCache, routeId);
                    if (route == null || route.climbs == null) continue;
                    for (StoredClimb climb : route.climbs) ids.add(ClimbIdentity.of(climb));
                }
            }
            if (c.climbs != null) {
                for (ClimbMembership m : c.climbs) {
                    StoredRoute route = route(routeCache, m.routeId);
                    if (route == null || route.climbs == null
                            || m.climbIndex < 0 || m.climbIndex >= route.climbs.size()) {
                        continue;
                    }
                    ids.add(ClimbIdentity.of(route.climbs.get(m.climbIndex)));
                }
            }
            out.add(new CollectionClimbs(c.id, c.name, ids));
        }
        return out;
    }

    /** Loads a route once per refresh; null when it is gone or fails to load. */
    private StoredRoute route(Map<String, StoredRoute> cache, String routeId) {
        if (routeId == null) return null;
        if (cache.containsKey(routeId)) return cache.get(routeId);
        StoredRoute route = null;
        try {
            route = routeRepo.loadRoute(routeId);
        } catch (Exception ignored) {
            // A deleted or corrupt member route just contributes no climbs.
        }
        cache.put(routeId, route);
        return route;
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
