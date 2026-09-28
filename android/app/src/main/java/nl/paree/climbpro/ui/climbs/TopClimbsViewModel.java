package nl.paree.climbpro.ui.climbs;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.climb.ClimbIdentity;
import nl.paree.climbpro.domain.climb.RegionalTopClimbs;
import nl.paree.climbpro.ui.planning.ElevationTargetViewModel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Backs {@link TopClimbsActivity} (issue #211). IO (catalog/route loading, last-known
 * location) runs on a single background executor; ranking is the pure
 * {@link RegionalTopClimbs}.
 */
public final class TopClimbsViewModel extends AndroidViewModel {

    /** Where the radius is measured from. */
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

    /** Result of one search: the ranked climbs plus the radius they were searched in. */
    public static final class Result {
        public final List<RegionalTopClimbs.Ranked> climbs;
        public final int radiusKm;
        Result(List<RegionalTopClimbs.Ranked> climbs, int radiusKm) {
            this.climbs = climbs;
            this.radiusKm = radiusKm;
        }
    }

    public interface RouteStartsCallback { void onRouteStarts(List<StartPoint> starts); }

    private final RouteRepository routeRepo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<StartPoint> start = new MutableLiveData<>();
    private final MutableLiveData<Result> result = new MutableLiveData<>();
    private final MutableLiveData<String> message = new MutableLiveData<>();
    private final MutableLiveData<Boolean> busy = new MutableLiveData<>(false);

    public TopClimbsViewModel(@NonNull Application app) {
        super(app);
        routeRepo = new RouteRepository(app);
    }

    public LiveData<StartPoint> start() { return start; }
    public LiveData<Result> result() { return result; }
    public LiveData<String> message() { return message; }
    public LiveData<Boolean> busy() { return busy; }

    public void setStart(StartPoint point) {
        start.setValue(point);
        result.setValue(null); // a ranking for another start point is stale
    }

    /**
     * Uses the freshest last-known fix from any enabled provider (no active GPS request).
     * Caller must have obtained a location permission first.
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
            String label = best == null ? null : ElevationTargetViewModel.startLabelForFixAge(
                    (SystemClock.elapsedRealtimeNanos() - best.getElapsedRealtimeNanos()) / 1_000_000L);
            if (label == null) {
                message.postValue(getApplication().getString(R.string.top_climbs_no_location));
                return;
            }
            start.postValue(new StartPoint(best.getLatitude(), best.getLongitude(), label));
            result.postValue(null);
        });
    }

    /** Lists stored routes with their first track point as possible start points. */
    public void loadRouteStarts(RouteStartsCallback callback) {
        executor.execute(() -> {
            List<StartPoint> out = new ArrayList<>();
            for (RouteCatalogEntry entry : routeRepo.loadCatalog()) {
                try {
                    StoredRoute route = routeRepo.loadRoute(entry.routeId);
                    if (route.lats == null || route.lats.length == 0
                            || route.lons == null || route.lons.length == 0) continue;
                    out.add(new StartPoint(route.lats[0], route.lons[0], routeName(entry)));
                } catch (Exception ignored) {
                    // Unreadable route: simply not offered as a start point.
                }
            }
            callback.onRouteStarts(out);
        });
    }

    public void search(int radiusKm) {
        StartPoint from = start.getValue();
        if (from == null) {
            message.setValue(getApplication().getString(R.string.top_climbs_pick_start_first));
            return;
        }
        busy.setValue(true);
        executor.execute(() -> {
            double radiusM = radiusKm * 1000.0;
            List<RegionalTopClimbs.Ranked> top = RegionalTopClimbs.top(
                    from.lat, from.lon, radiusM, loadCandidates(from, radiusM),
                    RegionalTopClimbs.DEFAULT_LIMIT);
            result.postValue(new Result(top, radiusKm));
            busy.postValue(false);
        });
    }

    /**
     * Climbs from every route with at least one climb start inside the radius (catalog
     * pre-filter, same as radius mode); {@link RegionalTopClimbs} applies the exact per-climb
     * radius filter and dedupes the same climb appearing in several routes.
     */
    private List<RegionalTopClimbs.Candidate> loadCandidates(StartPoint from, double radiusM) {
        List<RegionalTopClimbs.Candidate> out = new ArrayList<>();
        for (RouteCatalogEntry entry : routeRepo.findNearby(from.lat, from.lon, radiusM)) {
            StoredRoute route;
            try {
                route = routeRepo.loadRoute(entry.routeId);
            } catch (Exception e) {
                continue;
            }
            if (route.climbs == null) continue;
            String routeName = routeName(entry);
            for (int i = 0; i < route.climbs.size(); i++) {
                StoredClimb c = route.climbs.get(i);
                if (c == null) continue;
                String name = c.userDisplayName != null ? c.userDisplayName
                        : (c.name != null ? c.name : ("Klim " + (i + 1) + " (" + routeName + ")"));
                out.add(new RegionalTopClimbs.Candidate(
                        ClimbIdentity.of(c), entry.routeId, i, name,
                        c.startLat, c.startLon, c.elevationGain, c.avgGradient,
                        ClimbIdentity.effectiveLength(c)));
            }
        }
        return out;
    }

    private static String routeName(RouteCatalogEntry entry) {
        if (entry.userDisplayName != null) return entry.userDisplayName;
        return entry.name != null ? entry.name : entry.routeId;
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
