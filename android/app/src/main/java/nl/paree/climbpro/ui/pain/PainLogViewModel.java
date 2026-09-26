package nl.paree.climbpro.ui.pain;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.data.pain.PainLogEntry;
import nl.paree.climbpro.data.pain.PainLogRepository;
import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.domain.pain.PainPatternAnalyzer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Pain log screen (issue #232): entries newest first, the pattern summary and recent rides. */
public final class PainLogViewModel extends AndroidViewModel {

    /** How many recent archived rides the "which ride?" picker offers. */
    static final int RIDE_PICKER_SIZE = 20;

    public static final class Snapshot {
        public final List<PainLogEntry> entries;
        public final String summary;
        /** Recent archived rides, newest first, for the picker. */
        public final List<StoredRide> recentRides;

        Snapshot(List<PainLogEntry> entries, String summary, List<StoredRide> recentRides) {
            this.entries = entries;
            this.summary = summary;
            this.recentRides = recentRides;
        }
    }

    private final PainLogRepository repo;
    private final RideRepository rides;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<Snapshot> snapshot = new MutableLiveData<>();
    private final MutableLiveData<String> message = new MutableLiveData<>();

    public PainLogViewModel(@NonNull Application app) {
        super(app);
        repo = new PainLogRepository(app);
        rides = new RideRepository(app);
    }

    public LiveData<Snapshot> snapshot() { return snapshot; }
    public LiveData<String> message() { return message; }

    /** Latest loaded snapshot, or null before the first load completes. */
    public Snapshot current() { return snapshot.getValue(); }

    public void load() {
        executor.execute(this::loadNow);
    }

    public void add(long rideActivityId, long timestampEpochSec, List<String> areas,
                    int severity, String bike, String setup, String note) {
        executor.execute(() -> {
            try {
                repo.add(rideActivityId, timestampEpochSec, areas, severity, bike, setup, note);
                message.postValue("Klacht gelogd");
            } catch (Exception e) {
                message.postValue("Opslaan mislukt: " + e.getMessage());
            }
            loadNow();
        });
    }

    public void delete(String id) {
        executor.execute(() -> {
            try {
                repo.delete(id);
            } catch (Exception e) {
                message.postValue("Verwijderen mislukt: " + e.getMessage());
            }
            loadNow();
        });
    }

    private void loadNow() {
        List<PainLogEntry> entries = repo.loadAll();
        List<StoredRide> allRides = rides.loadAll();
        String summary = PainPatternAnalyzer.summaryText(
                PainPatternAnalyzer.analyze(entries, allRides));

        List<PainLogEntry> sorted = new ArrayList<>(entries);
        sorted.sort((a, b) -> Long.compare(b.timestampEpochSec, a.timestampEpochSec));

        List<StoredRide> recent = new ArrayList<>();
        for (StoredRide r : allRides) if (r != null && r.startEpochSec > 0) recent.add(r);
        recent.sort((a, b) -> Long.compare(b.startEpochSec, a.startEpochSec));
        if (recent.size() > RIDE_PICKER_SIZE) recent = new ArrayList<>(recent.subList(0, RIDE_PICKER_SIZE));

        snapshot.postValue(new Snapshot(sorted, summary, Collections.unmodifiableList(recent)));
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
