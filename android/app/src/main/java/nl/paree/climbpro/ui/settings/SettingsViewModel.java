package nl.paree.climbpro.ui.settings;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.preference.PreferenceManager;

import nl.paree.climbpro.data.rider.RiderProfileRepository;
import nl.paree.climbpro.data.strava.StravaAuthRepository;
import nl.paree.climbpro.domain.climb.CoordinateFuzzer;
import nl.paree.climbpro.domain.power.RiderProfile;
import nl.paree.climbpro.service.RouteSyncWorker;
import nl.paree.climbpro.service.SyncScheduler;

public final class SettingsViewModel extends AndroidViewModel {

    private final StravaAuthRepository authRepo;
    private final RiderProfileRepository riderRepo;
    private final MutableLiveData<Boolean> stravaSignedIn = new MutableLiveData<>();
    private final MutableLiveData<RiderProfile> riderProfile = new MutableLiveData<>();
    private final MutableLiveData<String>  syncMode       = new MutableLiveData<>();
    private final MutableLiveData<Integer> radiusKm       = new MutableLiveData<>();
    private final MutableLiveData<String>  syncStatus     = new MutableLiveData<>();
    private final MutableLiveData<Integer> privacyRadiusM = new MutableLiveData<>();

    public SettingsViewModel(@NonNull Application app) {
        super(app);
        authRepo = new StravaAuthRepository(app);
        riderRepo = new RiderProfileRepository(app);
        reload();
    }

    public LiveData<Boolean>     stravaSignedIn() { return stravaSignedIn; }
    public LiveData<String>      syncMode()       { return syncMode; }
    public LiveData<Integer>     radiusKm()       { return radiusKm; }
    public LiveData<String>      syncStatus()     { return syncStatus; }
    public LiveData<RiderProfile> riderProfile()  { return riderProfile; }
    public LiveData<Integer>     privacyRadiusM() { return privacyRadiusM; }

    public void reload() {
        stravaSignedIn.postValue(authRepo.isAuthorised());
        android.content.SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplication());
        syncMode.postValue(prefs.getString(RouteSyncWorker.PREF_MODE, RouteSyncWorker.MODE_ROUTE));
        int r = prefs.getInt(RouteSyncWorker.PREF_RADIUS_M, 30_000) / 1000;
        radiusKm.postValue(r);
        privacyRadiusM.postValue(CoordinateFuzzer.effectiveRadius(prefs.getInt(
                CoordinateFuzzer.PREF_PRIVACY_RADIUS_M, CoordinateFuzzer.DEFAULT_PRIVACY_RADIUS_M)));
        riderProfile.postValue(riderRepo.load());
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

    /** Privacy-zone radius (issue #92) for fuzzing home-climb start locations on export. */
    public void setPrivacyRadiusM(int requestedMeters) {
        int meters = CoordinateFuzzer.effectiveRadius(requestedMeters);
        PreferenceManager.getDefaultSharedPreferences(getApplication())
                .edit().putInt(CoordinateFuzzer.PREF_PRIVACY_RADIUS_M, meters).apply();
        privacyRadiusM.postValue(meters);
    }

    public void saveRiderProfile(int ftpWatts, double riderKg, double bikeKg, int rideIntensityPct) {
        RiderProfile profile = new RiderProfile(ftpWatts, riderKg, bikeKg, rideIntensityPct);
        riderRepo.save(profile);
        riderProfile.postValue(profile);
    }

    public void signOutStrava() {
        authRepo.signOut();
        stravaSignedIn.postValue(false);
    }

    public void syncNow() {
        syncStatus.postValue("Syncing…");
        SyncScheduler.triggerImmediateSync(getApplication());
    }
}
