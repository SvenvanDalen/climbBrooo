package nl.paree.climbpro.ui.goals;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.data.ride.MonthlyChallengeRepository;
import nl.paree.climbpro.data.ride.MonthlyChallengeRepository.Challenge;
import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.domain.ride.MonthlyChallengeCalculator;
import nl.paree.climbpro.domain.ride.MonthlyChallengeCalculator.Progress;
import nl.paree.climbpro.domain.ride.MonthlyChallengeCalculator.Type;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Issue #192: the current month's challenge and its progress, plus a suggested target per
 * challenge type for the picker. Phone-only — see {@link MonthlyChallengeCalculator}.
 */
public final class MonthlyChallengeViewModel extends AndroidViewModel {

    /** Everything the screen renders; {@link #progress} is null without a challenge. */
    public static final class State {
        public final YearMonth month;
        public final Progress progress;
        public final Map<Type, Integer> suggestions;

        State(YearMonth month, Progress progress, Map<Type, Integer> suggestions) {
            this.month = month;
            this.progress = progress;
            this.suggestions = suggestions;
        }
    }

    private final MonthlyChallengeRepository challengeRepo;
    private final RideRepository rideRepo;
    private final ClimbAttemptRepository attemptRepo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<State> state = new MutableLiveData<>();

    public MonthlyChallengeViewModel(@NonNull Application app) {
        super(app);
        challengeRepo = new MonthlyChallengeRepository(app);
        rideRepo = new RideRepository(app);
        attemptRepo = new ClimbAttemptRepository(app);
    }

    public LiveData<State> state() { return state; }

    public void setChallenge(Type type, int target) {
        executor.execute(() -> {
            challengeRepo.save(YearMonth.now(ZoneId.systemDefault()), type, target);
            loadNow();
        });
    }

    public void clearChallenge() {
        executor.execute(() -> {
            challengeRepo.clear();
            loadNow();
        });
    }

    public void load() {
        executor.execute(this::loadNow);
    }

    private void loadNow() {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        YearMonth month = YearMonth.from(today);
        List<StoredRide> rides = rideRepo.loadAll();
        List<StoredClimbAttempt> attempts = attemptRepo.loadAll();

        Map<Type, Integer> suggestions = new EnumMap<>(Type.class);
        for (Type t : Type.values()) {
            suggestions.put(t, MonthlyChallengeCalculator.suggestTarget(
                    t, rides, attempts, month, zone));
        }
        Challenge c = challengeRepo.load(month);
        Progress progress = c == null ? null : MonthlyChallengeCalculator.progress(
                c.type, c.target, rides, attempts, month, today, zone);
        state.postValue(new State(month, progress, suggestions));
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
