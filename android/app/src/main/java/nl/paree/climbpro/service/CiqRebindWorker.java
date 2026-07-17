package nl.paree.climbpro.service;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.function.BooleanSupplier;

import nl.paree.climbpro.ClimbProApplication;
import nl.paree.climbpro.connectiq.ConnectIqClient;

/**
 * Lightweight keep-registered worker. Starting this worker starts the app
 * process; {@code ClimbProApplication.onCreate} then kicks off the Connect IQ
 * (re)connect, and connecting re-registers binder-service delivery with Garmin
 * Connect Mobile — the registration that lets the watch wake this app while it
 * is closed. This worker only keeps the process alive long enough for that to
 * finish. It does no sync work (RouteSyncWorker owns that) and is deliberately
 * unconstrained: no network or charging requirement, because it must also run
 * on battery, offline, and shortly after boot.
 */
public final class CiqRebindWorker extends Worker {

    private static final String TAG = "CiqRebindWorker";
    private static final long CONNECT_TIMEOUT_MS = 30_000;
    private static final long POLL_MS = 250;

    public CiqRebindWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        ConnectIqClient client =
                ((ClimbProApplication) getApplicationContext()).connectIqClient();
        boolean connected = awaitConnected(client::isConnected, CONNECT_TIMEOUT_MS, POLL_MS);
        Log.i(TAG, "Rebind pass done, connected=" + connected);
        // Best effort: the watch may simply be out of range or GCM not up yet.
        // The next periodic run tries again — never retry-loop here.
        return Result.success();
    }

    /** Poll {@code connected} every {@code pollMs} until true or {@code timeoutMs} elapsed. */
    static boolean awaitConnected(BooleanSupplier connected, long timeoutMs, long pollMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!connected.getAsBoolean()) {
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(pollMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }
}
