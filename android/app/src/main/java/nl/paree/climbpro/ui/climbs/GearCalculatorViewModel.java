package nl.paree.climbpro.ui.climbs;

import android.app.Application;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.preference.PreferenceManager;

import nl.paree.climbpro.data.rider.RiderProfileRepository;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.domain.power.ClimbTimeEstimate;
import nl.paree.climbpro.domain.power.ClimbTimeEstimator;
import nl.paree.climbpro.domain.power.GearCalculator;
import nl.paree.climbpro.domain.power.RiderProfile;
import nl.paree.climbpro.domain.power.SurfaceRollingResistance;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Issue #188: gear calculator for one climb. Loads the climb and the rider profile, derives the
 * sustainable power on this climb from the time estimate and runs {@link GearCalculator}. The
 * rider's gearing inputs are remembered in default SharedPreferences. Phone-only.
 */
public final class GearCalculatorViewModel extends AndroidViewModel {

    static final String PREF_CHAINRINGS = "gear_chainrings";
    static final String PREF_CASSETTE = "gear_cassette";
    static final String PREF_WHEEL_MM = "gear_wheel_circumference_mm";
    static final String PREF_CADENCE = "gear_target_cadence_rpm";

    /** Used when the rider profile is incomplete, so the screen still gives a rough answer. */
    static final RiderProfile FALLBACK_PROFILE = new RiderProfile(200, 72, 8);

    /** Saved gearing inputs. */
    public static final class Inputs {
        public final String chainrings;
        public final String cassette;
        public final int wheelMm;
        public final int cadenceRpm;

        public Inputs(String chainrings, String cassette, int wheelMm, int cadenceRpm) {
            this.chainrings = chainrings;
            this.cassette = cassette;
            this.wheelMm = wheelMm;
            this.cadenceRpm = cadenceRpm;
        }
    }

    /** Either a result or a Dutch error; {@link #fallbackProfile} marks a rough estimate. */
    public static final class State {
        public final String climbName;
        public final GearCalculator.Result result;
        public final double powerWatts;
        public final boolean fallbackProfile;
        public final String error;

        State(String climbName, GearCalculator.Result result, double powerWatts,
              boolean fallbackProfile, String error) {
            this.climbName = climbName;
            this.result = result;
            this.powerWatts = powerWatts;
            this.fallbackProfile = fallbackProfile;
            this.error = error;
        }
    }

    private final RouteRepository routeRepo;
    private final RiderProfileRepository profileRepo;
    private final SharedPreferences prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<State> state = new MutableLiveData<>();

    public GearCalculatorViewModel(@NonNull Application app) {
        super(app);
        routeRepo = new RouteRepository(app);
        profileRepo = new RiderProfileRepository(app);
        prefs = PreferenceManager.getDefaultSharedPreferences(app);
    }

    public LiveData<State> state() { return state; }

    public Inputs savedInputs() {
        return new Inputs(prefs.getString(PREF_CHAINRINGS, "50/34"),
                prefs.getString(PREF_CASSETTE, "11-30"),
                prefs.getInt(PREF_WHEEL_MM, GearCalculator.DEFAULT_WHEEL_CIRCUMFERENCE_MM),
                prefs.getInt(PREF_CADENCE, GearCalculator.DEFAULT_TARGET_CADENCE_RPM));
    }

    public void calculate(String routeId, int climbIndex, Inputs in) {
        executor.execute(() -> {
            prefs.edit()
                    .putString(PREF_CHAINRINGS, in.chainrings)
                    .putString(PREF_CASSETTE, in.cassette)
                    .putInt(PREF_WHEEL_MM, in.wheelMm)
                    .putInt(PREF_CADENCE, in.cadenceRpm)
                    .apply();
            state.postValue(compute(routeId, climbIndex, in));
        });
    }

    private State compute(String routeId, int climbIndex, Inputs in) {
        StoredClimb climb = loadClimb(routeId, climbIndex);
        if (climb == null || climb.segments == null || climb.segments.isEmpty()) {
            return new State(null, null, 0, false, "Klim niet gevonden of nog niet gesegmenteerd.");
        }
        String name = climb.userDisplayName != null ? climb.userDisplayName
                : climb.name != null ? climb.name : "Klim " + (climbIndex + 1);
        int[] chainrings;
        int[] sprockets;
        try {
            chainrings = GearCalculator.parseChainrings(in.chainrings);
            sprockets = GearCalculator.parseCassette(in.cassette);
        } catch (IllegalArgumentException e) {
            return new State(name, null, 0, false, e.getMessage());
        }
        if (in.wheelMm < 1000 || in.wheelMm > 3000) {
            return new State(name, null, 0, false, "Wielomtrek moet tussen 1000 en 3000 mm liggen.");
        }
        if (in.cadenceRpm < 30 || in.cadenceRpm > 150) {
            return new State(name, null, 0, false, "Cadans moet tussen 30 en 150 rpm liggen.");
        }

        int n = climb.segments.size();
        int[] dist = new int[n];
        double[] grad = new double[n];
        int[] surface = new int[n];
        StoredSegment steepest = climb.segments.get(0);
        for (int i = 0; i < n; i++) {
            StoredSegment s = climb.segments.get(i);
            dist[i] = s.distance;
            grad[i] = s.gradient;
            surface[i] = s.surfaceType;
            if (s.gradient > steepest.gradient) steepest = s;
        }

        RiderProfile profile = profileRepo.load();
        boolean fallback = !profile.isComplete();
        if (fallback) profile = FALLBACK_PROFILE;
        ClimbTimeEstimate estimate = ClimbTimeEstimator.estimate(dist, grad, surface, profile);
        double power = estimate != null ? estimate.assumedPowerWatts : profile.ftpWatts;

        GearCalculator.Result result = GearCalculator.compute(chainrings, sprockets, in.wheelMm,
                in.cadenceRpm, steepest.gradient, climb.avgGradient, power,
                profile.totalMassKg(), SurfaceRollingResistance.crr(steepest.surfaceType));
        return new State(name, result, power, fallback, null);
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
