package nl.paree.climbpro.ui.strava;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Intent;

import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.R;
import nl.paree.climbpro.ui.UiTestEnv;

import net.openid.appauth.AuthorizationException;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class StravaAuthScreensTest {

    private Application app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        UiTestEnv.resetViewModelFactory();
    }

    @Test
    public void callback_withoutResponse_finishes() {
        ActivityController<StravaAuthCallbackActivity> c = Robolectric.buildActivity(
                StravaAuthCallbackActivity.class, new Intent(app, StravaAuthCallbackActivity.class))
                .setup();
        assertTrue(c.get().isFinishing());
        c.destroy();
    }

    @Test
    public void callback_withAuthError_finishes() {
        Intent err = AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW.toIntent();
        err.setClass(app, StravaAuthCallbackActivity.class);
        ActivityController<StravaAuthCallbackActivity> c =
                Robolectric.buildActivity(StravaAuthCallbackActivity.class, err).setup();
        assertTrue(c.get().isFinishing());
        // A later redirect into the same (singleTop) screen is handled too.
        c.newIntent(new Intent(app, StravaAuthCallbackActivity.class));
        c.destroy();
    }

    @Test
    public void viewModel_errorAndInitialState() {
        StravaAuthViewModel vm = new StravaAuthViewModel(app);
        UiTestEnv.settle();
        assertEquals(Boolean.FALSE, vm.authorised().getValue());
        vm.onAuthError("geweigerd");
        UiTestEnv.settle();
        assertEquals("geweigerd", vm.error().getValue());
    }

    @Test
    public void authScreen_signInStartsBrowserFlowOrReportsFailure() {
        ActivityController<StravaAuthActivity> c =
                Robolectric.buildActivity(StravaAuthActivity.class).setup();
        StravaAuthActivity a = c.get();
        a.findViewById(R.id.btn_sign_in).performClick();
        UiTestEnv.settle();
        String toast = UiTestEnv.latestToast();
        boolean launched = org.robolectric.Shadows.shadowOf(a).getNextStartedActivity() != null;
        assertTrue(toast, launched || (toast != null && toast.startsWith("Auth launch failed")));
        c.pause().stop().destroy();
    }
}
