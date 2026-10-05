package nl.paree.climbpro.ui.climbs;

import android.Manifest;
import android.app.Application;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.preference.PreferenceManager;

import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.weather.OpenMeteoClient;
import nl.paree.climbpro.domain.climb.ClimbIdentity;
import nl.paree.climbpro.domain.climb.ClimbOfTheWeek;
import nl.paree.climbpro.domain.weather.DailyForecast;
import nl.paree.climbpro.ui.planning.LastKnownLocation;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Backs {@link ClimbOfTheWeekActivity} (issue #40). All IO (catalog, attempts, last-known
 * location, Open-Meteo) runs on one background executor; the choice itself is the pure
 * {@link ClimbOfTheWeek}. The climb shown is remembered per ISO week in the default
 * SharedPreferences so the suggestion does not change every time the screen opens.
 */
public final class ClimbOfTheWeekViewModel extends AndroidViewModel {

    /** {@code <isoWeek>|<climbId>} of the climb shown this week. */
    static final String PREF_PIN = "climb_of_week_pin";
    private static final int ALTERNATIVES = 3;

    /** What the screen shows. {@code top} is null when there are no climbs at all. */
    public static final class State {
        public final ClimbOfTheWeek.Suggestion top;
        public final List<ClimbOfTheWeek.Suggestion> alternatives;
        public final boolean hasLocationPermission;
        public final String weekKey;

        State(ClimbOfTheWeek.Suggestion top, List<ClimbOfTheWeek.Suggestion> alternatives,
              boolean hasLocationPermission, String weekKey) {
            this.top = top;
            this.alternatives = alternatives;
            this.hasLocationPermission = hasLocationPermission;
            this.weekKey = weekKey;
        }
    }

    private final RouteRepository routeRepo;
    private final ClimbAttemptRepository attemptRepo;
    private final OpenMeteoClient weather = new OpenMeteoClient();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<State> state = new MutableLiveData<>();
    private final MutableLiveData<Boolean> busy = new MutableLiveData<>(false);

    public ClimbOfTheWeekViewModel(@NonNull Application app) {
        super(app);
        routeRepo = new RouteRepository(app);
        attemptRepo = new ClimbAttemptRepository(app);
    }

    public LiveData<State> state() { return state; }
    public LiveData<Boolean> busy() { return busy; }

    /** (Re)computes the suggestion; this week's pin keeps it stable. */
    public void load() {
        busy.setValue(true);
        executor.execute(() -> {
            try {
                state.postValue(compute());
            } finally {
                busy.postValue(false);
            }
        });
    }

    /** Coordinate when there is no location fix; the forecast is then skipped. */
    private static final double NO_POSITION = Double.NaN;

    private State compute() {
        LocalDate today = LocalDate.now();
        String week = ClimbOfTheWeek.weekKey(today);
        List<ClimbOfTheWeek.Candidate> candidates = loadCandidates();

        boolean permission = hasLocationPermission();
        Location fix = permission ? LastKnownLocation.freshest(getApplication()) : null;
        // NaN = no position; kept out of the Location getters so lint's range check holds.
        double lat = fix != null ? fix.getLatitude() : NO_POSITION;
        double lon = fix != null ? fix.getLongitude() : NO_POSITION;

        List<DailyForecast.Day> forecast = fetchForecast(lat, lon, candidates);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplication());
        String pinned = ClimbOfTheWeek.pinnedClimbId(
                prefs.getString(PREF_PIN, null), week);

        List<ClimbOfTheWeek.Suggestion> ranked =
                ClimbOfTheWeek.rank(candidates, lat, lon, today, forecast, pinned);
        if (ranked.isEmpty()) {
            return new State(null, Collections.emptyList(), permission, week);
        }
        ClimbOfTheWeek.Suggestion top = ranked.get(0);
        if (!top.pinned && top.candidate.climbId != null) {
            prefs.edit().putString(PREF_PIN,
                    ClimbOfTheWeek.encodePin(week, top.candidate.climbId)).apply();
        }
        List<ClimbOfTheWeek.Suggestion> alt = new ArrayList<>(
                ranked.subList(1, Math.min(ranked.size(), 1 + ALTERNATIVES)));
        return new State(top, alt, permission, week);
    }

    /**
     * Weather at the rider's location, or — without one — at the start of the most recently
     * ridden climb (else any climb) as a stand-in for the home region. Null when offline.
     */
    private List<DailyForecast.Day> fetchForecast(double lat, double lon,
                                                  List<ClimbOfTheWeek.Candidate> candidates) {
        if (Double.isNaN(lat)) {
            ClimbOfTheWeek.Candidate ref = null;
            for (ClimbOfTheWeek.Candidate c : candidates) {
                if (ref == null) ref = c;
                if (c.lastRiddenEpochDay != null && (ref.lastRiddenEpochDay == null
                        || c.lastRiddenEpochDay > ref.lastRiddenEpochDay)) ref = c;
            }
            if (ref == null) return null;
            lat = ref.startLat;
            lon = ref.startLon;
        }
        try {
            return weather.fetchDaily(lat, lon);
        } catch (Exception e) {
            return null; // offline or service error: weather factor is skipped
        }
    }

    private List<ClimbOfTheWeek.Candidate> loadCandidates() {
        Map<String, Long> lastRidden = new HashMap<>();
        ZoneId zone = ZoneId.systemDefault();
        for (StoredClimbAttempt a : attemptRepo.loadAll()) {
            if (a == null || a.climbId == null) continue;
            long day = Instant.ofEpochSecond(a.dateEpochSec).atZone(zone).toLocalDate()
                    .toEpochDay();
            Long prev = lastRidden.get(a.climbId);
            if (prev == null || day > prev) lastRidden.put(a.climbId, day);
        }

        List<ClimbOfTheWeek.Candidate> out = new ArrayList<>();
        for (RouteCatalogEntry entry : routeRepo.loadCatalog()) {
            StoredRoute route;
            try {
                route = routeRepo.loadRoute(entry.routeId);
            } catch (Exception e) {
                continue;
            }
            if (route.climbs == null) continue;
            String routeName = entry.userDisplayName != null ? entry.userDisplayName
                    : (entry.name != null ? entry.name : entry.routeId);
            for (int i = 0; i < route.climbs.size(); i++) {
                StoredClimb c = route.climbs.get(i);
                if (c == null) continue;
                String id = ClimbIdentity.of(c);
                String name = c.userDisplayName != null ? c.userDisplayName
                        : (c.name != null ? c.name : ("Klim " + (i + 1) + " (" + routeName + ")"));
                out.add(new ClimbOfTheWeek.Candidate(id, entry.routeId, i, name,
                        c.startLat, c.startLon, c.elevationGain, c.avgGradient,
                        ClimbIdentity.effectiveLength(c), lastRidden.get(id)));
            }
        }
        return out;
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(getApplication(),
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(getApplication(),
                Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
