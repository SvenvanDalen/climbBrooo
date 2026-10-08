package nl.paree.climbpro.ui.mywhoosh;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

import nl.paree.climbpro.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Weight log screen: background work must survive the activity being destroyed mid-task. */
@RunWith(RobolectricTestRunner.class)
public class WeightLogActivityTest {

    private final AtomicReference<Throwable> uncaught = new AtomicReference<>();
    private Thread.UncaughtExceptionHandler previousHandler;

    @Before
    public void setUp() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> uncaught.compareAndSet(null, e));
    }

    @After
    public void tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler);
    }

    /** A running import that finishes after onDestroy must not crash on its follow-up reload. */
    @Test
    public void importFinishingAfterDestroyDoesNotCrash() throws Exception {
        ActivityController<WeightLogActivity> controller =
                Robolectric.buildActivity(WeightLogActivity.class).setup();
        WeightLogActivity activity = controller.get();
        activity.executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
        shadowOf(Looper.getMainLooper()).idle();

        CountDownLatch gate = new CountDownLatch(1);
        activity.executor.execute(() -> {
            try {
                gate.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        Button importButton = findButton(activity, "Importeren uit Health Connect");
        assertNotNull(importButton);
        importButton.performClick();

        controller.pause().stop().destroy();
        gate.countDown();
        assertTrue(activity.executor.awaitTermination(5, TimeUnit.SECONDS));
        shadowOf(Looper.getMainLooper()).idle();

        assertNull("worker thread crashed", uncaught.get());
    }

    private static Button findButton(WeightLogActivity activity, String text) {
        LinearLayout rows = activity.findViewById(R.id.rows);
        for (int i = 0; i < rows.getChildCount(); i++) {
            View child = rows.getChildAt(i);
            if (child instanceof Button && text.contentEquals(((Button) child).getText())) {
                return (Button) child;
            }
        }
        return null;
    }
}
