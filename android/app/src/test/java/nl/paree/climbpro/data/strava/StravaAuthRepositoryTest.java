package nl.paree.climbpro.data.strava;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import net.openid.appauth.AuthState;
import net.openid.appauth.AuthorizationRequest;
import net.openid.appauth.AuthorizationResponse;
import net.openid.appauth.TokenResponse;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.IOException;

/**
 * EncryptedSharedPreferences has no keystore under Robolectric, so persistence degrades to a
 * no-op; these tests cover the in-memory state machine and the token hand-off.
 */
@RunWith(RobolectricTestRunner.class)
public class StravaAuthRepositoryTest {

    private Application app;
    private StravaAuthRepository repo;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        repo = new StravaAuthRepository(app);
    }

    private AuthState authorisedState(String accessToken, long expiresAtMs, String refreshToken) {
        AuthorizationRequest request = repo.buildAuthRequest();
        AuthorizationResponse authResp = new AuthorizationResponse.Builder(request)
                .setAuthorizationCode("code")
                .build();
        TokenResponse token = new TokenResponse.Builder(authResp.createTokenExchangeRequest())
                .setTokenType("Bearer")
                .setAccessToken(accessToken)
                .setAccessTokenExpirationTime(expiresAtMs)
                .setRefreshToken(refreshToken)
                .build();
        return new AuthState(authResp, token, null);
    }

    @Test
    public void freshRepository_withoutStoredState_isNotAuthorised() {
        assertFalse(repo.isAuthorised());
    }

    @Test
    public void buildAuthRequest_targetsStravaWithWriteScope() {
        AuthorizationRequest req = repo.buildAuthRequest();
        assertEquals("https://www.strava.com/oauth/authorize",
                req.configuration.authorizationEndpoint.toString());
        assertEquals("https://www.strava.com/oauth/token",
                req.configuration.tokenEndpoint.toString());
        assertEquals("climbpro://oauth/callback", req.redirectUri.toString());
        assertEquals("code", req.responseType);
        // Strava wants a comma-separated scope (not the OAuth space-separated form).
        assertEquals("read,activity:read_all,activity:write,profile:read_all", req.scope);
    }

    @Test
    public void getAccessToken_notAuthorised_throws() {
        try {
            repo.getAccessToken();
            fail("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Not authorised"));
        }
    }

    @Test
    public void updateState_unauthorisedState_staysUnauthorised() throws Exception {
        repo.updateState(new AuthState());
        assertFalse(repo.isAuthorised());
        try {
            repo.getAccessToken();
            fail("expected IOException");
        } catch (IOException expected) {
        }
    }

    @Test
    public void updateState_validToken_returnsItWithoutRefresh() throws Exception {
        repo.updateState(authorisedState("abc", System.currentTimeMillis() + 3_600_000L, "r"));
        assertTrue(repo.isAuthorised());
        assertEquals("abc", repo.getAccessToken());
    }

    @Test
    public void getAccessToken_expiredWithoutRefreshToken_wrapsError() {
        repo.updateState(authorisedState("old", System.currentTimeMillis() - 60_000L, null));
        try {
            repo.getAccessToken();
            fail("expected IOException");
        } catch (IOException e) {
            assertEquals("Token refresh failed", e.getMessage());
            assertNotNull(e.getCause());
        }
    }

    @Test
    public void signOut_clearsAuthorisation() {
        repo.updateState(authorisedState("abc", System.currentTimeMillis() + 3_600_000L, "r"));
        repo.signOut();
        assertFalse(repo.isAuthorised());
    }
}
