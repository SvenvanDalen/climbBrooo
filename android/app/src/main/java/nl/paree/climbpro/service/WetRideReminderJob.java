package nl.paree.climbpro.service;

import android.content.Context;
import android.util.Log;

import androidx.core.app.NotificationManagerCompat;
import androidx.preference.PreferenceManager;

import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.ride.WetRideCheck;
import nl.paree.climbpro.data.ride.WetRideCheckRepository;
import nl.paree.climbpro.data.weather.OpenMeteoClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Android glue for the cleaning reminder (issue #234), run by {@link RouteSyncWorker} after
 * the ride archive refresh. Opt-in ({@link #PREF_ENABLED}); does nothing — not even a network
 * call — when it is off or notifications are blocked. Blocking; never throws.
 */
public final class WetRideReminderJob {

    private static final String TAG = "WetRideReminderJob";
    public  static final String PREF_ENABLED = "wet_ride_reminder_enabled";

    /** The periodic and the manual sync can overlap; one check at a time avoids double alerts. */
    private static final Object LOCK = new Object();

    private WetRideReminderJob() {}

    public static void runIfEnabled(Context context) {
        Context app = context.getApplicationContext();
        if (!PreferenceManager.getDefaultSharedPreferences(app).getBoolean(PREF_ENABLED, false)) {
            return;
        }
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return;
        synchronized (LOCK) {
            try {
                WetRideCheckRepository store = new WetRideCheckRepository(app);
                List<StoredRide> rides = new RideRepository(app).loadAll();
                OpenMeteoClient client = new OpenMeteoClient();
                List<WetRideCheck> fresh = WetRideReminder.check(rides, store.checkedIds(),
                        System.currentTimeMillis() / 1000L, client::fetchPrecipitation);
                if (fresh.isEmpty()) return;
                // Persist before notifying: a crash after this line can't alert twice.
                store.addAll(fresh);
                Map<Long, String> names = new HashMap<>();
                for (StoredRide r : rides) if (r != null) names.put(r.activityId, r.name);
                for (WetRideCheck c : fresh) {
                    if (c.wet) WetRideNotifier.notify(app, c, names.get(c.activityId));
                }
            } catch (Exception e) {
                Log.w(TAG, "Wet-ride check failed; sync continues", e);
            }
        }
    }
}
