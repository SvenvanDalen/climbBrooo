package nl.paree.climbpro.ui.goals;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.data.goal.GoalEvent;
import nl.paree.climbpro.data.goal.GoalEventStore;
import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.domain.goal.GoalEventProgress;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Target event with countdown and training progress (issue #221), off the main thread. */
public final class GoalEventViewModel extends AndroidViewModel {

    /** Event plus progress; both null when no event is set. */
    public static final class State {
        public final GoalEvent event;
        public final GoalEventProgress.Result progress;

        State(GoalEvent event, GoalEventProgress.Result progress) {
            this.event = event;
            this.progress = progress;
        }
    }

    private final GoalEventStore store;
    private final RideRepository rideRepo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<State> state = new MutableLiveData<>();
    private final MutableLiveData<String> message = new MutableLiveData<>();

    public GoalEventViewModel(@NonNull Application app) {
        super(app);
        store = new GoalEventStore(app);
        rideRepo = new RideRepository(app);
    }

    public LiveData<State> state() { return state; }
    public LiveData<String> message() { return message; }

    public void load() {
        executor.execute(this::loadNow);
    }

    public void save(GoalEvent event) {
        executor.execute(() -> {
            try {
                store.save(event);
            } catch (Exception e) {
                message.postValue("Opslaan mislukt: " + e.getMessage());
            }
            loadNow();
        });
    }

    public void delete() {
        executor.execute(() -> {
            if (!store.delete()) message.postValue("Verwijderen mislukt");
            loadNow();
        });
    }

    private void loadNow() {
        GoalEvent e = store.load();
        if (e == null) {
            state.postValue(new State(null, null));
            return;
        }
        ZoneId zone = ZoneId.systemDefault();
        state.postValue(new State(e, GoalEventProgress.compute(e, rideRepo.loadAll(),
                LocalDate.now(zone), zone)));
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
