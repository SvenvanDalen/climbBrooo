package nl.paree.climbpro.service;

import android.content.Context;

import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

import nl.paree.climbpro.data.planning.PlannedClimb;
import nl.paree.climbpro.domain.planning.PlannedClimbScheduler;

/**
 * Enqueues/cancels the one-off {@link PlannedClimbReminderWorker} for a single planned climb
 * (issue #70). Mirrors {@link SyncScheduler}'s use of unique WorkManager work names, keyed by
 * plan id so add/remove/reschedule is idempotent from the caller's point of view.
 */
public final class PlannedClimbWorkScheduler {

    private static final String WORK_NAME_PREFIX = "planned_climb_reminder_";

    private PlannedClimbWorkScheduler() {}

    /** Schedules (or reschedules) the reminder for this plan based on its plannedAtEpochSec. */
    public static void schedule(Context context, PlannedClimb plan) {
        long nowSec = System.currentTimeMillis() / 1000L;
        long delaySec = PlannedClimbScheduler.delaySeconds(plan, nowSec);

        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(PlannedClimbReminderWorker.class)
                .setInitialDelay(delaySec, TimeUnit.SECONDS)
                .setInputData(new Data.Builder()
                        .putString(PlannedClimbReminderWorker.KEY_PLAN_ID, plan.id)
                        .build())
                .build();

        WorkManager.getInstance(context).enqueueUniqueWork(
                workName(plan.id), ExistingWorkPolicy.REPLACE, work);
    }

    /** Cancels a pending reminder, e.g. when the plan is removed. */
    public static void cancel(Context context, String planId) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(planId));
    }

    private static String workName(String planId) {
        return WORK_NAME_PREFIX + planId;
    }
}
