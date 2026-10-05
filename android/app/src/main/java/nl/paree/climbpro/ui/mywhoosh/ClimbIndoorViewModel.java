package nl.paree.climbpro.ui.mywhoosh;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.ride.RideStreamStatsRepository;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.rider.RiderProfileRepository;
import nl.paree.climbpro.data.rider.WeightLogStore;
import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.domain.climb.ClimbIdentity;
import nl.paree.climbpro.domain.mywhoosh.ClimbProgress;
import nl.paree.climbpro.domain.mywhoosh.IndoorClimbPredictor;
import nl.paree.climbpro.domain.mywhoosh.IndoorRides;
import nl.paree.climbpro.domain.mywhoosh.VirtualClimbLinker;
import nl.paree.climbpro.domain.power.ClimbTimeEstimate;
import nl.paree.climbpro.domain.power.RiderProfile;
import nl.paree.climbpro.domain.rider.WeightHistory;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Indoor view of one climb: progression over all attempts (issue #387), the linked real or
 * MyWhoosh counterpart (issue #395) and the outdoor time predicted from indoor power (issue
 * #396). Loaded off the main thread; phone-only.
 */
public final class ClimbIndoorViewModel extends AndroidViewModel {

    /** Attempts summary for one side of the indoor/outdoor comparison. */
    public static final class Side {
        public final String label;
        public final int attempts;
        /** 0 when none. */
        public final int bestSec;
        public final Integer bestWatts;
        public final Double bestWkg;

        Side(String label, int attempts, int bestSec, Integer bestWatts, Double bestWkg) {
            this.label = label;
            this.attempts = attempts;
            this.bestSec = bestSec;
            this.bestWatts = bestWatts;
            this.bestWkg = bestWkg;
        }
    }

    public static final class State {
        public final String climbName;
        public final boolean virtual;
        public final List<ClimbProgress.Point> progress;
        /** Null without a counterpart. */
        public final VirtualClimbLinker.Candidate counterpart;
        public final Side self;
        public final Side other;
        /** The climb the prediction is for (this one, or the real counterpart); may be null. */
        public final String predictedClimbName;
        public final IndoorClimbPredictor.Basis basis;
        public final ClimbTimeEstimate prediction;
        public final double weightKg;
        public final String predictionHint;

        State(String climbName, boolean virtual, List<ClimbProgress.Point> progress,
              VirtualClimbLinker.Candidate counterpart, Side self, Side other,
              String predictedClimbName, IndoorClimbPredictor.Basis basis,
              ClimbTimeEstimate prediction, double weightKg, String predictionHint) {
            this.climbName = climbName;
            this.virtual = virtual;
            this.progress = progress;
            this.counterpart = counterpart;
            this.self = self;
            this.other = other;
            this.predictedClimbName = predictedClimbName;
            this.basis = basis;
            this.prediction = prediction;
            this.weightKg = weightKg;
            this.predictionHint = predictionHint;
        }
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<State> state = new MutableLiveData<>();
    private final MutableLiveData<String> error = new MutableLiveData<>();

    public ClimbIndoorViewModel(@NonNull Application app) {
        super(app);
    }

    public LiveData<State> state() { return state; }
    public LiveData<String> error() { return error; }

    public void load(String routeId, int climbIndex) {
        executor.execute(() -> {
            try {
                state.postValue(compute(routeId, climbIndex));
            } catch (Exception e) {
                error.postValue("Klim laden mislukt: " + e.getMessage());
            }
        });
    }

    private State compute(String routeId, int climbIndex) throws Exception {
        Application app = getApplication();
        RouteRepository routes = new RouteRepository(app);
        StoredRoute route = routes.loadRoute(routeId);
        if (route.climbs == null || climbIndex < 0 || climbIndex >= route.climbs.size()) {
            throw new IllegalStateException("klim niet gevonden");
        }
        StoredClimb climb = route.climbs.get(climbIndex);
        boolean virtual = isMyWhooshRoute(route.name);
        String climbId = climbId(climb);
        String climbName = name(climb, climbIndex);

        List<StoredRide> rides = new RideRepository(app).loadAll();
        Set<Long> indoorIds = IndoorRides.indoorIds(rides);
        RiderProfile profile = new RiderProfileRepository(app).load();
        WeightHistory weights = WeightLogStore.of(app).history(profile.riderWeightKg,
                ZoneId.systemDefault());
        List<StoredClimbAttempt> attempts = new ClimbAttemptRepository(app).loadAll();

        List<ClimbProgress.Point> progress =
                ClimbProgress.compute(climbId, attempts, indoorIds, weights);

        // #395: the counterpart on the other side, among every climb in the collection.
        List<VirtualClimbLinker.Candidate> all = new ArrayList<>();
        VirtualClimbLinker.Candidate self = null;
        for (RouteCatalogEntry e : routes.loadCatalog()) {
            StoredRoute r;
            try {
                r = e.routeId.equals(routeId) ? route : routes.loadRoute(e.routeId);
            } catch (Exception ex) {
                continue;
            }
            if (r.climbs == null) continue;
            String routeName = r.userDisplayName != null ? r.userDisplayName : r.name;
            boolean v = isMyWhooshRoute(r.name);
            for (int i = 0; i < r.climbs.size(); i++) {
                StoredClimb c = r.climbs.get(i);
                VirtualClimbLinker.Candidate cand = new VirtualClimbLinker.Candidate(
                        climbId(c), name(c, i), stripMyWhoosh(routeName), length(c),
                        c.avgGradient, v, e.routeId, i);
                if (e.routeId.equals(routeId) && i == climbIndex) self = cand;
                all.add(cand);
            }
        }
        VirtualClimbLinker.Candidate counterpart = self != null
                ? VirtualClimbLinker.counterpart(self, all) : null;
        Side selfSide = side(virtual ? "Indoor (MyWhoosh)" : "Buiten", climbId, attempts, weights);
        Side otherSide = counterpart != null
                ? side(counterpart.virtual ? "Indoor (MyWhoosh)" : "Buiten", counterpart.climbId,
                        attempts, weights)
                : null;

        // #396: predict the real climb — this one, or the real counterpart of a MyWhoosh climb.
        StoredClimb target = null;
        String targetName = null;
        if (!virtual) {
            target = climb;
            targetName = climbName;
        } else if (counterpart != null) {
            StoredRoute r = routes.loadRoute(counterpart.routeId);
            target = r.climbs.get(counterpart.climbIndex);
            targetName = counterpart.name;
        }
        IndoorClimbPredictor.Basis basis = IndoorClimbPredictor.basis(rides,
                new RideStreamStatsRepository(app).loadById(), System.currentTimeMillis() / 1000L);
        double weightKg = weights.weightOn(LocalDate.now());
        ClimbTimeEstimate prediction = null;
        String hint = null;
        if (target == null) {
            hint = "Geen echte klim gekoppeld aan deze MyWhoosh-klim, dus niets te voorspellen.";
        } else if (basis == null) {
            hint = "Nog geen indoorrit met 20 minuten vermogen in de laatste "
                    + IndoorClimbPredictor.WINDOW_DAYS + " dagen.";
        } else if (weightKg <= 0 || profile.bikeWeightKg <= 0) {
            hint = "Vul je gewicht en het gewicht van je fiets in bij Instellingen.";
        } else if (target.segments == null || target.segments.isEmpty()) {
            hint = "Deze klim heeft geen segmenten.";
        } else {
            List<StoredSegment> segs = target.segments;
            int[] dist = new int[segs.size()];
            double[] grad = new double[segs.size()];
            int[] surface = new int[segs.size()];
            for (int i = 0; i < segs.size(); i++) {
                dist[i] = segs.get(i).distance;
                grad[i] = segs.get(i).gradient;
                surface[i] = segs.get(i).surfaceType;
            }
            prediction = IndoorClimbPredictor.predict(basis, weightKg, profile.bikeWeightKg,
                    dist, grad, surface);
        }
        return new State(climbName, virtual, progress, counterpart, selfSide, otherSide,
                targetName, basis, prediction, weightKg, hint);
    }

    private static Side side(String label, String climbId, List<StoredClimbAttempt> attempts,
                             WeightHistory weights) {
        int count = 0;
        int best = 0;
        Integer bestWatts = null;
        Double bestWkg = null;
        for (StoredClimbAttempt a : attempts) {
            if (!climbId.equals(a.climbId)) continue;
            count++;
            if (!a.routeDeviation && a.elapsedSec > 0 && (best == 0 || a.elapsedSec < best)) {
                best = a.elapsedSec;
            }
            if (a.avgWatts != null && (bestWatts == null || a.avgWatts > bestWatts)) {
                bestWatts = a.avgWatts;
            }
            Double w = weights.wattsPerKg(a.avgWatts, a.dateEpochSec);
            if (w != null && (bestWkg == null || w > bestWkg)) bestWkg = w;
        }
        return new Side(label, count, best, bestWatts, bestWkg);
    }

    static boolean isMyWhooshRoute(String routeName) {
        return routeName != null
                && routeName.trim().toLowerCase(Locale.ROOT).startsWith("mywhoosh");
    }

    private static String stripMyWhoosh(String name) {
        return name == null ? null : name.replaceFirst("(?i)^mywhoosh\\s*[–-]?\\s*", "");
    }

    private static int length(StoredClimb c) {
        return c.length > 0 ? c.length : (c.endDistance - c.startDistance);
    }

    private static String climbId(StoredClimb c) {
        return ClimbIdentity.of(c.startLat, c.startLon, length(c));
    }

    private static String name(StoredClimb c, int index) {
        if (c.userDisplayName != null) return c.userDisplayName;
        return c.name != null ? c.name : "Klim " + (index + 1);
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
