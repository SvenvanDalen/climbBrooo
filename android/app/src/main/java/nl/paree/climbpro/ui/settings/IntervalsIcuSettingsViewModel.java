package nl.paree.climbpro.ui.settings;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.intervals.IntervalsIcuRepository;
import nl.paree.climbpro.domain.export.IntervalsIcuExport;

/**
 * intervals.icu link settings (issue #78): save, test and clear the personal API key and
 * athlete id. The stored key is never shown again; an empty key field on save keeps it.
 */
public final class IntervalsIcuSettingsViewModel extends AndroidViewModel {

    /** Current link state for the form. */
    public static final class State {
        public final boolean configured;
        public final String athleteId;

        State(boolean configured, String athleteId) {
            this.configured = configured;
            this.athleteId = athleteId;
        }
    }

    private final IntervalsIcuRepository repo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<State> state = new MutableLiveData<>();
    private final MutableLiveData<String> status = new MutableLiveData<>();

    public IntervalsIcuSettingsViewModel(@NonNull Application app) {
        super(app);
        repo = new IntervalsIcuRepository(app);
    }

    public LiveData<State> state() { return state; }
    public LiveData<String> status() { return status; }

    public void load() {
        executor.execute(() -> state.postValue(new State(repo.isConfigured(), repo.athleteId())));
    }

    public void save(String keyInput, String athleteInput) {
        executor.execute(() -> {
            String key = resolveKey(keyInput);
            String athlete = IntervalsIcuExport.normalizeAthleteId(athleteInput);
            if (key == null) {
                status.postValue(str(R.string.intervals_status_key_invalid));
                return;
            }
            if (athlete == null) {
                status.postValue(str(R.string.intervals_status_athlete_invalid));
                return;
            }
            try {
                repo.save(key, athlete);
                status.postValue(str(R.string.intervals_status_saved));
            } catch (Exception e) {
                status.postValue(str(R.string.intervals_status_failed, message(e)));
            }
            state.postValue(new State(true, athlete));
        });
    }

    /** GET /athlete/{id} with the entered (or stored) values; nothing is saved. */
    public void testConnection(String keyInput, String athleteInput) {
        executor.execute(() -> {
            String key = resolveKey(keyInput);
            String athlete = IntervalsIcuExport.normalizeAthleteId(athleteInput);
            if (key == null) {
                status.postValue(str(R.string.intervals_status_key_invalid));
                return;
            }
            if (athlete == null) {
                status.postValue(str(R.string.intervals_status_athlete_invalid));
                return;
            }
            status.postValue(str(R.string.intervals_status_testing));
            try {
                String who = repo.testConnection(key, athlete);
                status.postValue(str(R.string.intervals_status_ok, who != null ? who : athlete));
            } catch (Exception e) {
                status.postValue(str(R.string.intervals_status_failed, message(e)));
            }
        });
    }

    public void clear() {
        executor.execute(() -> {
            repo.clear();
            status.postValue(str(R.string.intervals_status_cleared));
            state.postValue(new State(false, IntervalsIcuExport.OWN_ATHLETE_ID));
        });
    }

    /** Entered key, or the stored one when the field is left empty; null when unusable. */
    private String resolveKey(String keyInput) {
        if (keyInput != null && !keyInput.trim().isEmpty()) {
            return IntervalsIcuExport.isValidApiKey(keyInput) ? keyInput.trim() : null;
        }
        return repo.apiKey();
    }

    private String str(int id, Object... args) {
        return getApplication().getString(id, args);
    }

    private static String message(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
