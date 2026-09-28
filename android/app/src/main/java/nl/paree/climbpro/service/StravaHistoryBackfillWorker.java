package nl.paree.climbpro.service;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.strava.StravaActivitiesRepository;
import nl.paree.climbpro.data.strava.StravaAuthRepository;
import nl.paree.climbpro.widget.WeekWidgetProvider;

/**
 * Runs the one-off Strava history backfill (issue #312). A paused or failed run returns
 * {@link Result#retry()}, and WorkManager's linear 15-minute backoff (see
 * {@link SyncScheduler#startHistoryBackfill}) resumes it from the saved cursor, across app
 * kills and reboots, until the whole window is in. That can take days on a large history
 * because of Strava's daily limit.
 */
public final class StravaHistoryBackfillWorker extends Worker {

    private static final String TAG = "HistoryBackfillWorker";
    public  static final String KEY_CURSOR = "cursor_epoch_sec";
    public  static final String KEY_FLOOR  = "floor_epoch_sec";
    public  static final String KEY_PAUSED = "paused";

    public StravaHistoryBackfillWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        StravaAuthRepository auth = new StravaAuthRepository(ctx);
        if (!auth.isAuthorised()) {
            Log.w(TAG, "Not signed in to Strava; backfill stopped");
            return Result.failure();
        }
        StravaActivitiesRepository repo = new StravaActivitiesRepository(ctx, auth,
                new RouteRepository(ctx), new ClimbAttemptRepository(ctx));
        try {
            StravaActivitiesRepository.BackfillResult r = repo.backfillHistory((cursor, floor) ->
                    setProgressAsync(progress(cursor, floor, false)));
            Log.i(TAG, "Backfill run: " + r.status + ", " + r.ridesArchived + " ride(s), "
                    + r.attemptsCreated + " attempt(s)");
            if (r.attemptsCreated > 0) WeekWidgetProvider.refresh(ctx);
            if (r.status == StravaActivitiesRepository.BackfillStatus.DONE) {
                return Result.success(progress(r.cursorEpochSec, r.floorEpochSec, false));
            }
            setProgressAsync(progress(r.cursorEpochSec, r.floorEpochSec,
                    r.status == StravaActivitiesRepository.BackfillStatus.PAUSED_RATE_LIMIT));
            return Result.retry();
        } catch (Exception e) {
            Log.w(TAG, "Backfill run failed; will retry", e);
            return Result.retry();
        }
    }

    private static Data progress(long cursor, long floor, boolean paused) {
        return new Data.Builder()
                .putLong(KEY_CURSOR, cursor)
                .putLong(KEY_FLOOR, floor)
                .putBoolean(KEY_PAUSED, paused)
                .build();
    }
}
