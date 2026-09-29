package nl.paree.climbpro.ui.nutrition;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.rider.RiderProfileRepository;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.weather.OpenMeteoClient;
import nl.paree.climbpro.domain.nutrition.FuelPlan;
import nl.paree.climbpro.domain.nutrition.FuelPlanner;
import nl.paree.climbpro.domain.nutrition.RideEffort;
import nl.paree.climbpro.domain.nutrition.RideEffortEstimator;
import nl.paree.climbpro.domain.power.RiderProfile;
import nl.paree.climbpro.domain.weather.HourlyForecast;
import nl.paree.climbpro.service.RouteEffortProfileBuilder;

/**
 * Backs {@link FuelPlannerActivity} (issue #185). Loads the route and rider profile off the
 * main thread, estimates ride time/work with the pacing model, and optionally fetches the
 * forecast temperature at the route start. The plan itself is the pure {@link FuelPlanner}.
 */
public final class FuelPlannerViewModel extends AndroidViewModel {

    /** What the screen needs to know about the route before planning. */
    public static final class RideSummary {
        public final double distanceMeters;
        public final int ascentMeters;
        public final RideEffort effort;
        public final double riderWeightKg;
        final double startLat;
        final double startLon;
        final double startElevation;

        RideSummary(double distanceMeters, int ascentMeters, RideEffort effort,
                    double riderWeightKg, double startLat, double startLon,
                    double startElevation) {
            this.distanceMeters = distanceMeters;
            this.ascentMeters = ascentMeters;
            this.effort = effort;
            this.riderWeightKg = riderWeightKg;
            this.startLat = startLat;
            this.startLon = startLon;
            this.startElevation = startElevation;
        }

        boolean hasStart() {
            return !Double.isNaN(startLat) && !Double.isNaN(startLon);
        }
    }

    private final RouteRepository routeRepo;
    private final RiderProfileRepository riderRepo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<RideSummary> summary = new MutableLiveData<>();
    private final MutableLiveData<Double> forecastTemperature = new MutableLiveData<>();
    private final MutableLiveData<FuelPlan> plan = new MutableLiveData<>();
    private final MutableLiveData<String> message = new MutableLiveData<>();

    public FuelPlannerViewModel(@NonNull Application app) {
        super(app);
        routeRepo = new RouteRepository(app);
        riderRepo = new RiderProfileRepository(app);
    }

    public LiveData<RideSummary> summary() { return summary; }
    /** Forecast mean temperature over the ride when leaving now; NaN when unavailable. */
    public LiveData<Double> forecastTemperature() { return forecastTemperature; }
    public LiveData<FuelPlan> plan() { return plan; }
    public LiveData<String> message() { return message; }

    public void consumeForecastTemperature() { forecastTemperature.setValue(null); }
    public void consumeMessage() { message.setValue(null); }

    public void load(String routeId) {
        executor.execute(() -> {
            try {
                StoredRoute route = routeRepo.loadRoute(routeId);
                RiderProfile profile = riderRepo.load();
                summary.postValue(summarize(route, profile));
            } catch (Exception e) {
                message.postValue(getApplication().getString(
                        R.string.fuel_route_error, e.getMessage()));
            }
        });
    }

    /** Fetches the forecast at the route start and averages it over the estimated ride time. */
    public void fetchTemperature() {
        RideSummary s = summary.getValue();
        if (s == null || !s.hasStart()) {
            forecastTemperature.setValue(Double.NaN);
            return;
        }
        executor.execute(() -> {
            try {
                HourlyForecast f = new OpenMeteoClient().fetch(
                        s.startLat, s.startLon, s.startElevation);
                forecastTemperature.postValue(FuelPlanner.averageTemperature(
                        f, Instant.now(), s.effort.seconds));
            } catch (Exception e) {
                message.postValue(getApplication().getString(R.string.fuel_temperature_failed));
            }
        });
    }

    /** @param temperatureC NaN when the user left it empty. */
    public void calculate(double temperatureC, int bottleMl) {
        RideSummary s = summary.getValue();
        if (s == null) return;
        plan.setValue(FuelPlanner.plan(new FuelPlanner.Input(
                s.effort.seconds, s.effort.workKj, s.ascentMeters, temperatureC,
                s.riderWeightKg, bottleMl)));
    }

    static RideSummary summarize(StoredRoute route, RiderProfile profile) {
        double distance = route.distances != null && route.distances.length > 0
                ? route.distances[route.distances.length - 1] : 0;
        int ascent = RideEffortEstimator.totalAscent(route.elevations);
        if (ascent == 0 && route.climbs != null) {
            for (StoredClimb c : route.climbs) ascent += Math.max(0, c.elevationGain);
        }
        RideEffort effort = RideEffortEstimator.estimate(
                RouteEffortProfileBuilder.build(route), profile);
        if (effort == null) effort = RideEffortEstimator.fallback(distance, ascent);

        boolean hasStart = route.lats != null && route.lats.length > 0
                && route.lons != null && route.lons.length > 0;
        double elev = route.elevations != null && route.elevations.length > 0
                ? route.elevations[0] : Double.NaN;
        return new RideSummary(distance, ascent, effort, profile.riderWeightKg,
                hasStart ? route.lats[0] : Double.NaN, hasStart ? route.lons[0] : Double.NaN,
                elev);
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
