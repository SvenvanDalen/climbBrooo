package nl.paree.climbpro.ui.collections;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.data.route.ClimbMembership;
import nl.paree.climbpro.data.route.RouteCollection;
import nl.paree.climbpro.data.route.RouteCollectionRepository;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CollectionDetailViewModel extends AndroidViewModel {

    private final RouteCollectionRepository collectionRepo;
    private final RouteRepository           routeRepo;
    private final ExecutorService           executor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<RouteCollection>      collection = new MutableLiveData<>();
    private final MutableLiveData<List<CollectionMember>> members  = new MutableLiveData<>();
    private final MutableLiveData<String>               error      = new MutableLiveData<>();

    public CollectionDetailViewModel(@NonNull Application app) {
        super(app);
        collectionRepo = new RouteCollectionRepository(app);
        routeRepo      = new RouteRepository(app);
    }

    public LiveData<RouteCollection>        collection() { return collection; }
    public LiveData<List<CollectionMember>> members()    { return members; }
    public LiveData<String>                 error()      { return error; }

    public void load(String collectionId) {
        executor.execute(() -> {
            RouteCollection c = collectionRepo.get(collectionId);
            collection.postValue(c);
            members.postValue(resolveMembers(c));
        });
    }

    public void rename(String collectionId, String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            error.postValue("Naam mag niet leeg zijn");
            return;
        }
        executor.execute(() -> {
            collectionRepo.rename(collectionId, trimmed);
            load(collectionId);
        });
    }

    public void delete(String collectionId) {
        executor.execute(() -> collectionRepo.delete(collectionId));
    }

    public void removeRoute(String collectionId, String routeId) {
        executor.execute(() -> {
            collectionRepo.removeRoute(collectionId, routeId);
            load(collectionId);
        });
    }

    public void removeClimb(String collectionId, String routeId, int climbIndex) {
        executor.execute(() -> {
            collectionRepo.removeClimb(collectionId, routeId, climbIndex);
            load(collectionId);
        });
    }

    /** Resolves route/climb ids to display names, tolerating members whose route was deleted. */
    private List<CollectionMember> resolveMembers(RouteCollection c) {
        List<CollectionMember> result = new ArrayList<>();
        if (c == null) return result;

        Map<String, StoredRoute> cache = new HashMap<>();

        if (c.routeIds != null) {
            for (String routeId : c.routeIds) {
                StoredRoute r = loadCached(cache, routeId);
                String label = r != null
                        ? (r.userDisplayName != null ? r.userDisplayName : r.name)
                        : "(verwijderde route)";
                result.add(new CollectionMember(routeId, -1, label != null ? label : routeId));
            }
        }
        if (c.climbs != null) {
            for (ClimbMembership m : c.climbs) {
                StoredRoute r = loadCached(cache, m.routeId);
                String label = "(verwijderde klim)";
                if (r != null && r.climbs != null
                        && m.climbIndex >= 0 && m.climbIndex < r.climbs.size()) {
                    StoredClimb climb = r.climbs.get(m.climbIndex);
                    String climbName = climb.userDisplayName != null ? climb.userDisplayName : climb.name;
                    String routeName = r.userDisplayName != null ? r.userDisplayName : r.name;
                    label = (climbName != null ? climbName : "Klim " + (m.climbIndex + 1))
                            + " — " + (routeName != null ? routeName : m.routeId);
                }
                result.add(new CollectionMember(m.routeId, m.climbIndex, label));
            }
        }
        return result;
    }

    private StoredRoute loadCached(Map<String, StoredRoute> cache, String routeId) {
        if (cache.containsKey(routeId)) return cache.get(routeId);
        StoredRoute r;
        try {
            r = routeRepo.loadRoute(routeId);
        } catch (Exception e) {
            r = null;
        }
        cache.put(routeId, r);
        return r;
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
