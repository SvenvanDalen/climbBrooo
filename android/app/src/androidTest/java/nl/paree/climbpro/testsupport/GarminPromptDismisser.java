package nl.paree.climbpro.testsupport;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;

import org.junit.rules.ExternalResource;

/**
 * Keeps the Connect IQ SDK's "Additional App Required" (Garmin Connect Mobile) prompt out of
 * the way on devices without Garmin Connect, such as emulators. The client currently re-shows
 * it on every 5 s reconnect (see ConnectIqClientTest#missingGarminConnectDoesNotRepromptEveryRetry),
 * so a one-off dismissal is not enough: a daemon thread cancels it whenever it appears.
 */
public final class GarminPromptDismisser extends ExternalResource {

    private volatile boolean running;
    private Thread thread;

    @Override
    protected void before() {
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        running = true;
        thread = new Thread(() -> {
            while (running) {
                try {
                    if (device.hasObject(By.text("Additional App Required"))) {
                        UiObject2 cancel = device.findObject(By.res("android", "button2"));
                        if (cancel != null) cancel.click();
                    }
                    DeviceState.dismissAnrDialogs();
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException ignored) {
                    // the window went away between find and click
                }
            }
        }, "garmin-prompt-dismisser");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    protected void after() {
        running = false;
        if (thread != null) thread.interrupt();
    }
}
