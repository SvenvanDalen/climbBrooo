package nl.paree.climbpro.testsupport;

import android.content.Context;

import androidx.preference.PreferenceManager;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.StaleObjectException;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;

import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;

import java.io.File;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

/** On-device test state: wipe app data between tests and wait for background work. */
public final class DeviceState {

    private DeviceState() {}

    public static Context app() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
    }

    /** Deletes everything under getFilesDir() and the preferences the screens read. */
    public static void wipe() {
        Context app = app();
        File[] files = app.getFilesDir().listFiles();
        if (files != null) for (File f : files) deleteRecursively(f);
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear().commit();
        app.getSharedPreferences("route_repo", Context.MODE_PRIVATE).edit().clear().commit();
    }

    /** Wipes, then seeds the shared fixture routes, rides and collection. */
    public static void seed() throws Exception {
        dismissAnrDialogs();
        wipe();
        UiTestData.seed(app());
    }

    /**
     * Dismisses "X isn't responding" system dialogs. A loaded (CI or headless) emulator often
     * shows one for System UI; it steals window focus and Espresso then fails to find a root.
     */
    public static void dismissAnrDialogs() {
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        for (int i = 0; i < 3; i++) {
            UiObject2 wait = device.findObject(By.res("android", "aerr_wait"));
            if (wait == null) wait = device.findObject(By.text(Pattern.compile("(?i)wait|wachten")).pkg("android"));
            if (wait == null) return;
            try {
                wait.click();
            } catch (StaleObjectException gone) {
                // the dialog closed between find and click; check again
            }
            device.waitForIdle();
        }
    }

    /** Polls {@code condition} until true or {@code timeoutMs} passes. */
    public static boolean waitFor(Callable<Boolean> condition, long timeoutMs) throws Exception {
        try {
            Awaitility.await()
                    .pollDelay(Duration.ZERO)
                    .pollInterval(Duration.ofMillis(100))
                    .atMost(Duration.ofMillis(timeoutMs))
                    .until(() -> Boolean.TRUE.equals(condition.call()));
            return true;
        } catch (ConditionTimeoutException e) {
            return Boolean.TRUE.equals(condition.call());
        }
    }

    private static void deleteRecursively(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursively(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
