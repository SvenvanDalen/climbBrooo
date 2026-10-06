package nl.paree.climbpro.testsupport;

import android.content.Context;

import androidx.preference.PreferenceManager;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.File;
import java.util.concurrent.Callable;

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
        wipe();
        UiTestData.seed(app());
    }

    /** Polls {@code condition} until true or {@code timeoutMs} passes. */
    public static boolean waitFor(Callable<Boolean> condition, long timeoutMs) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (Boolean.TRUE.equals(condition.call())) return true;
            Thread.sleep(100);
        }
        return Boolean.TRUE.equals(condition.call());
    }

    private static void deleteRecursively(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursively(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
