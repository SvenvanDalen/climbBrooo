package nl.paree.climbpro.testsupport;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;

import org.junit.rules.ExternalResource;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the Connect IQ SDK's "Additional App Required" (Garmin Connect Mobile) prompt out of
 * the way on devices without Garmin Connect, such as emulators. The client currently re-shows
 * it on every 5 s reconnect (see ConnectIqClientTest#missingGarminConnectDoesNotRepromptEveryRetry),
 * so a one-off dismissal is not enough: a daemon thread cancels it whenever it appears.
 */
public final class GarminPromptDismisser extends ExternalResource {

    private ScheduledExecutorService scheduler;

    @Override
    protected void before() {
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "garmin-prompt-dismisser");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(() -> dismissOnce(device), 0, 300, TimeUnit.MILLISECONDS);
    }

    private static void dismissOnce(UiDevice device) {
        try {
            if (device.hasObject(By.text("Additional App Required"))) {
                UiObject2 cancel = device.findObject(By.res("android", "button2"));
                if (cancel != null) cancel.click();
            }
            DeviceState.dismissAnrDialogs();
        } catch (RuntimeException ignored) {
            // the window went away between find and click
        }
    }

    @Override
    protected void after() {
        if (scheduler != null) scheduler.shutdownNow();
    }
}
