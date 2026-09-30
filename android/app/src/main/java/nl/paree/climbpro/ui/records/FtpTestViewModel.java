package nl.paree.climbpro.ui.records;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.ride.RideStreamStatsRepository;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.ride.StoredRideStreamStats;
import nl.paree.climbpro.data.rider.RiderProfileRepository;
import nl.paree.climbpro.domain.power.FtpTestPlan;
import nl.paree.climbpro.domain.power.FtpTestResultDetector;
import nl.paree.climbpro.domain.ride.RideStreamAnalyzer;
import nl.paree.climbpro.ui.climbs.ClimbWorkoutExportHandoff;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * FTP-test screen (issue #181): the current FTP, the export of the test workout, and the test
 * result detected in the synced Strava rides. Updating the FTP only happens on
 * {@link #applyResult}, after the rider confirmed.
 */
public final class FtpTestViewModel extends AndroidViewModel {

    public static final class State {
        public final int currentFtpWatts;
        /** Epoch seconds of the last export; 0 if never. */
        public final long exportedAtEpochSec;
        /** Detected test, or null. */
        public final FtpTestResultDetector.Result result;
        /** Archived rides whose streams haven't been analyzed yet. */
        public final int ridesAwaitingAnalysis;

        State(int currentFtpWatts, long exportedAtEpochSec, FtpTestResultDetector.Result result,
              int ridesAwaitingAnalysis) {
            this.currentFtpWatts = currentFtpWatts;
            this.exportedAtEpochSec = exportedAtEpochSec;
            this.result = result;
            this.ridesAwaitingAnalysis = ridesAwaitingAnalysis;
        }
    }

    /** One-shot outcome of an export: the file to share, or the error message. */
    public static final class ExportResult {
        public final File file;
        public final String error;

        ExportResult(File file, String error) {
            this.file = file;
            this.error = error;
        }
    }

    private final RideRepository rideRepo;
    private final RideStreamStatsRepository statsRepo;
    private final RiderProfileRepository profileRepo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<State> state = new MutableLiveData<>();
    private final MutableLiveData<ExportResult> export = new MutableLiveData<>();

    public FtpTestViewModel(@NonNull Application app) {
        super(app);
        rideRepo = new RideRepository(app);
        statsRepo = new RideStreamStatsRepository(app);
        profileRepo = new RiderProfileRepository(app);
    }

    public LiveData<State> state() { return state; }

    public LiveData<ExportResult> export() { return export; }

    public void load() {
        executor.execute(() -> {
            List<StoredRide> rides = rideRepo.loadAll();
            Map<Long, StoredRideStreamStats> byId = statsRepo.loadById();
            int awaiting = 0;
            for (StoredRide r : rides) {
                StoredRideStreamStats s = byId.get(r.activityId);
                if (s == null || s.version < RideStreamAnalyzer.VERSION) awaiting++;
            }
            int ftp = profileRepo.load().ftpWatts;
            long exportedAt = profileRepo.loadFtpTestExportedAt();
            FtpTestResultDetector.Result result = FtpTestResultDetector.detect(rides, byId,
                    exportedAt, ftp, profileRepo.loadFtpTestHandledActivityId(),
                    System.currentTimeMillis() / 1000L);
            state.postValue(new State(ftp, exportedAt, result, awaiting));
        });
    }

    /** Writes the .zwo to the share cache and remembers the export time for detection. */
    public void exportWorkout() {
        executor.execute(() -> {
            try {
                File file = ClimbWorkoutExportHandoff.writeFile(getApplication(),
                        FtpTestPlan.toZwo(), FtpTestPlan.FILE_NAME);
                profileRepo.saveFtpTestExportedAt(System.currentTimeMillis() / 1000L);
                export.postValue(new ExportResult(file, null));
            } catch (Exception e) {
                export.postValue(new ExportResult(null, e.getMessage()));
            }
        });
        load();
    }

    public void consumeExport() {
        export.setValue(null);
    }

    /** Saves the test's FTP in the rider profile; called only after the rider confirmed. */
    public void applyResult(FtpTestResultDetector.Result result) {
        if (result == null || result.ftpWatts <= 0) return;
        executor.execute(() -> {
            profileRepo.saveFtp(result.ftpWatts);
            profileRepo.saveFtpTestHandledActivityId(result.ride.activityId);
        });
        load();
    }

    /** Hides this test result without changing the FTP. */
    public void dismissResult(FtpTestResultDetector.Result result) {
        if (result == null) return;
        executor.execute(() ->
                profileRepo.saveFtpTestHandledActivityId(result.ride.activityId));
        load();
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
