package nl.paree.climbpro.data.strava;

import android.content.Context;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import net.openid.appauth.AuthState;
import net.openid.appauth.AuthorizationRequest;
import net.openid.appauth.AuthorizationService;
import net.openid.appauth.AuthorizationServiceConfiguration;
import net.openid.appauth.ClientSecretPost;
import net.openid.appauth.ResponseTypeValues;

import org.json.JSONException;

import java.io.IOException;

import nl.paree.climbpro.BuildConfig;

/**
 * Manages Strava OAuth 2.0 state using AppAuth.
 * The AuthState (including refresh token) is persisted in EncryptedSharedPreferences.
 */
public final class StravaAuthRepository {

    private static final String TAG      = "StravaAuth";
    private static final String PREF_KEY = "auth_state";
    private static final String PREFS    = "strava_auth";

    private static final String AUTH_ENDPOINT  = "https://www.strava.com/oauth/authorize";
    private static final String TOKEN_ENDPOINT = "https://www.strava.com/oauth/token";

    private final Context context;
    private AuthState authState;

    public StravaAuthRepository(Context context) {
        this.context   = context.getApplicationContext();
        this.authState = loadState();
    }

    public boolean isAuthorised() {
        return authState != null && authState.isAuthorized();
    }

    public AuthorizationRequest buildAuthRequest() {
        AuthorizationServiceConfiguration config = new AuthorizationServiceConfiguration(
                android.net.Uri.parse(AUTH_ENDPOINT),
                android.net.Uri.parse(TOKEN_ENDPOINT));
        return new AuthorizationRequest.Builder(
                config,
                BuildConfig.STRAVA_CLIENT_ID,
                ResponseTypeValues.CODE,
                android.net.Uri.parse("climbpro://oauth/callback"))
                .setScope("read,activity:read_all,profile:read_all")
                .build();
    }

    public void updateState(AuthState newState) {
        this.authState = newState;
        persistState();
    }

    /**
     * Returns a valid access token, refreshing if needed. Blocks — call on a background thread.
     *
     * @throws IOException if the token refresh fails
     */
    public String getAccessToken() throws IOException {
        if (authState == null || !authState.isAuthorized()) {
            throw new IOException("Not authorised — user must sign in");
        }
        final String[] result = new String[1];
        final Exception[] error = new Exception[1];
        final Object lock = new Object();

        AuthorizationService service = new AuthorizationService(context);
        ClientSecretPost clientAuth = new ClientSecretPost(BuildConfig.STRAVA_CLIENT_SECRET);
        authState.performActionWithFreshTokens(service, clientAuth, (accessToken, idToken, ex) -> {
            synchronized (lock) {
                if (ex != null) {
                    error[0] = ex;
                } else {
                    result[0] = accessToken;
                    persistState();
                }
                lock.notifyAll();
            }
        });
        synchronized (lock) {
            while (result[0] == null && error[0] == null) {
                try { lock.wait(10_000); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Token refresh interrupted");
                }
            }
        }
        service.dispose();
        if (error[0] != null) throw new IOException("Token refresh failed", error[0]);
        return result[0];
    }

    public void signOut() {
        authState = null;
        persistState();
    }

    // -------------------------------------------------------------------------

    private AuthState loadState() {
        try {
            String json = getPrefs().getString(PREF_KEY, null);
            if (json == null) return null;
            return AuthState.jsonDeserialize(json);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load auth state", e);
            return null;
        }
    }

    private void persistState() {
        try {
            String json = authState != null ? authState.jsonSerializeString() : "";
            getPrefs().edit().putString(PREF_KEY, json).apply();
        } catch (Exception e) {
            Log.e(TAG, "Failed to persist auth state", e);
        }
    }

    private android.content.SharedPreferences getPrefs()
            throws java.security.GeneralSecurityException, IOException {
        MasterKey masterKey = new MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();
        return EncryptedSharedPreferences.create(
                context, PREFS, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
    }
}
