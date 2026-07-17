package nl.paree.climbpro;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import nl.paree.climbpro.service.RebindScheduler;

/**
 * Re-registers Connect IQ binder-service delivery right after boot so the watch
 * can reach the app without the user opening it first. WorkManager replays the
 * periodic rebind on its own after reboot; this receiver only removes the
 * up-to-6-hour gap. BOOT_COMPLETED is a protected system broadcast, so
 * exported="false" is safe (the system is exempt from export checks — same
 * pattern as WorkManager's own RescheduleReceiver).
 */
public final class BootCompletedReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            RebindScheduler.triggerImmediateRebind(context);
        }
    }
}
