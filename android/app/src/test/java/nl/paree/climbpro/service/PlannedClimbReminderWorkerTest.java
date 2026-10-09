package nl.paree.climbpro.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.NotificationManager;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.work.Data;
import androidx.work.ListenableWorker;
import androidx.work.testing.TestWorkerBuilder;

import nl.paree.climbpro.data.planning.PlannedClimb;
import nl.paree.climbpro.data.planning.PlannedClimbRepository;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.concurrent.Executors;

/** The "planned climb is today" reminder (issue #70). */
@RunWith(RobolectricTestRunner.class)
public class PlannedClimbReminderWorkerTest {

    private final Context app = ApplicationProvider.getApplicationContext();

    private ListenableWorker.Result run(String planId) {
        Data.Builder input = new Data.Builder();
        if (planId != null) input.putString(PlannedClimbReminderWorker.KEY_PLAN_ID, planId);
        PlannedClimbReminderWorker w = TestWorkerBuilder.from(app,
                PlannedClimbReminderWorker.class, Executors.newSingleThreadExecutor())
                .setInputData(input.build()).build();
        return w.doWork();
    }

    private int notifications() {
        return shadowOf(app.getSystemService(NotificationManager.class))
                .getAllNotifications().size();
    }

    @Test
    public void notifiesOnceOnThePlannedDay() throws Exception {
        long now = System.currentTimeMillis() / 1000L;
        PlannedClimbRepository repo = new PlannedClimbRepository(app);
        repo.add(new PlannedClimb("today", "r1", 0, "Cauberg", now, System.currentTimeMillis()));

        assertEquals(ListenableWorker.Result.success(), run("today"));
        assertEquals(1, notifications());
        assertTrue(repo.find("today").reminderSent);

        assertEquals(ListenableWorker.Result.success(), run("today")); // already sent
        assertEquals(1, notifications());
    }

    @Test
    public void skipsMissingRemovedAndNotYetDuePlans() throws Exception {
        assertEquals(ListenableWorker.Result.failure(), run(null));
        assertEquals(ListenableWorker.Result.success(), run("gone"));

        long inAWeek = System.currentTimeMillis() / 1000L + 7 * 86_400L;
        new PlannedClimbRepository(app).add(new PlannedClimb("later", "r1",
                PlannedClimb.WHOLE_ROUTE, "Rondje", inAWeek, System.currentTimeMillis()));
        assertEquals(ListenableWorker.Result.success(), run("later"));
        assertEquals(0, notifications());
    }
}
