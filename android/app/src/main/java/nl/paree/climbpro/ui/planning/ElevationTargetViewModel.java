package nl.paree.climbpro.ui.planning;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.location.Location;
import android.location.LocationManager;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.data.planning.PlannedClimb;
import nl.paree.climbpro.data.planning.PlannedClimbRepository;
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.climb.ClimbIdentity;
import nl.paree.climbpro.domain.climb.KnownClimb;
import nl.paree.climbpro.domain.climb.KnownClimbs;
import nl.paree.climbpro.domain.planning.ElevationTargetPlanner;
import nl.paree.climbpro.service.PlannedClimbWorkScheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Backs {@link ElevationTargetActivity} (issue #68 — route-suggestie op gewenst aantal
 * hoogtemeters). All IO (catalog/route loading, location lookup, plan persistence) runs on a
 * single background executor; the actual selection/ordering is the pure
 * {@link ElevationTargetPlanner}.
 */
public final class ElevationTargetViewModel extends AndroidViewModel {

    /** Where the suggested day ride starts; the radius is measured from here. */
    public static final class StartPoint {
        public final double lat;
        public final double lon;
        public final String label;
        public StartPoint(double lat, double lon, String label) {
            this.lat = lat;
            this.lon = lon;
            this.label = label;
        }
    }

    /** A stored route offered as a fallback start point (its first track point). */
    public static final class RouteStart {
        public final String label;
        public final double lat;
        public final double lon;
        RouteStart(String label, double lat, double lon) {
            this.label = label;
            this.lat = lat;
            this.lon = lon;
        }
    }

    public interface RouteStartsCallback { void onRouteStarts(List<RouteStart> starts); }

    private final RouteRepository routeRepo;
    private final PlannedClimbRepository planRepo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<StartPoint> start = new MutableLiveData<>();
    private final MutableLiveData<ElevationTargetPlanner.Suggestion> suggestion = new MutableLiveData<>();
    private final MutableLiveData<String> message = new MutableLiveData<>();
    private final MutableLiveData<Boolean> busy = new MutableLiveData<>(false);

    public ElevationTargetViewModel(@NonNull Application app) {
        super(app);
        routeRepo = new RouteRepository(app);
        planRepo = new PlannedClimbRepository(app);
    }

    public LiveData<StartPoint> start() { return start; }
    public LiveData<ElevationTargetPlanner.Suggestion> suggestion() { return suggestion; }
    public LiveData<String> message() { return message; }
    public LiveData<Boolean> busy() { return busy; }

    public void setStart(StartPoint point) {
        start.setValue(point);
        suggestion.setValue(null); // a suggestion for another start point is stale
    }

    /**
     * Uses the freshest last-known fix from any enabled provider. Caller must have obtained
     * a location permission first; without it (or without any cached fix) a message asks the
     * user to pick a route start instead. No active GPS request is made — this is a planning
     * screen, a cached fix of the phone's whereabouts is precise enough for a radius.
     */
    @SuppressLint("MissingPermission")
    public void useLastKnownLocation() {
        executor.execute(() -> {
            Location best = null;
            try {
                LocationManager lm = (LocationManager) getApplication()
                        .getSystemService(Context.LOCATION_SERVICE);
                if (lm != null) {
                    for (String provider : lm.getProviders(true)) {
                        Location l = lm.getLastKnownLocation(provider);
                        if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                    }
                }
            } catch (SecurityException e) {
                best = null;
            }
            if (best == null) {
                message.postValue("Geen recente locatie bekend — kies de start van een route.");
                return;
            }
            start.postValue(new StartPoint(best.getLatitude(), best.getLongitude(),
                    "Huidige locatie"));
            suggestion.postValue(null);
        });
    }

    /** Lists stored routes with their first track point, off the main thread. */
    public void loadRouteStarts(RouteStartsCallback callback) {
        executor.execute(() -> {
            List<RouteStart> out = new ArrayList<>();
            for (RouteCatalogEntry entry : routeRepo.loadCatalog()) {
                try {
                    StoredRoute route = routeRepo.loadRoute(entry.routeId);
                    if (route.lats == null || route.lats.length == 0
                            || route.lons == null || route.lons.length == 0) continue;
                    out.add(new RouteStart(routeName(entry), route.lats[0], route.lons[0]));
                } catch (Exception ignored) {
                    // Unreadable route: simply not offered as a start point.
                }
            }
            callback.onRouteStarts(out);
        });
    }

    public void suggest(int targetGainM, double radiusKm, int maxClimbs) {
        StartPoint from = start.getValue();
        if (from == null) {
            message.setValue("Kies eerst een startpunt.");
            return;
        }
        busy.setValue(true);
        executor.execute(() -> {
            double radiusM = radiusKm * 1000.0;
            List<ElevationTargetPlanner.Candidate> candidates = loadCandidates(from, radiusM);
            ElevationTargetPlanner.Suggestion s = ElevationTargetPlanner.plan(
                    new ElevationTargetPlanner.Request(from.lat, from.lon, targetGainM,
                            radiusM, maxClimbs),
                    candidates);
            suggestion.postValue(s);
            busy.postValue(false);
        });
    }

    /**
     * Candidate climbs from every route with at least one climb start inside the radius
     * (catalog pre-filter, same as radius mode); the planner applies the exact per-climb
     * radius filter and dedupes the same climb appearing in several routes.
     */
    private List<ElevationTargetPlanner.Candidate> loadCandidates(StartPoint from, double radiusM) {
        List<ElevationTargetPlanner.Candidate> out = new ArrayList<>();
        for (RouteCatalogEntry entry : routeRepo.findNearby(from.lat, from.lon, radiusM)) {
            StoredRoute route;
            try {
                route = routeRepo.loadRoute(entry.routeId);
            } catch (Exception e) {
                continue;
            }
            if (route.climbs == null) continue;
            List<KnownClimb> known = KnownClimbs.fromRoute(route);
            String routeName = routeName(entry);
            for (int i = 0; i < route.climbs.size(); i++) {
                StoredClimb c = route.climbs.get(i);
                double endLat = Double.NaN;
                double endLon = Double.NaN;
                if (known.size() == route.climbs.size()) {
                    endLat = known.get(i).endLat;
                    endLon = known.get(i).endLon;
                }
                String name = c.userDisplayName != null ? c.userDisplayName
                        : (c.name != null ? c.name : ("Klim " + (i + 1) + " (" + routeName + ")"));
                out.add(new ElevationTargetPlanner.Candidate(
                        ClimbIdentity.of(c), entry.routeId, i, name,
                        c.startLat, c.startLon, endLat, endLon,
                        c.elevationGain, ClimbIdentity.effectiveLength(c)));
            }
        }
        return out;
    }

    /**
     * Adds every suggested climb as its own entry in the existing Klimplanning (issue #70
     * storage), all on the same planned moment, numbered in visiting order. Each gets the
     * normal reminder, exactly as if the user had planned it by hand.
     */
    public void addToPlanning(ElevationTargetPlanner.Suggestion s, long plannedAtEpochSec) {
        if (s == null || s.climbs.isEmpty()) return;
        executor.execute(() -> {
            int n = s.climbs.size();
            int added = 0;
            try {
                for (int i = 0; i < n; i++) {
                    ElevationTargetPlanner.Candidate c = s.climbs.get(i);
                    PlannedClimb plan = new PlannedClimb(
                            UUID.randomUUID().toString(),
                            c.routeId, c.climbIndex,
                            "Hm-doel " + s.targetGainM + " · " + (i + 1) + "/" + n + ": " + c.name,
                            plannedAtEpochSec,
                            System.currentTimeMillis());
                    planRepo.add(plan);
                    PlannedClimbWorkScheduler.schedule(getApplication(), plan);
                    added++;
                }
                message.postValue(added + " klimmen toegevoegd aan de klimplanning.");
            } catch (Exception e) {
                message.postValue("Toevoegen mislukt na " + added + " van " + n
                        + " klimmen: " + e.getMessage());
            }
        });
    }

    private static String routeName(RouteCatalogEntry entry) {
        if (entry.userDisplayName != null) return entry.userDisplayName;
        return entry.name != null ? entry.name : entry.routeId;
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
