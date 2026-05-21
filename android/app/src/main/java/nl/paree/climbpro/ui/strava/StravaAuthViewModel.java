package nl.paree.climbpro.ui.strava;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import net.openid.appauth.AuthState;
import net.openid.appauth.AuthorizationRequest;

import nl.paree.climbpro.data.strava.StravaAuthRepository;

public final class StravaAuthViewModel extends AndroidViewModel {

    private final StravaAuthRepository authRepo;
    private final MutableLiveData<Boolean> authorised  = new MutableLiveData<>(false);
    private final MutableLiveData<String>  error       = new MutableLiveData<>();

    public StravaAuthViewModel(@NonNull Application app) {
        super(app);
        authRepo   = new StravaAuthRepository(app);
        authorised.postValue(authRepo.isAuthorised());
    }

    public LiveData<Boolean> authorised() { return authorised; }
    public LiveData<String>  error()      { return error; }

    public AuthorizationRequest buildAuthRequest() {
        return authRepo.buildAuthRequest();
    }

    public void onAuthStateReceived(AuthState state) {
        authRepo.updateState(state);
        authorised.postValue(state.isAuthorized());
    }

    public void onAuthError(String message) {
        error.postValue(message);
    }
}
