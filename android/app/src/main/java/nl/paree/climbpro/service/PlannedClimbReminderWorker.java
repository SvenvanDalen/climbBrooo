package nl.paree.climbpro.service;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.time.ZoneId;
import java.util.List;

import nl.paree.climbpro.data.planning.PlannedClimb;
import nl.paree.climbpro.data.planning.PlannedClimbRepository;
import nl.paree.climbpro.domain.planning.PlannedClimbScheduler;

/**
 * One-off WorkManager {@link Worker}, scheduled per planned climb by
 * {@link PlannedClimbWorkScheduler} with an initial delay computed at plan-creation time
 * (issue #70 — Kalenderintegratie voor geplande klimmen).
 *
 * Re-checks "is this actually due today" rather than trusting the delay alone: the plan may
 * have been removed, rescheduled, or already reminded (e.g. after a retry) since the work was
 * enqueued. Idempotent via {@link PlannedClimb#reminderSent}.
 */
public final class PlannedClimbReminderWorker extends Worker {

    private static final String TAG = "PlannedClimbReminder";
    public  static final String KEY_PLAN_ID = "plan_id";

    public PlannedClimbReminderWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        String planId = getInputData().getString(KEY_PLAN_ID);
        if (planId == null) {
            Log.w(TAG, "No plan id in input data — nothing to do");
            return Result.failure();
        }

        Context ctx = getApplicationContext();
        PlannedClimbRepository repo = new PlannedClimbRepository(ctx);
        PlannedClimb plan = repo.find(planId);
        if (plan == null) {
            Log.i(TAG, "Plan " + planId + " no longer exists — skipping (was removed)");
            return Result.success();
        }
        if (plan.reminderSent) {
            return Result.success();
        }

        long nowSec = System.currentTimeMillis() / 1000L;
        List<PlannedClimb> due = PlannedClimbScheduler.dueToday(
                java.util.Collections.singletonList(plan), nowSec, ZoneId.systemDefault());
        if (due.isEmpty()) {
            // Not (yet) today — plan was rescheduled after the work was enqueued. Don't notify;
            // PlannedClimbWorkScheduler re-enqueues on reschedule with a fresh delay.
            Log.i(TAG, "Plan " + planId + " not due today — skipping");
            return Result.success();
        }

        PlannedClimbNotifier.notify(ctx, plan);
        try {
            repo.markReminderSent(planId);
        } catch (java.io.IOException e) {
            Log.e(TAG, "Failed to persist reminderSent for " + planId, e);
            return Result.retry();
        }
        return Result.success();
    }
}
