package nl.paree.climbpro.ui.planning;

import android.app.Application;
import android.location.Location;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.data.planning.FavoriteStartPoint;
import nl.paree.climbpro.data.planning.FavoriteStartPointStore;
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.climb.Climb;
import nl.paree.climbpro.domain.climb.ClimbDetector;
import nl.paree.climbpro.domain.planning.LoopGenerator;
import nl.paree.climbpro.domain.route.RoutePoint;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Backs {@link LoopGeneratorActivity} (issue #202). Loads every saved route as a
 * {@link LoopGenerator.Source} off the main thread, runs the pure generator and saves a chosen
 * suggestion as an ordinary new route with climbs detected.
 */
public final class LoopGeneratorViewModel extends AndroidViewModel {

    static final int MAX_SUGGESTIONS = 5;

    public interface FavoritesCallback { void onFavorites(List<FavoriteStartPoint> favorites); }

    private final RouteRepository routeRepo;
    private final FavoriteStartPointStore favoriteStore;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<ElevationTargetViewModel.StartPoint> start = new MutableLiveData<>();
    private final MutableLiveData<List<LoopGenerator.Suggestion>> suggestions = new MutableLiveData<>();
    private final MutableLiveData<String> message = new MutableLiveData<>();
    private final MutableLiveData<Boolean> busy = new MutableLiveData<>(false);
    /** One-shot: id of a just-saved loop route to open. */
    private final MutableLiveData<String> savedRouteId = new MutableLiveData<>();

    private double lastTargetM;

    public LoopGeneratorViewModel(@NonNull Application app) {
        super(app);
        routeRepo = new RouteRepository(app);
        favoriteStore = new FavoriteStartPointStore(
                new File(app.getFilesDir(), FavoriteStartPointStore.FILE_NAME));
    }

    public LiveData<ElevationTargetViewModel.StartPoint> start() { return start; }
    public LiveData<List<LoopGenerator.Suggestion>> suggestions() { return suggestions; }
    public LiveData<String> message() { return message; }
    public LiveData<Boolean> busy() { return busy; }
    public LiveData<String> savedRouteId() { return savedRouteId; }

    public void consumeSavedRouteId() { savedRouteId.setValue(null); }

    public void setStart(ElevationTargetViewModel.StartPoint point) {
        start.setValue(point);
        suggestions.setValue(null);
    }

    /** Freshest cached fix; caller must hold a location permission. */
    public void useLastKnownLocation() {
        executor.execute(() -> {
            Location best = LastKnownLocation.freshest(getApplication());
            String label = best == null ? null : ElevationTargetViewModel.startLabelForFixAge(
                    (SystemClock.elapsedRealtimeNanos() - best.getElapsedRealtimeNanos()) / 1_000_000L);
            if (label == null) {
                message.postValue("Geen recente locatie bekend — kies een favoriet startpunt.");
                return;
            }
            start.postValue(new ElevationTargetViewModel.StartPoint(
                    best.getLatitude(), best.getLongitude(), label));
            suggestions.postValue(null);
        });
    }

    public void loadFavorites(FavoritesCallback callback) {
        executor.execute(() -> callback.onFavorites(favoriteStore.loadAll()));
    }

    public void suggest(double targetKm) {
        ElevationTargetViewModel.StartPoint from = start.getValue();
        if (from == null) {
            message.setValue("Kies eerst een startpunt.");
            return;
        }
        busy.setValue(true);
        lastTargetM = targetKm * 1000.0;
        executor.execute(() -> {
            List<LoopGenerator.Source> sources = new ArrayList<>();
            for (RouteCatalogEntry entry : routeRepo.loadCatalog()) {
                try {
                    StoredRoute r = routeRepo.loadRoute(entry.routeId);
                    List<RoutePoint> pts = toPoints(r);
                    if (pts.size() >= 3) sources.add(new LoopGenerator.Source(
                            entry.routeId, routeName(entry), pts));
                } catch (Exception ignored) {
                    // Unreadable route: simply not used.
                }
            }
            suggestions.postValue(LoopGenerator.suggest(from.lat, from.lon, lastTargetM,
                    sources, MAX_SUGGESTIONS));
            busy.postValue(false);
        });
    }

    /** Saves {@code s} as a new route named "Rondje N km (…)" and posts its id. */
    public void save(LoopGenerator.Suggestion s) {
        busy.setValue(true);
        executor.execute(() -> {
            try {
                List<RoutePoint> pts = s.points();
                List<Climb> climbs = ClimbDetector.detect(pts);
                StoredRoute stored = new StoredRoute();
                stored.routeId = "loop_" + System.currentTimeMillis();
                stored.name = loopName(s.lengthM, s.routeNames);
                stored.importedAtMs = System.currentTimeMillis();
                routeRepo.saveRoute(stored, pts, climbs);
                savedRouteId.postValue(stored.routeId);
            } catch (Exception e) {
                message.postValue("Opslaan mislukt: " + e.getMessage());
            } finally {
                busy.postValue(false);
            }
        });
    }

    static String loopName(double lengthM, List<String> routeNames) {
        return String.format(Locale.US, "Rondje %.0f km (%s)", lengthM / 1000.0,
                String.join(" + ", routeNames));
    }

    private static List<RoutePoint> toPoints(StoredRoute r) {
        List<RoutePoint> pts = new ArrayList<>();
        if (r.lats == null || r.lons == null || r.distances == null) return pts;
        int n = Math.min(Math.min(r.lats.length, r.lons.length), r.distances.length);
        for (int i = 0; i < n; i++) {
            double ele = r.elevations != null && i < r.elevations.length ? r.elevations[i] : Double.NaN;
            pts.add(new RoutePoint(r.lats[i], r.lons[i], ele, r.distances[i]));
        }
        return pts;
    }

    private static String routeName(RouteCatalogEntry entry) {
        if (entry.userDisplayName != null) return entry.userDisplayName;
        return entry.name != null ? entry.name : entry.routeId;
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
