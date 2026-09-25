package nl.paree.climbpro.service;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.ride.WetRideCheck;
import nl.paree.climbpro.domain.ride.WetRideDetector;
import nl.paree.climbpro.domain.weather.HourlyPrecipitation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Picks the archived rides that still need a rain check and judges them (issue #234). Pure
 * apart from the injected {@link PrecipitationSource}, so it is JVM-tested; the Android glue
 * lives in {@code WetRideReminderJob}.
 */
public final class WetRideReminder {

    /** Blocking weather lookup; {@code OpenMeteoClient::fetchPrecipitation} in production. */
    public interface PrecipitationSource {
        HourlyPrecipitation fetch(double lat, double lon) throws IOException;
    }

    /** Bounds network use per sync; the rest is picked up by the next sync. */
    public static final int MAX_FETCHES_PER_RUN = 10;

    private WetRideReminder() {}

    /**
     * @return one new record per judged ride (wet or dry), newest ride first. Rides whose
     *         weather is unknown are left out so a later sync retries them; an IOException
     *         (offline, HTTP error) ends the run but keeps what was judged before it.
     */
    public static List<WetRideCheck> check(List<StoredRide> rides, Set<Long> alreadyChecked,
                                           long nowSec, PrecipitationSource source) {
        List<WetRideCheck> out = new ArrayList<>();
        if (rides == null) return out;

        List<StoredRide> due = new ArrayList<>();
        for (StoredRide r : rides) {
            if (!WetRideDetector.isCandidate(r, nowSec)) continue;
            if (alreadyChecked != null && alreadyChecked.contains(r.activityId)) continue;
            due.add(r);
        }
        Collections.sort(due, (a, b) -> Long.compare(b.startEpochSec, a.startEpochSec));

        int fetches = 0;
        for (StoredRide r : due) {
            if (fetches >= MAX_FETCHES_PER_RUN) break;
            HourlyPrecipitation weather;
            try {
                fetches++;
                weather = source.fetch(r.startLat, r.startLon);
            } catch (IOException e) {
                break;
            }
            WetRideDetector.Verdict v = WetRideDetector.judge(r, weather);
            if (v == null) continue;
            WetRideCheck c = new WetRideCheck();
            c.activityId = r.activityId;
            c.checkedAtSec = nowSec;
            c.precipitationMm = v.mm;
            c.wet = v.wet;
            c.offroad = v.offroad;
            out.add(c);
        }
        return out;
    }
}
