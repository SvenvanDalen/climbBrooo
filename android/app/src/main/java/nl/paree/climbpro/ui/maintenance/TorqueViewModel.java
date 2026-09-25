package nl.paree.climbpro.ui.maintenance;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.data.maintenance.TorqueValue;
import nl.paree.climbpro.data.maintenance.TorqueValueRepository;
import nl.paree.climbpro.domain.maintenance.TorqueReference;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** "Aanhaalmomenten" screen (issue #237): the rider's own torque values, sorted for display. */
public final class TorqueViewModel extends AndroidViewModel {

    private final TorqueValueRepository repo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<List<TorqueValue>> values = new MutableLiveData<>();
    private final MutableLiveData<String> message = new MutableLiveData<>();

    public TorqueViewModel(@NonNull Application app) {
        super(app);
        repo = new TorqueValueRepository(app);
    }

    public LiveData<List<TorqueValue>> values() { return values; }
    public LiveData<String> message() { return message; }

    public void load() {
        executor.execute(this::loadNow);
    }

    /** Creates ({@code id == null}) or edits a value; {@code nm} must come from parseNm. */
    public void save(String id, String bike, String part, double nm, String note) {
        executor.execute(() -> {
            try {
                repo.upsert(id, bike, part, nm, note);
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
        values.postValue(TorqueReference.sorted(repo.load().values));
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
