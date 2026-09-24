package nl.paree.climbpro.ui.climbs;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.data.rider.RiderProfileRepository;
import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.domain.climb.ClimbGpxWriter;
import nl.paree.climbpro.domain.climb.ClimbIdentity;
import nl.paree.climbpro.domain.climb.LogbookCalculator;
import nl.paree.climbpro.domain.climb.LogbookCalculator.HistoryRow;
import nl.paree.climbpro.domain.climb.SeasonalComparisonCalculator;
import nl.paree.climbpro.domain.climb.SegmentPrCalculator;
import nl.paree.climbpro.domain.power.ClimbTimeEstimate;
import nl.paree.climbpro.domain.power.ClimbTimeEstimator;
import nl.paree.climbpro.domain.power.RiderProfile;
import nl.paree.climbpro.domain.power.RouteAwareClimbEstimator;
import nl.paree.climbpro.domain.power.RouteTile;
import nl.paree.climbpro.service.RouteEffortProfileBuilder;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ClimbDetailViewModel extends AndroidViewModel {

    private static final String TAG = "ClimbDetailVM";

    private final RouteRepository routeRepo;
    private final RiderProfileRepository riderRepo;
    private final ClimbAttemptRepository attemptRepo;
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

    private volatile StoredClimb lastClimb;
    private volatile StoredRoute lastRoute;
    private volatile int lastClimbIndex;

    public ClimbDetailViewModel(@NonNull Application app) {
        super(app);
        routeRepo = new RouteRepository(app);
        riderRepo = new RiderProfileRepository(app);
        attemptRepo = new ClimbAttemptRepository(app);
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
                } else {
                    error.postValue("Climb not found");
                }
            } catch (Exception e) {
                error.postValue("Load failed: " + e.getMessage());
            }
        });
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
     * Builds a GPX 1.1 export of the currently loaded climb (issue #79) — its geometry as a
     * track, plus waypoints at every segment boundary and, when PR data exists, at the
     * climb's personal-record splits — and writes it to the app cache. Posts the resulting
     * {@link File} to {@link #gpxExportFile()} for the Activity to hand off to the share
     * sheet; PR lookup and file I/O both happen off the main thread.
     */
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

                String gpx = ClimbGpxWriter.toGpx(r, c, lastClimbIndex, bestSplitSec, bestElapsedSec);
                File file = ClimbGpxExportHandoff.writeGpxFile(getApplication(), gpx);
                gpxExportFile.postValue(file);
            } catch (Exception e) {
                error.postValue("GPX-export mislukt: " + e.getMessage());
            }
        });
    }

    /**
     * Attaches/updates a note and/or photo on one existing attempt (issue #46). {@code
     * photoUri}, when non-null, is copied into {@code getFilesDir()/attempt_photos/} via
     * {@link nl.paree.climbpro.data.route.AttemptPhotoStore}; pass null to leave the attempt's
     * current photo untouched, and an empty/blank {@code note} to clear it. Identity is
     * (climbId derived from the loaded climb, activityId, passIndex) — the same key {@link
     * ClimbAttemptRepository#update} matches on. Reloads the climb afterward so the history
     * list picks up the change.
     */
    public void saveAttemptNote(String routeId, int climbIndex, long activityId, int passIndex,
                                 String note, android.net.Uri photoUri) {
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
