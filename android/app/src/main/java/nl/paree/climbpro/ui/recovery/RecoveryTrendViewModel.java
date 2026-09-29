package nl.paree.climbpro.ui.recovery;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.data.recovery.RecoveryCheckRepository;
import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.domain.recovery.RecoveryTrendAnalyzer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Recovery trend screen (issue #183): post-ride checks joined with the ride archive. */
public final class RecoveryTrendViewModel extends AndroidViewModel {

    private final RecoveryCheckRepository checks;
    private final RideRepository rides;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<RecoveryTrendAnalyzer.Trend> trend = new MutableLiveData<>();

    public RecoveryTrendViewModel(@NonNull Application app) {
        super(app);
        checks = new RecoveryCheckRepository(app);
        rides = new RideRepository(app);
    }

    public LiveData<RecoveryTrendAnalyzer.Trend> trend() { return trend; }

    public void load() {
        executor.execute(() ->
                trend.postValue(RecoveryTrendAnalyzer.analyze(checks.loadAll(), rides.loadAll())));
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
