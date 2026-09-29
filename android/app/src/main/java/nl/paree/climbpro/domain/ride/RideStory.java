package nl.paree.climbpro.domain.ride;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.route.StoredClimbAttempt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ride story (issue #193): what a shareable summary of one ride shows — headline stats, the
 * climbs ridden with PRs marked, the temperature and a photo taken on the ride. The image
 * itself is drawn by the UI; this class only decides the content. Pure.
 */
public final class RideStory {

    /** At most this many climbs are listed; PRs first, then in ride order. */
    static final int MAX_CLIMBS = 4;

    /** One climb ridden on this ride. */
    public static final class ClimbLine {
        public final String name;
        public final int elapsedSec;
        /** Fastest clean ascent up to and including this ride; never on a first ascent. */
        public final boolean pr;
        /** How many times this climb had been ridden, this ascent included. */
        public final int timesRidden;

        ClimbLine(String name, int elapsedSec, boolean pr, int timesRidden) {
            this.name = name;
            this.elapsedSec = elapsedSec;
            this.pr = pr;
            this.timesRidden = timesRidden;
        }
    }

    public final String title;
    public final long startEpochSec;
    /** Headline stats, e.g. "82,4 km", "3:12 u", "940 hm", "25,7 km/u". */
    public final List<String> stats;
    public final List<ClimbLine> climbs;
    /** Average device temperature over the ride, null when unknown. */
    public final Double avgTempC;
    /** Photo file name (under AttemptPhotoStore) of a climb photo on this ride, or null. */
    public final String photoFileName;

    private RideStory(String title, long startEpochSec, List<String> stats,
                      List<ClimbLine> climbs, Double avgTempC, String photoFileName) {
        this.title = title;
        this.startEpochSec = startEpochSec;
        this.stats = stats;
        this.climbs = climbs;
        this.avgTempC = avgTempC;
        this.photoFileName = photoFileName;
    }

    /**
     * @param attempts   every stored climb attempt (needed to tell PRs and repeat counts)
     * @param climbNames display name per ClimbIdentity key; unknown climbs get "Klim"
     * @param avgTempC   average temperature from the ride's stream, or null
     */
    public static RideStory build(StoredRide ride, List<StoredClimbAttempt> attempts,
                                  Map<String, String> climbNames, Double avgTempC) {
        String title = ride.name != null && !ride.name.trim().isEmpty() ? ride.name.trim() : "Rit";
        List<String> stats = new ArrayList<>();
        stats.add(String.format(Locale.GERMANY, "%.1f km", ride.distanceM / 1000f));
        int moving = ride.movingTimeSec > 0 ? ride.movingTimeSec : ride.elapsedTimeSec;
        if (moving > 0) stats.add(String.format(Locale.GERMANY, "%d:%02d u", moving / 3600, (moving / 60) % 60));
        if (ride.elevationGainM > 0) stats.add(Math.round(ride.elevationGainM) + " hm");
        if (ride.avgSpeedMps > 0) {
            stats.add(String.format(Locale.GERMANY, "%.1f km/u", ride.avgSpeedMps * 3.6f));
        }
        if (ride.avgWatts != null && ride.avgWatts > 0) stats.add(Math.round(ride.avgWatts) + " W");

        List<StoredClimbAttempt> onRide = new ArrayList<>();
        List<StoredClimbAttempt> all = attempts != null ? attempts : Collections.emptyList();
        for (StoredClimbAttempt a : all) {
            if (a.activityId == ride.activityId) onRide.add(a);
        }
        onRide.sort((x, y) -> x.startOffsetSec != y.startOffsetSec
                ? Integer.compare(x.startOffsetSec, y.startOffsetSec)
                : Integer.compare(x.passIndex, y.passIndex));

        List<ClimbLine> lines = new ArrayList<>();
        String photo = null;
        Double temp = avgTempC;
        double tempSum = 0;
        int tempN = 0;
        for (StoredClimbAttempt a : onRide) {
            String name = climbNames != null ? climbNames.get(a.climbId) : null;
            int times = timesRidden(a, all);
            // A first ascent is trivially the fastest; the story calls it a first instead.
            lines.add(new ClimbLine(name != null ? name : "Klim", a.elapsedSec,
                    times > 1 && isPr(a, all), times));
            if (photo == null && a.photoFileName != null) photo = a.photoFileName;
            if (a.avgTempC != null) {
                tempSum += a.avgTempC;
                tempN++;
            }
        }
        if (temp == null && tempN > 0) temp = tempSum / tempN;

        List<ClimbLine> shown = new ArrayList<>(lines);
        // Stable: PRs move to the front, the rest keeps ride order.
        shown.sort((x, y) -> Boolean.compare(y.pr, x.pr));
        if (shown.size() > MAX_CLIMBS) shown = new ArrayList<>(shown.subList(0, MAX_CLIMBS));
        return new RideStory(title, ride.startEpochSec, stats, shown, temp, photo);
    }

    /** No clean ascent of this climb before (or earlier on) this ride was faster. */
    static boolean isPr(StoredClimbAttempt a, List<StoredClimbAttempt> all) {
        if (a.routeDeviation || a.elapsedSec <= 0) return false;
        for (StoredClimbAttempt o : all) {
            if (o == a || o.routeDeviation || o.elapsedSec <= 0) continue;
            if (!a.climbId.equals(o.climbId) || !isEarlier(o, a)) continue;
            if (o.elapsedSec <= a.elapsedSec) return false;
        }
        return true;
    }

    static int timesRidden(StoredClimbAttempt a, List<StoredClimbAttempt> all) {
        int n = 0;
        for (StoredClimbAttempt o : all) {
            if (a.climbId.equals(o.climbId) && (o == a || isEarlier(o, a))) n++;
        }
        return n;
    }

    /** {@code o} happened before {@code a}: an earlier ride, or an earlier pass on the same ride. */
    private static boolean isEarlier(StoredClimbAttempt o, StoredClimbAttempt a) {
        if (o.dateEpochSec != a.dateEpochSec) return o.dateEpochSec < a.dateEpochSec;
        if (o.activityId != a.activityId) return false;
        return o.passIndex < a.passIndex;
    }
}
