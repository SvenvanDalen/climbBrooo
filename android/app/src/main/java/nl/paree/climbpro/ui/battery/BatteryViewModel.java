package nl.paree.climbpro.ui.battery;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.data.battery.BatteryDevice;
import nl.paree.climbpro.data.battery.BatteryRepository;
import nl.paree.climbpro.domain.battery.BatteryStatusCalculator;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** "Accu's" screen (issue #238): devices most urgent first. */
public final class BatteryViewModel extends AndroidViewModel {

    private final BatteryRepository repo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<List<BatteryDevice>> devices = new MutableLiveData<>();
    private final MutableLiveData<String> message = new MutableLiveData<>();

    public BatteryViewModel(@NonNull Application app) {
        super(app);
        repo = new BatteryRepository(app);
    }

    public LiveData<List<BatteryDevice>> devices() { return devices; }
    public LiveData<String> message() { return message; }

    public void load() {
        executor.execute(this::loadNow);
    }

    public void saveDevice(String id, String name, String kind, int intervalDays,
                           long lastChargedEpochSec) {
        executor.execute(() -> {
            try {
                repo.upsertDevice(id, name, kind, intervalDays, lastChargedEpochSec);
            } catch (Exception e) {
                message.postValue("Opslaan mislukt: " + e.getMessage());
            }
            loadNow();
        });
    }

    public void markCharged(String id) {
        executor.execute(() -> {
            try {
                repo.markCharged(id, System.currentTimeMillis() / 1000L);
                message.postValue("Opgeladen gelogd");
            } catch (Exception e) {
                message.postValue("Opslaan mislukt: " + e.getMessage());
            }
            loadNow();
        });
    }

    public void deleteDevice(String id) {
        executor.execute(() -> {
            try {
                repo.deleteDevice(id);
            } catch (Exception e) {
                message.postValue("Verwijderen mislukt: " + e.getMessage());
            }
            loadNow();
        });
    }

    private void loadNow() {
        devices.postValue(BatteryStatusCalculator.sortByUrgency(
                repo.load().devices, System.currentTimeMillis() / 1000L));
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
