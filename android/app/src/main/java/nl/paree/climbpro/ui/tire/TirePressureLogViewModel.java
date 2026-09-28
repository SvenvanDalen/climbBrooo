package nl.paree.climbpro.ui.tire;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.data.tire.TirePressureLogEntry;
import nl.paree.climbpro.data.tire.TirePressureLogRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tire-pressure log screen (issue #155): entries newest first plus reminder status. */
public final class TirePressureLogViewModel extends AndroidViewModel {

    private final TirePressureLogRepository repo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<TirePressureStatusLoader.Snapshot> snapshot = new MutableLiveData<>();
    private final MutableLiveData<List<TirePressureLogEntry>> entries = new MutableLiveData<>();
    private final MutableLiveData<String> message = new MutableLiveData<>();

    public TirePressureLogViewModel(@NonNull Application app) {
        super(app);
        repo = new TirePressureLogRepository(app);
    }

    public LiveData<TirePressureStatusLoader.Snapshot> snapshot() { return snapshot; }
    public LiveData<List<TirePressureLogEntry>> entries() { return entries; }
    public LiveData<String> message() { return message; }

    /** Latest loaded snapshot, or null before the first load completes. */
    public TirePressureStatusLoader.Snapshot current() { return snapshot.getValue(); }

    public void load() {
        executor.execute(this::loadNow);
    }

    public void addEntry(long timestampEpochSec, double frontBar, double rearBar, String note) {
        executor.execute(() -> {
            try {
                repo.addEntry(timestampEpochSec, frontBar, rearBar, note);
                message.postValue("Controle gelogd");
            } catch (Exception e) {
                message.postValue("Opslaan mislukt: " + e.getMessage());
            }
            loadNow();
        });
    }

    public void deleteEntry(String id) {
        executor.execute(() -> {
            try {
                repo.deleteEntry(id);
            } catch (Exception e) {
                message.postValue("Verwijderen mislukt: " + e.getMessage());
            }
            loadNow();
        });
    }

    public void saveReminderSettings(int days, int km) {
        executor.execute(() -> {
            try {
                repo.saveReminderSettings(days, km);
            } catch (Exception e) {
                message.postValue("Opslaan mislukt: " + e.getMessage());
            }
            loadNow();
        });
    }

    private void loadNow() {
        TirePressureStatusLoader.Snapshot s =
                TirePressureStatusLoader.load(getApplication(), System.currentTimeMillis() / 1000L);
        List<TirePressureLogEntry> sorted = new ArrayList<>(s.log.entries);
        sorted.sort((a, b) -> Long.compare(b.timestampEpochSec, a.timestampEpochSec));
        entries.postValue(sorted);
        snapshot.postValue(s);
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
