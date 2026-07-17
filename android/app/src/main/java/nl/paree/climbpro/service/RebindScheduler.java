package nl.paree.climbpro.service;

import android.content.Context;

import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/**
 * Schedules {@link CiqRebindWorker}. Unlike {@link SyncScheduler} this has NO
 * charging/network constraints — its whole point is to run unconditionally so
 * the GCM binder-service registration stays fresh (post-reboot, post-GCM-update)
 * even when the user never opens the app.
 */
public final class RebindScheduler {

    private static final String PERIODIC_TAG   = "climbpro_periodic_rebind";
    private static final String UNIQUE_BOOT    = "climbpro_boot_rebind";
    private static final long   INTERVAL_HOURS = 6;

    private RebindScheduler() {}

    public static void schedulePeriodicRebind(Context context) {
        PeriodicWorkRequest work = new PeriodicWorkRequest.Builder(
                CiqRebindWorker.class, INTERVAL_HOURS, TimeUnit.HOURS)
                .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_TAG,
                ExistingPeriodicWorkPolicy.KEEP,
                work);
    }

    /** Immediate one-shot rebind (used right after boot). */
    public static void triggerImmediateRebind(Context context) {
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(CiqRebindWorker.class)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_BOOT,
                ExistingWorkPolicy.REPLACE,
                work);
    }
}
