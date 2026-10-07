package nl.paree.climbpro.ui.routes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assume.assumeTrue;

import android.Manifest;
import android.app.UiAutomation;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import nl.paree.climbpro.testsupport.DeviceState;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.regex.Pattern;

/**
 * BLUETOOTH_CONNECT runtime permission (Android 12+): without it the Connect IQ SDK sees no
 * watch, so the route list must ask for it — and survive a denial.
 */
@RunWith(AndroidJUnit4.class)
public class BluetoothPermissionInstrumentedTest {

    private static final Pattern DENY = Pattern.compile("(?i)(don.t allow|deny|niet toestaan|weigeren)");
    private static final Pattern ALLOW = Pattern.compile("(?i)(^allow$|toestaan)");
    private static final String PERMISSION_UI = "com.google.android.permissioncontroller";

    @org.junit.Rule
    public nl.paree.climbpro.testsupport.GarminPromptDismisser garminPrompt =
            new nl.paree.climbpro.testsupport.GarminPromptDismisser();

    private Context app;
    private UiDevice device;
    private UiAutomation automation;

    @Before
    public void setUp() throws Exception {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S);
        DeviceState.seed();
        app = DeviceState.app();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
    }

    /**
     * Revoking a runtime permission kills the app process — and with it this instrumentation —
     * so the "not granted" tests only run when the grant was removed beforehand:
     * {@code adb shell pm revoke nl.paree.climbpro android.permission.BLUETOOTH_CONNECT}.
     */
    private void requireNotGranted() {
        assumeTrue("BLUETOOTH_CONNECT already granted; revoke it via adb to run this test",
                granted() == PackageManager.PERMISSION_DENIED);
    }

    private int granted() {
        return app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT);
    }

    @Test
    public void missingGrantShowsSystemDialogAndDenialKeepsAppUsable() throws Exception {
        requireNotGranted();
        try (ActivityScenario<RouteListActivity> s = ActivityScenario.launch(RouteListActivity.class)) {
            UiObject2 deny = device.wait(Until.findObject(By.pkg(PERMISSION_UI).text(DENY)), 10_000);
            assertNotNull("permission dialog not shown", deny);
            deny.click();
            device.waitForIdle();
            assertEquals(PackageManager.PERMISSION_DENIED, granted());
            s.moveToState(Lifecycle.State.RESUMED);
            assertEquals(Lifecycle.State.RESUMED, s.getState());
        }
    }

    @Test
    public void grantingFromTheDialogGrantsBluetoothConnect() throws Exception {
        requireNotGranted();
        try (ActivityScenario<RouteListActivity> s = ActivityScenario.launch(RouteListActivity.class)) {
            UiObject2 allow = device.wait(Until.findObject(By.pkg(PERMISSION_UI).text(ALLOW)), 10_000);
            assertNotNull("permission dialog not shown", allow);
            allow.click();
            // Android may ask for the second permission of the group separately.
            UiObject2 again = device.wait(Until.findObject(By.pkg(PERMISSION_UI).text(ALLOW)), 2_000);
            if (again != null) again.click();
            DeviceState.waitFor(() -> granted() == PackageManager.PERMISSION_GRANTED, 5_000);
            assertEquals(PackageManager.PERMISSION_GRANTED, granted());
        }
    }

    @Test
    public void existingGrantDoesNotPromptAgain() throws Exception {
        automation.grantRuntimePermission(app.getPackageName(), Manifest.permission.BLUETOOTH_CONNECT);
        automation.grantRuntimePermission(app.getPackageName(), Manifest.permission.BLUETOOTH_SCAN);
        try (ActivityScenario<RouteListActivity> s = ActivityScenario.launch(RouteListActivity.class)) {
            assertNull(device.wait(Until.findObject(By.pkg(PERMISSION_UI)), 3_000));
            assertFalse(s.getState() == Lifecycle.State.DESTROYED);
        }
    }
}
