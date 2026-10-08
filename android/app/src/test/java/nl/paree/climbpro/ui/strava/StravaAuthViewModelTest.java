package nl.paree.climbpro.ui.strava;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import net.openid.appauth.AuthState;
import net.openid.appauth.AuthorizationException;
import net.openid.appauth.AuthorizationRequest;
import net.openid.appauth.AuthorizationResponse;
import net.openid.appauth.ResponseTypeValues;

import nl.paree.climbpro.BuildConfig;
import nl.paree.climbpro.ui.UiTestEnv;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;


/**
 * {@link StravaAuthViewModel}. Not testable here: that an authorised state survives a restart,
 * because StravaAuthRepository persists it in EncryptedSharedPreferences, which needs the
 * AndroidKeyStore that Robolectric doesn't provide (persisting fails and is only logged), and
 * the token exchange/refresh, which needs both that store and Strava's token endpoint.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class StravaAuthViewModelTest {

    private Application app;
    private StravaAuthViewModel vm;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        vm = new StravaAuthViewModel(app);
        UiTestEnv.settle();
    }

    @Test
    public void initialState_notAuthorisedAndNoError() {
        assertEquals(Boolean.FALSE, vm.authorised().getValue());
        assertNull(vm.error().getValue());
    }

    @Test
    public void buildAuthRequest_targetsStravaWithAppCallbackAndScopes() {
        AuthorizationRequest req = vm.buildAuthRequest();
        assertEquals("https://www.strava.com/oauth/authorize",
                req.configuration.authorizationEndpoint.toString());
        assertEquals("https://www.strava.com/oauth/token",
                req.configuration.tokenEndpoint.toString());
        assertEquals("climbpro://oauth/callback", req.redirectUri.toString());
        assertEquals(ResponseTypeValues.CODE, req.responseType);
        assertEquals(BuildConfig.STRAVA_CLIENT_ID, req.clientId);
        // Strava wants comma-separated scopes (not OAuth's space-separated), so AppAuth sees
        // one scope string; it is sent verbatim.
        assertEquals("read,activity:read_all,activity:write,profile:read_all", req.scope);
    }

    @Test
    public void buildAuthRequest_usesFreshStatePerRequest() {
        assertTrue(!vm.buildAuthRequest().state.equals(vm.buildAuthRequest().state));
    }

    @Test
    public void unauthorisedState_keepsAuthorisedFalse() {
        vm.onAuthStateReceived(new AuthState());
        UiTestEnv.settle();
        assertEquals(Boolean.FALSE, vm.authorised().getValue());
    }

    @Test
    public void authorisedState_flipsAuthorisedTrue() {
        AuthorizationResponse resp = new AuthorizationResponse.Builder(vm.buildAuthRequest())
                .setAccessToken("access-token")
                .build();
        vm.onAuthStateReceived(new AuthState(resp, null));
        assertEquals(Boolean.TRUE, UiTestEnv.awaitValue(vm.authorised(), b -> b));
    }

    @Test
    public void stateWithAuthError_isNotAuthorised() {
        AuthState failed = new AuthState(null,
                AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW);
        vm.onAuthStateReceived(failed);
        UiTestEnv.settle();
        assertEquals(Boolean.FALSE, vm.authorised().getValue());
    }

    @Test
    public void signInThenFailedState_dropsBackToNotAuthorised() {
        AuthorizationResponse resp = new AuthorizationResponse.Builder(vm.buildAuthRequest())
                .setAccessToken("access-token")
                .build();
        vm.onAuthStateReceived(new AuthState(resp, null));
        UiTestEnv.awaitValue(vm.authorised(), b -> b);
        vm.onAuthStateReceived(new AuthState());
        assertEquals(Boolean.FALSE, UiTestEnv.awaitValue(vm.authorised(), b -> !b));
    }

    @Test
    public void onAuthError_latestMessageWins() {
        vm.onAuthError("eerste");
        vm.onAuthError("tweede");
        UiTestEnv.settle();
        assertEquals("tweede", vm.error().getValue());
        assertEquals(Boolean.FALSE, vm.authorised().getValue());
    }
}
