package nl.paree.climbpro.service;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.safehome.SafeHomeRepository;
import nl.paree.climbpro.data.safehome.SafeHomeSettings;
import nl.paree.climbpro.data.strava.StravaActivitiesRepository;
import nl.paree.climbpro.data.strava.StravaAuthRepository;
import nl.paree.climbpro.domain.safehome.SafeHomeDecider;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * "Ik ben veilig thuis" (issue #231): while the feature is on, polls Strava every 15 minutes
 * (WorkManager's minimum) for a freshly uploaded ride and then messages the chosen contact.
 * One cheap activity-list call per run and only while enabled; the regular ride-archive sync
 * is too infrequent (charging + Wi-Fi) for a timely "I'm home".
 */
public final class SafeHomeWorker extends Worker {

    private static final String TAG = "SafeHomeWorker";
    private static final String UNIQUE = "climbpro_safe_home";

    /** Fetches rides started after the given moment. */
    interface RideSource {
        List<StoredRide> ridesStartedAfter(long epochSec) throws IOException;
    }

    /** Sends the message; returns whether it was sent or offered to the user. */
    interface Delivery {
        boolean deliver(SafeHomeSettings s, String text);
    }

    public SafeHomeWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /** Schedules (KEEP) or cancels the poll to match the stored setting. Call on start/save. */
    public static void syncSchedule(Context ctx) {
        WorkManager wm = WorkManager.getInstance(ctx);
        if (!new SafeHomeRepository(ctx).load().enabled) {
            wm.cancelUniqueWork(UNIQUE);
            return;
        }
        PeriodicWorkRequest work = new PeriodicWorkRequest.Builder(
                SafeHomeWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build();
        wm.enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.KEEP, work);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        StravaAuthRepository auth = new StravaAuthRepository(ctx);
        if (!auth.isAuthorised()) return Result.success();
        StravaActivitiesRepository strava = new StravaActivitiesRepository(ctx, auth,
                new RouteRepository(ctx), new ClimbAttemptRepository(ctx));
        try {
            runOnce(new SafeHomeRepository(ctx), strava::listRecentRides,
                    (s, text) -> SafeHomeSender.deliver(ctx, s, text),
                    System.currentTimeMillis() / 1000L);
            return Result.success();
        } catch (IOException e) {
            // Network hiccup: the next 15-minute run tries again; no retry storm needed.
            Log.w(TAG, "Safe-home check failed", e);
            return Result.success();
        }
    }

    /**
     * One check. Sends a single message for the most recently ended qualifying ride and marks
     * every qualifying ride reported, but only after delivery succeeded — a blocked
     * notification leaves them unmarked for the next run (still inside the age window).
     *
     * @return the activity id that was reported, or 0 when nothing was sent.
     */
    static long runOnce(SafeHomeRepository repo, RideSource source, Delivery delivery,
                        long nowEpochSec) throws IOException {
        SafeHomeSettings s = repo.load();
        if (!s.enabled || s.phoneNumber == null) return 0;
        // Rides that ended within the window started at most a long ride's duration earlier.
        long after = Math.max(0, nowEpochSec - SafeHomeDecider.MAX_AGE_SEC - 24L * 3600);
        List<StoredRide> due = SafeHomeDecider.qualifying(
                source.ridesStartedAfter(after), s, nowEpochSec);
        if (due.isEmpty()) return 0;
        StoredRide latest = due.get(due.size() - 1);
        if (!delivery.deliver(s, SafeHomeDecider.formatMessage(s.message, latest))) return 0;
        for (StoredRide r : due) repo.markReported(r.activityId);
        return latest.activityId;
    }
}
