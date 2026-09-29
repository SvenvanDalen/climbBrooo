package nl.paree.climbpro.ui.climbs;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.rider.RiderProfileRepository;
import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.domain.climb.ClimbGpxWriter;
import nl.paree.climbpro.domain.climb.ClimbIdentity;
import nl.paree.climbpro.domain.climb.ClimbPacingAdvisor;
import nl.paree.climbpro.domain.climb.CoordinateFuzzer;
import nl.paree.climbpro.domain.climb.LogbookCalculator;
import nl.paree.climbpro.domain.climb.LogbookCalculator.HistoryRow;
import nl.paree.climbpro.domain.climb.PrChancePredictor;
import nl.paree.climbpro.domain.climb.SeasonalComparisonCalculator;
import nl.paree.climbpro.domain.climb.SegmentPrCalculator;
import nl.paree.climbpro.domain.power.ClimbTimeEstimate;
import nl.paree.climbpro.domain.power.ClimbTimeEstimator;
import nl.paree.climbpro.domain.power.RiderProfile;
import nl.paree.climbpro.domain.export.ClimbWorkoutWriter;
import nl.paree.climbpro.domain.power.RouteAwareClimbEstimator;
import nl.paree.climbpro.domain.power.RouteTile;
import nl.paree.climbpro.domain.training.FitnessCalculator;
import nl.paree.climbpro.domain.weather.ClimbEndpoints;
import nl.paree.climbpro.domain.weather.HourlyForecast;
import nl.paree.climbpro.data.weather.OpenMeteoClient;
import nl.paree.climbpro.service.RouteEffortProfileBuilder;

import java.io.File;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ClimbDetailViewModel extends AndroidViewModel {

    private static final String TAG = "ClimbDetailVM";

    private final RouteRepository routeRepo;
    private final RiderProfileRepository riderRepo;
    private final ClimbAttemptRepository attemptRepo;
    private final RideRepository rideRepo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<StoredClimb>       climb        = new MutableLiveData<>();
    private final MutableLiveData<StoredRoute>       route        = new MutableLiveData<>();
    private final MutableLiveData<String>            error        = new MutableLiveData<>();
    private final MutableLiveData<Boolean>           saved        = new MutableLiveData<>(false);
    private final MutableLiveData<ClimbTimeEstimate> timeEstimate = new MutableLiveData<>();
    private final MutableLiveData<List<HistoryRow>>  history      = new MutableLiveData<>();
    private final MutableLiveData<SeasonalComparisonCalculator.Result> seasonalComparison =
            new MutableLiveData<>();
    private final MutableLiveData<File>              gpxExportFile = new MutableLiveData<>();
    private final MutableLiveData<TrainingAdvice>    trainingAdvice = new MutableLiveData<>();
    private final MutableLiveData<WorkoutExport>     workoutExport = new MutableLiveData<>();
    private final MutableLiveData<PrChance>          prChance      = new MutableLiveData<>();

    private volatile StoredClimb lastClimb;
    private volatile StoredRoute lastRoute;
    private volatile int lastClimbIndex;

    public ClimbDetailViewModel(@NonNull Application app) {
        super(app);
        routeRepo = new RouteRepository(app);
        riderRepo = new RiderProfileRepository(app);
        attemptRepo = new ClimbAttemptRepository(app);
        rideRepo = new RideRepository(app);
    }

    public LiveData<StoredClimb>       climb()        { return climb; }
    public LiveData<StoredRoute>       route()        { return route; }
    public LiveData<String>            error()        { return error; }
    public LiveData<Boolean>           saved()        { return saved; }
    public LiveData<ClimbTimeEstimate> timeEstimate() { return timeEstimate; }
    public LiveData<List<HistoryRow>>  history()      { return history; }
    public LiveData<SeasonalComparisonCalculator.Result> seasonalComparison() {
        return seasonalComparison;
    }
    public LiveData<File>              gpxExportFile() { return gpxExportFile; }
    /** Pacing advice for the latest analysable attempt (issue #64); null = none. */
    public LiveData<TrainingAdvice>    trainingAdvice() { return trainingAdvice; }

    /** {@link ClimbPacingAdvisor} output plus the date of the attempt it is about. */
    public static final class TrainingAdvice {
        public final long attemptDateEpochSec;
        public final ClimbPacingAdvisor.Advice advice;

        TrainingAdvice(long attemptDateEpochSec, ClimbPacingAdvisor.Advice advice) {
            this.attemptDateEpochSec = attemptDateEpochSec;
            this.advice = advice;
        }
    }
    public LiveData<WorkoutExport>     workoutExport() { return workoutExport; }
    public LiveData<PrChance>          prChance()      { return prChance; }

    /** Issue #58: the PR-chance prediction and whether the weather could be taken into account. */
    public static final class PrChance {
        public final PrChancePredictor.Prediction prediction;
        public final boolean weatherIncluded;

        PrChance(PrChancePredictor.Prediction prediction, boolean weatherIncluded) {
            this.prediction = prediction;
            this.weatherIncluded = weatherIncluded;
        }
    }

    /** A written indoor-workout file and the MIME type to share it with (issue #223). */
    public static final class WorkoutExport {
        public final File file;
        public final String mime;

        WorkoutExport(File file, String mime) {
            this.file = file;
            this.mime = mime;
        }
    }

    public void loadClimb(String routeId, int climbIndex) {
        executor.execute(() -> {
            try {
                StoredRoute r = routeRepo.loadRoute(routeId);
                lastRoute = r;
                lastClimbIndex = climbIndex;
                route.postValue(r);
                if (r.climbs != null && climbIndex < r.climbs.size()) {
                    StoredClimb loaded = r.climbs.get(climbIndex);
                    lastClimb = loaded;
                    climb.postValue(loaded);
                    computeEstimate(loaded);
                    int len = loaded.length > 0
                            ? loaded.length : (loaded.endDistance - loaded.startDistance);
                    if (len <= 0) {
                        android.util.Log.w(TAG, "Climb length resolved to " + len
                                + " for route climb; history may be empty");
                    }
                    String climbId = ClimbIdentity.of(loaded.startLat, loaded.startLon, len);
                    List<StoredClimbAttempt> attempts = attemptRepo.loadAll();
                    history.postValue(LogbookCalculator.historyFor(climbId, attempts));
                    seasonalComparison.postValue(
                            SeasonalComparisonCalculator.compare(climbId, attempts));
                    trainingAdvice.postValue(computeTrainingAdvice(loaded, climbId, attempts));
                    predictPrChance(r, loaded, climbId, attempts);
                } else {
                    error.postValue("Climb not found");
                }
            } catch (Exception e) {
                error.postValue("Load failed: " + e.getMessage());
            }
        });
    }

    /**
     * Issue #58: posts the offline prediction (history + fitness) straight away, then fetches the
     * summit weather on its own thread and reposts with it. Offline the first result simply stays.
     */
    private void predictPrChance(StoredRoute r, StoredClimb c, String climbId,
                                 List<StoredClimbAttempt> attempts) {
        long now = System.currentTimeMillis() / 1000L;
        PrChancePredictor.Prediction history =
                PrChancePredictor.predict(climbId, attempts, null, null, now);
        if (history.firstAttempt) {
            prChance.postValue(new PrChance(history, false));
            return;
        }
        PrChancePredictor.Fitness fitness = null;
        try {
            ZoneId zone = ZoneId.systemDefault();
            LocalDate today = LocalDate.now(zone);
            LocalDate prDate = history.prDateEpochSec > 0
                    ? Instant.ofEpochSecond(history.prDateEpochSec).atZone(zone).toLocalDate()
                    : null;
            int window = prDate != null && !prDate.isAfter(today)
                    ? (int) ChronoUnit.DAYS.between(prDate, today) + 1 : 1;
            FitnessCalculator.Result result = FitnessCalculator.compute(rideRepo.loadAll(),
                    riderRepo.load().ftpWatts, today, zone, window);
            fitness = PrChancePredictor.fitnessFrom(result, prDate);
        } catch (Exception e) {
            android.util.Log.w(TAG, "PR chance: fitness unavailable", e);
        }
        prChance.postValue(new PrChance(
                PrChancePredictor.predict(climbId, attempts, fitness, null, now), false));

        final PrChancePredictor.Fitness fit = fitness;
        ClimbEndpoints.Point top = ClimbEndpoints.top(r, c);
        new Thread(() -> {
            try {
                HourlyForecast f = new OpenMeteoClient().fetch(top);
                PrChancePredictor.Weather w = PrChancePredictor.Weather.from(f, Instant.now());
                if (w == null) return;
                prChance.postValue(new PrChance(
                        PrChancePredictor.predict(climbId, attempts, fit, w, now), true));
            } catch (Exception e) {
                // Offline or service down: the prediction without weather stays on screen.
                android.util.Log.i(TAG, "PR chance: no weather (" + e.getMessage() + ")");
            }
        }, "pr-chance-weather").start();
    }

    public void renameClimb(String routeId, int climbIndex, String newName) {
        executor.execute(() -> {
            try {
                routeRepo.renameClimb(routeId, climbIndex, newName);
                loadClimb(routeId, climbIndex);
                saved.postValue(true);
            } catch (Exception e) {
                error.postValue("Rename failed: " + e.getMessage());
            }
        });
    }

    /**
     * Sets (or clears, when {@code shapeName} is null) a manual override of the climb's shape
     * tag (issue #36). Mirrors {@link #renameClimb}: persist, reload, flag saved/error.
     */
    public void setShapeOverride(String routeId, int climbIndex, String shapeName) {
        executor.execute(() -> {
            try {
                routeRepo.setClimbShapeOverride(routeId, climbIndex, shapeName);
                loadClimb(routeId, climbIndex);
                saved.postValue(true);
            } catch (Exception e) {
                error.postValue("Opslaan mislukt: " + e.getMessage());
            }
        });
    }

    public void reSegment(String routeId, int climbIndex, int newSegmentCount) {
        executor.execute(() -> {
            try {
                routeRepo.reSegmentClimb(routeId, climbIndex, newSegmentCount);
                loadClimb(routeId, climbIndex);
                saved.postValue(true);
            } catch (Exception e) {
                error.postValue("Herberekening mislukt: " + e.getMessage());
            }
        });
    }

    public void setSurfaceType(String routeId, int climbIndex, int segmentIndex, int surfaceType) {
        executor.execute(() -> {
            try {
                routeRepo.setSegmentSurfaceType(routeId, climbIndex, segmentIndex, surfaceType);
                loadClimb(routeId, climbIndex);
                saved.postValue(true);
            } catch (Exception e) {
                error.postValue("Opslaan mislukt: " + e.getMessage());
            }
        });
    }

    /**
     * Sets or clears a segment's manual pacing target (issue #23). {@code targetSec} null
     * reverts the segment to the automatic {@code RoutePacingPlanner} value.
     */
    public void setSegmentManualTargetSec(String routeId, int climbIndex, int segmentIndex,
                                           Integer targetSec) {
        executor.execute(() -> {
            try {
                routeRepo.setSegmentManualTargetSec(routeId, climbIndex, segmentIndex, targetSec);
                loadClimb(routeId, climbIndex);
                saved.postValue(true);
            } catch (Exception e) {
                error.postValue("Opslaan mislukt: " + e.getMessage());
            }
        });
    }

    /**
     * Sets or clears the climb's manually-entered WR/pro reference time (issue #59).
     * Pass a null/non-positive {@code refSec} to clear.
     */
    public void setManualRefTime(String routeId, int climbIndex, Integer refSec, String label) {
        executor.execute(() -> {
            try {
                routeRepo.setManualRefTime(routeId, climbIndex, refSec, label);
                loadClimb(routeId, climbIndex);
                saved.postValue(true);
            } catch (Exception e) {
                error.postValue("Opslaan mislukt: " + e.getMessage());
            }
        });
    }

    public void setBulkSurfaceType(String routeId, int climbIndex, int surfaceType) {
        executor.execute(() -> {
            try {
                routeRepo.setBulkClimbSurfaceType(routeId, climbIndex, surfaceType);
                loadClimb(routeId, climbIndex);
                saved.postValue(true);
            } catch (Exception e) {
                error.postValue("Opslaan mislukt: " + e.getMessage());
            }
        });
    }

    /**
     * Marks/unmarks the loaded climb as a "thuisklim" (issue #92). Only a phone-side privacy
     * flag consumed by {@link #exportGpx()} — never sent to the watch, never affects matching
     * or PR calculations.
     */
    public void setHomeClimb(String routeId, int climbIndex, boolean isHome) {
        executor.execute(() -> {
            try {
                routeRepo.setClimbHome(routeId, climbIndex, isHome);
                loadClimb(routeId, climbIndex);
                saved.postValue(true);
            } catch (Exception e) {
                error.postValue("Opslaan mislukt: " + e.getMessage());
            }
        });
    }

    /** Saves the rider's rating of this climb (issue #244); all-null clears it. */
    public void setRating(String routeId, int climbIndex, Integer road, Integer traffic,
                          Integer view, String note) {
        executor.execute(() -> {
            try {
                routeRepo.setClimbRating(routeId, climbIndex, road, traffic, view, note);
                loadClimb(routeId, climbIndex);
                saved.postValue(true);
            } catch (Exception e) {
                error.postValue("Opslaan mislukt: " + e.getMessage());
            }
        });
    }

    /**
     * Builds a GPX 1.1 export of the currently loaded climb (issue #79) — its geometry as a
     * track, plus waypoints at every segment boundary and, when PR data exists, at the
     * climb's personal-record splits — and writes it to the app cache. Posts the resulting
     * {@link File} to {@link #gpxExportFile()} for the Activity to hand off to the share
     * sheet; PR lookup and file I/O both happen off the main thread.
     */
    /**
     * Writes the climb as an indoor workout (issue #223): Zwift {@code .zwo} when {@code zwift},
     * else ERG. Uses the fresh per-climb estimate, not the route-aware one: indoors you start
     * the climb rested. Needs a complete rider profile for the power targets.
     */
    public void exportWorkout(boolean zwift) {
        StoredClimb c = lastClimb;
        if (c == null || c.segments == null || c.segments.isEmpty()) {
            error.postValue("Klim nog niet geladen");
            return;
        }
        executor.execute(() -> {
            try {
                RiderProfile profile = riderRepo.load();
                List<StoredSegment> segs = c.segments;
                int[] dist = new int[segs.size()];
                double[] grad = new double[segs.size()];
                int[] surface = new int[segs.size()];
                for (int i = 0; i < segs.size(); i++) {
                    dist[i] = segs.get(i).distance;
                    grad[i] = segs.get(i).gradient;
                    surface[i] = segs.get(i).surfaceType;
                }
                ClimbWorkoutWriter.Plan plan =
                        ClimbWorkoutWriter.plan(dist, grad, surface, profile);
                if (plan == null) {
                    error.postValue("Vul eerst je FTP en gewicht in bij Instellingen; daarmee "
                            + "worden de vermogensdoelen per segment berekend.");
                    return;
                }
                String name = c.userDisplayName != null && !c.userDisplayName.trim().isEmpty()
                        ? c.userDisplayName : c.name;
                String content = zwift ? ClimbWorkoutWriter.toZwo(name, plan.steps)
                        : ClimbWorkoutWriter.toErg(name, plan.steps, plan.ftpWatts);
                File file = ClimbWorkoutExportHandoff.writeFile(getApplication(), content,
                        ClimbWorkoutWriter.fileName(name, zwift ? "zwo" : "erg"));
                workoutExport.postValue(new WorkoutExport(file, zwift
                        ? ClimbWorkoutExportHandoff.ZWO_MIME : ClimbWorkoutExportHandoff.ERG_MIME));
            } catch (Exception e) {
                error.postValue("Workout-export mislukt: " + e.getMessage());
            }
        });
    }

    public void exportGpx() {
        StoredClimb c = lastClimb;
        StoredRoute r = lastRoute;
        if (c == null || r == null) {
            error.postValue("Klim nog niet geladen");
            return;
        }
        executor.execute(() -> {
            try {
                int segCount = c.segments != null ? c.segments.size() : 0;
                List<StoredClimbAttempt> attempts = attemptRepo.loadAll();
                int len = c.length > 0 ? c.length : (c.endDistance - c.startDistance);
                String climbId = ClimbIdentity.of(c.startLat, c.startLon, len);

                int[] bestSplitSec = SegmentPrCalculator.bestSplits(climbId, segCount, attempts);
                Integer bestElapsedSec = null;
                Map<String, LogbookCalculator.Summary> summaries = LogbookCalculator.summaries(attempts);
                LogbookCalculator.Summary summary = summaries.get(climbId);
                if (summary != null) bestElapsedSec = summary.prSec;

                int privacyRadiusM = CoordinateFuzzer.effectiveRadius(
                        androidx.preference.PreferenceManager
                                .getDefaultSharedPreferences(getApplication())
                                .getInt(CoordinateFuzzer.PREF_PRIVACY_RADIUS_M,
                                        CoordinateFuzzer.DEFAULT_PRIVACY_RADIUS_M));
                if (c.isHome && !CoordinateFuzzer.isUsableZoneCentre(c.privacyCentreLat,
                        c.privacyCentreLon, c.startLat, c.startLon, privacyRadiusM)) {
                    // First export, radius shrunk below the stored offset, or a resync moved
                    // the start: draw a fresh centre and persist it before anything is shared,
                    // so later exports reuse it instead of leaking a new one each time.
                    double[] centre = CoordinateFuzzer.randomZoneCentre(
                            c.startLat, c.startLon, privacyRadiusM, new SecureRandom());
                    routeRepo.setClimbPrivacyCentre(r.routeId, lastClimbIndex, centre[0], centre[1]);
                    c.privacyCentreLat = centre[0];
                    c.privacyCentreLon = centre[1];
                }
                String gpx = ClimbGpxWriter.toGpx(r, c, lastClimbIndex, bestSplitSec,
                        bestElapsedSec, privacyRadiusM);
                File file = ClimbGpxExportHandoff.writeGpxFile(getApplication(), gpx);
                gpxExportFile.postValue(file);
            } catch (Exception e) {
                error.postValue("GPX-export mislukt: " + e.getMessage());
            }
        });
    }

    /**
     * Attaches/updates a note, the riding companions (issue #243) and/or photo on one existing
     * attempt (issue #46). {@code companions} is free text, normalised via
     * {@link nl.paree.climbpro.domain.ride.SummitGroupPhotos#normalizeCompanions}; blank
     * clears it. {@code
     * photoUri}, when non-null, is copied into {@code getFilesDir()/attempt_photos/} via
     * {@link nl.paree.climbpro.data.route.AttemptPhotoStore}; pass null to leave the attempt's
     * current photo untouched, and an empty/blank {@code note} to clear it. Identity is
     * (climbId derived from the loaded climb, activityId, passIndex) — the same key {@link
     * ClimbAttemptRepository#update} matches on. Reloads the climb afterward so the history
     * list picks up the change.
     */
    public void saveAttemptNote(String routeId, int climbIndex, long activityId, int passIndex,
                                 String note, String companions, android.net.Uri photoUri) {
        StoredClimb c = lastClimb;
        if (c == null) {
            error.postValue("Klim nog niet geladen");
            return;
        }
        executor.execute(() -> {
            try {
                int len = c.length > 0 ? c.length : (c.endDistance - c.startDistance);
                String climbId = ClimbIdentity.of(c.startLat, c.startLon, len);

                StoredClimbAttempt target = null;
                for (StoredClimbAttempt a : attemptRepo.loadAll()) {
                    if (climbId.equals(a.climbId) && a.activityId == activityId
                            && a.passIndex == passIndex) {
                        target = a;
                        break;
                    }
                }
                if (target == null) {
                    error.postValue("Attempt niet gevonden");
                    return;
                }

                target.note = (note == null || note.trim().isEmpty()) ? null : note.trim();
                target.companions = nl.paree.climbpro.domain.ride.SummitGroupPhotos
                        .normalizeCompanions(companions);

                // Write the NEW photo first, but don't touch the OLD one yet — if the JSON
                // record update below fails, we must be able to roll back to a state where
                // the attempt still has a valid, working photo reference (see saveAttemptNote
                // javadoc). Only once attemptRepo.update() confirms success do we delete the
                // old file; only on failure do we delete the new one instead.
                String oldPhoto = target.photoFileName;
                String newPhoto = oldPhoto;
                boolean photoChanged = false;
                if (photoUri != null) {
                    newPhoto = nl.paree.climbpro.data.route.AttemptPhotoStore
                            .savePickedPhoto(getApplication(), photoUri);
                    photoChanged = true;
                }
                target.photoFileName = newPhoto;

                boolean updateSucceeded;
                try {
                    updateSucceeded = attemptRepo.update(target);
                } catch (java.io.IOException writeFailure) {
                    // Write itself blew up (e.g. disk full) — same rollback as an explicit
                    // false return: the new photo never becomes referenced by anything.
                    if (photoChanged) {
                        nl.paree.climbpro.data.route.AttemptPhotoStore
                                .delete(getApplication(), newPhoto);
                    }
                    error.postValue("Opslaan mislukt: " + writeFailure.getMessage());
                    return;
                }

                if (updateSucceeded) {
                    if (photoChanged) {
                        nl.paree.climbpro.data.route.AttemptPhotoStore
                                .delete(getApplication(), oldPhoto);
                    }
                    saved.postValue(true);
                    loadClimb(routeId, climbIndex);
                } else {
                    if (photoChanged) {
                        // Roll back the just-written new photo; leave the old photo + old
                        // JSON record untouched so the attempt still has a working reference.
                        nl.paree.climbpro.data.route.AttemptPhotoStore
                                .delete(getApplication(), newPhoto);
                    }
                    error.postValue("Opslaan mislukt");
                }
            } catch (Exception e) {
                error.postValue("Opslaan mislukt: " + e.getMessage());
            }
        });
    }

    /** Recompute using the latest saved rider profile (call from Activity.onResume). */
    public void refreshEstimate() {
        StoredClimb c = lastClimb;
        if (c != null) {
            executor.execute(() -> computeEstimate(c));
        }
    }

    /** Issue #64: advice on how the latest attempt was paced; null when it can't be analysed. */
    private TrainingAdvice computeTrainingAdvice(StoredClimb c, String climbId,
                                                 List<StoredClimbAttempt> attempts) {
        if (c.segments == null || c.segments.isEmpty()) return null;
        int n = c.segments.size();
        StoredClimbAttempt latest = ClimbPacingAdvisor.latestAnalyzable(climbId, n, attempts);
        if (latest == null) return null;
        int[] dist = new int[n];
        double[] grad = new double[n];
        int[] surface = new int[n];
        for (int i = 0; i < n; i++) {
            StoredSegment s = c.segments.get(i);
            dist[i] = s.distance;
            grad[i] = s.gradient;
            surface[i] = s.surfaceType;
        }
        RiderProfile profile = riderRepo.load();
        double mass = profile.riderWeightKg > 0 && profile.bikeWeightKg > 0
                ? profile.totalMassKg() : ClimbPacingAdvisor.DEFAULT_MASS_KG;
        int[] best = SegmentPrCalculator.bestSplits(climbId, n, attempts);
        ClimbPacingAdvisor.Advice advice = ClimbPacingAdvisor.analyze(
                dist, grad, surface, latest.segSplitSec, best, mass);
        return advice == null ? null : new TrainingAdvice(latest.dateEpochSec, advice);
    }

    private void computeEstimate(StoredClimb c) {
        if (c.segments == null || c.segments.isEmpty()) {
            timeEstimate.postValue(null);
            return;
        }
        RiderProfile profile = riderRepo.load();

        // Preferred path: whole-route, fatigue-aware estimate.
        StoredRoute r = lastRoute;
        ClimbTimeEstimate estimate = null;
        if (r != null) {
            List<RouteTile> tiles = RouteEffortProfileBuilder.build(r);
            if (tiles != null) {
                estimate = RouteAwareClimbEstimator.estimate(tiles, lastClimbIndex, profile);
            }
        }

        // Fallback: fresh per-climb estimate when the route can't be profiled
        // (e.g. missing elevation/distance arrays) but the profile is usable.
        if (estimate == null && profile.isComplete()) {
            List<StoredSegment> segs = c.segments;
            int[] dist = new int[segs.size()];
            double[] grad = new double[segs.size()];
            int[] surface = new int[segs.size()];
            for (int i = 0; i < segs.size(); i++) {
                dist[i] = segs.get(i).distance;
                grad[i] = segs.get(i).gradient;
                surface[i] = segs.get(i).surfaceType;
            }
            estimate = ClimbTimeEstimator.estimate(dist, grad, surface, profile);
        }

        // Apply any per-segment manual overrides (issue #23) so the header total shown here
        // stays consistent with ClimbSegmentAdapter's per-row display, which already reads
        // StoredSegment#manualTargetSec directly.
        if (estimate != null) {
            int[] merged = nl.paree.climbpro.service.SegmentTargetOverrideMerger
                    .mergeClimb(c, estimate.segmentSeconds);
            if (merged != estimate.segmentSeconds) {
                int total = 0;
                for (int sec : merged) total += sec;
                estimate = new ClimbTimeEstimate(total, merged, estimate.assumedPowerWatts);
            }
        }

        timeEstimate.postValue(estimate);
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
