package nl.paree.climbpro.ui.hydration;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.hydration.SweatLossEntry;
import nl.paree.climbpro.data.hydration.SweatLossStore;
import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.rider.RiderProfileRepository;
import nl.paree.climbpro.domain.hydration.SweatLossCalculator;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Zweetverlies-schatter (issue #186): measurements newest first with their evaluation, the
 * personal average + drinking advice, and recent rides for the "which ride?" picker.
 */
public final class SweatLossViewModel extends AndroidViewModel {

    /** How many recent archived rides the picker offers. */
    static final int RIDE_PICKER_SIZE = 20;

    public static final class Row {
        public final SweatLossEntry entry;
        public final SweatLossCalculator.Result result;

        Row(SweatLossEntry entry, SweatLossCalculator.Result result) {
            this.entry = entry;
            this.result = result;
        }
    }

    public static final class Snapshot {
        /** Newest first. */
        public final List<Row> rows;
        /** Null when there are no measurements yet. */
        public final SweatLossCalculator.Summary summary;
        public final List<StoredRide> recentRides;
        /** Rider-profile body weight, 0 when not set; pre-fills "weight before". */
        public final double profileWeightKg;

        Snapshot(List<Row> rows, SweatLossCalculator.Summary summary,
                 List<StoredRide> recentRides, double profileWeightKg) {
            this.rows = rows;
            this.summary = summary;
            this.recentRides = recentRides;
            this.profileWeightKg = profileWeightKg;
        }
    }

    /** A one-shot message: a string resource with optional format args. */
    public static final class Message {
        public final int resId;
        public final Object[] args;

        Message(int resId, Object... args) {
            this.resId = resId;
            this.args = args;
        }
    }

    private final SweatLossStore store;
    private final RideRepository rides;
    private final RiderProfileRepository profile;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<Snapshot> snapshot = new MutableLiveData<>();
    private final MutableLiveData<Message> message = new MutableLiveData<>();

    public SweatLossViewModel(@NonNull Application app) {
        super(app);
        store = new SweatLossStore(new File(app.getFilesDir(), SweatLossStore.FILE_NAME));
        rides = new RideRepository(app);
        profile = new RiderProfileRepository(app);
    }

    public LiveData<Snapshot> snapshot() { return snapshot; }
    public LiveData<Message> message() { return message; }

    /** Latest loaded snapshot, or null before the first load completes. */
    public Snapshot current() { return snapshot.getValue(); }

    public void load() {
        executor.execute(this::loadNow);
    }

    /** Values must already pass {@link SweatLossCalculator#validate}. */
    public void add(long rideActivityId, long timestampEpochSec, double weightBeforeKg,
                    double weightAfterKg, int drunkMl, int durationMin, String note) {
        executor.execute(() -> {
            try {
                store.add(rideActivityId, timestampEpochSec, weightBeforeKg, weightAfterKg,
                        drunkMl, durationMin, note);
                SweatLossCalculator.Result r = SweatLossCalculator.calculate(
                        weightBeforeKg, weightAfterKg, drunkMl, durationMin);
                message.postValue(new Message(R.string.sweat_saved, r.sweatRateLPerH));
            } catch (Exception e) {
                message.postValue(new Message(R.string.sweat_save_failed, e.getMessage()));
            }
            loadNow();
        });
    }

    public void delete(String id) {
        executor.execute(() -> {
            try {
                store.delete(id);
            } catch (Exception e) {
                message.postValue(new Message(R.string.sweat_delete_failed, e.getMessage()));
            }
            loadNow();
        });
    }

    private void loadNow() {
        List<Row> rows = new ArrayList<>();
        List<SweatLossCalculator.Result> results = new ArrayList<>();
        for (SweatLossEntry e : store.loadAll()) {
            SweatLossCalculator.Result r = SweatLossCalculator.calculate(
                    e.weightBeforeKg, e.weightAfterKg, e.drunkMl, e.durationMin);
            rows.add(new Row(e, r));
            results.add(r);
        }

        List<StoredRide> recent = new ArrayList<>();
        for (StoredRide r : rides.loadAll()) if (r != null && r.startEpochSec > 0) recent.add(r);
        recent.sort((a, b) -> Long.compare(b.startEpochSec, a.startEpochSec));
        if (recent.size() > RIDE_PICKER_SIZE) {
            recent = new ArrayList<>(recent.subList(0, RIDE_PICKER_SIZE));
        }

        snapshot.postValue(new Snapshot(Collections.unmodifiableList(rows),
                SweatLossCalculator.summarize(results), Collections.unmodifiableList(recent),
                profile.load().riderWeightKg));
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
