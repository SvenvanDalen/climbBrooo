package nl.paree.climbpro.ui.settings;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.preference.PreferenceManager;

import nl.paree.climbpro.data.rider.RiderProfileRepository;
import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.strava.StravaAuthRepository;
import nl.paree.climbpro.domain.power.FtpEstimator;
import nl.paree.climbpro.domain.power.RiderProfile;
import nl.paree.climbpro.service.FtpEffortJoiner;
import nl.paree.climbpro.service.RouteSyncWorker;
import nl.paree.climbpro.service.SyncScheduler;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SettingsViewModel extends AndroidViewModel {

    private final StravaAuthRepository authRepo;
    private final RiderProfileRepository riderRepo;
    private final RouteRepository routeRepo;
    private final ClimbAttemptRepository attemptRepo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<Boolean> stravaSignedIn = new MutableLiveData<>();
    private final MutableLiveData<RiderProfile> riderProfile = new MutableLiveData<>();
    private final MutableLiveData<String>  syncMode       = new MutableLiveData<>();
    private final MutableLiveData<Integer> radiusKm       = new MutableLiveData<>();
    private final MutableLiveData<String>  syncStatus     = new MutableLiveData<>();
    private final MutableLiveData<Integer> suggestedFtpWatts = new MutableLiveData<>();

    /**
     * Cached {@link FtpEffortJoiner#build} result. Rebuilding this walks every stored
     * route's climbs, so we only pay that cost when the screen (re)opens ({@link #reload()})
     * and reuse it for every re-suggestion triggered by a profile save — the underlying
     * climb/route/attempt data doesn't change just because the FTP field changed.
     * Only ever touched from within {@link #executor} (single-threaded), so no extra
     * synchronization is needed.
     */
    private List<FtpEstimator.Effort> cachedEfforts;

    public SettingsViewModel(@NonNull Application app) {
        super(app);
        authRepo = new StravaAuthRepository(app);
        riderRepo = new RiderProfileRepository(app);
        routeRepo = new RouteRepository(app);
        attemptRepo = new ClimbAttemptRepository(app);
        reload();
    }

    public LiveData<Boolean>     stravaSignedIn() { return stravaSignedIn; }
    public LiveData<String>      syncMode()       { return syncMode; }
    public LiveData<Integer>     radiusKm()       { return radiusKm; }
    public LiveData<String>      syncStatus()     { return syncStatus; }
    public LiveData<RiderProfile> riderProfile()  { return riderProfile; }

    /**
     * A suggested FTP re-estimate (issue #20), or null when there isn't enough
     * duration-spread climb data yet or the suggestion isn't meaningfully different from
     * the stored value (see {@link FtpEstimator#MEANINGFUL_DELTA_WATTS}). Never applied
     * automatically — {@link #applySuggestedFtp()} only pushes it into the editable field.
     */
    public LiveData<Integer> suggestedFtpWatts() { return suggestedFtpWatts; }

    public void reload() {
        stravaSignedIn.postValue(authRepo.isAuthorised());
        android.content.SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplication());
        syncMode.postValue(prefs.getString(RouteSyncWorker.PREF_MODE, RouteSyncWorker.MODE_ROUTE));
        int r = prefs.getInt(RouteSyncWorker.PREF_RADIUS_M, 30_000) / 1000;
        radiusKm.postValue(r);
        RiderProfile profile = riderRepo.load();
        riderProfile.postValue(profile);
        // Screen (re)opened: the on-disk climb/route/attempt data may have changed
        // since we last built the effort cache, so rebuild it.
        refreshSuggestedFtp(profile, true);
    }

    private void refreshSuggestedFtp(RiderProfile profile, boolean rebuildEfforts) {
        suggestedFtpWatts.postValue(null);
        executor.execute(() -> {
            try {
                List<FtpEstimator.Effort> efforts = cachedEfforts;
                if (rebuildEfforts || efforts == null) {
                    List<StoredClimbAttempt> attempts = attemptRepo.loadAll();
                    efforts = FtpEffortJoiner.build(routeRepo, attempts);
                    cachedEfforts = efforts;
                }
                Integer suggestion = FtpEstimator.suggestFtpWatts(efforts, profile);
                if (suggestion != null
                        && FtpEstimator.isMeaningfullyDifferent(suggestion, profile.ftpWatts)) {
                    suggestedFtpWatts.postValue(suggestion);
                }
            } catch (Exception e) {
                // Best-effort suggestion — never blocks Settings on failure.
            }
        });
    }

    public void setSyncMode(String mode) {
        PreferenceManager.getDefaultSharedPreferences(getApplication())
                .edit().putString(RouteSyncWorker.PREF_MODE, mode).apply();
        syncMode.postValue(mode);
    }

    public void setRadiusKm(int km) {
        PreferenceManager.getDefaultSharedPreferences(getApplication())
                .edit().putInt(RouteSyncWorker.PREF_RADIUS_M, km * 1000).apply();
        radiusKm.postValue(km);
    }

    public void saveRiderProfile(int ftpWatts, double riderKg, double bikeKg, int rideIntensityPct) {
        RiderProfile profile = new RiderProfile(ftpWatts, riderKg, bikeKg, rideIntensityPct);
        riderRepo.save(profile);
        riderProfile.postValue(profile);
        // Only ftpWatts (potentially) changed — the climb/route/attempt data behind the
        // effort cache did not, so reuse it instead of rescanning the whole catalog.
        refreshSuggestedFtp(profile, false);
    }

    /**
     * Applies the currently shown suggestion to the rider profile, combined with the
     * given weight/bike/intensity values. Callers MUST pass the values currently shown
     * on screen (not necessarily the last-saved profile) so that tapping "apply suggested
     * FTP" behaves like a normal "save profile" tap with this FTP value substituted in —
     * it must never silently discard unsaved edits to the other fields. Only ever called
     * from an explicit user tap (issue #20) — never invoked on the caller's behalf.
     */
    public void applySuggestedFtp(double riderKg, double bikeKg, int rideIntensityPct) {
        Integer suggestion = suggestedFtpWatts.getValue();
        if (suggestion == null) return;
        saveRiderProfile(suggestion, riderKg, bikeKg, rideIntensityPct);
    }

    public void signOutStrava() {
        authRepo.signOut();
        stravaSignedIn.postValue(false);
    }

    public void syncNow() {
        syncStatus.postValue("Syncing…");
        SyncScheduler.triggerImmediateSync(getApplication());
    }

    @Override
    protected void onCleared() {
        executor.shutdown();
    }
}
