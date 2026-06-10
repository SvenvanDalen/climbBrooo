package nl.paree.climbpro.ui.climbs;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.data.rider.RiderProfileRepository;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.domain.power.ClimbTimeEstimate;
import nl.paree.climbpro.domain.power.ClimbTimeEstimator;
import nl.paree.climbpro.domain.power.RiderProfile;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ClimbDetailViewModel extends AndroidViewModel {

    private final RouteRepository routeRepo;
    private final RiderProfileRepository riderRepo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<StoredClimb>       climb        = new MutableLiveData<>();
    private final MutableLiveData<StoredRoute>       route        = new MutableLiveData<>();
    private final MutableLiveData<String>            error        = new MutableLiveData<>();
    private final MutableLiveData<Boolean>           saved        = new MutableLiveData<>(false);
    private final MutableLiveData<ClimbTimeEstimate> timeEstimate = new MutableLiveData<>();

    private volatile StoredClimb lastClimb;

    public ClimbDetailViewModel(@NonNull Application app) {
        super(app);
        routeRepo = new RouteRepository(app);
        riderRepo = new RiderProfileRepository(app);
    }

    public LiveData<StoredClimb>       climb()        { return climb; }
    public LiveData<StoredRoute>       route()        { return route; }
    public LiveData<String>            error()        { return error; }
    public LiveData<Boolean>           saved()        { return saved; }
    public LiveData<ClimbTimeEstimate> timeEstimate() { return timeEstimate; }

    public void loadClimb(String routeId, int climbIndex) {
        executor.execute(() -> {
            try {
                StoredRoute r = routeRepo.loadRoute(routeId);
                route.postValue(r);
                if (r.climbs != null && climbIndex < r.climbs.size()) {
                    StoredClimb loaded = r.climbs.get(climbIndex);
                    lastClimb = loaded;
                    climb.postValue(loaded);
                    computeEstimate(loaded);
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
        List<StoredSegment> segs = c.segments;
        int[] dist = new int[segs.size()];
        double[] grad = new double[segs.size()];
        int[] surface = new int[segs.size()];
        for (int i = 0; i < segs.size(); i++) {
            dist[i] = segs.get(i).distance;
            grad[i] = segs.get(i).gradient;
            surface[i] = segs.get(i).surfaceType;
        }
        RiderProfile profile = riderRepo.load();
        timeEstimate.postValue(ClimbTimeEstimator.estimate(dist, grad, surface, profile));
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
