package nl.paree.climbpro.domain.ride;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.route.StoredClimbAttempt;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Badges and achievements (issue #191): fixed rules over the ride archive (#160) and the climb
 * attempts, plus one "collectie compleet" badge per user collection. Nothing is persisted —
 * badges are recomputed on every open, so a badge's date is the moment the rule was first met
 * in the history. Pure; zone is explicit. Phone-only, never sent to the watch.
 *
 * <p>Ride badges count every archived ride, including {@code VirtualRide}: the archive only
 * holds cycling activities and trainer km are real km (same rule as the yearly goal, #157).
 * Rides with an unparsed start date ({@code startEpochSec <= 0}) are skipped because a badge
 * without a date can't be ordered.
 */
public final class BadgeCalculator {

    /** One badge, earned or not. */
    public static final class Badge {
        /** Stable key, e.g. "ride_100km" or "collection:&lt;id&gt;". */
        public final String key;
        public final String title;
        public final String description;
        /** Progress towards {@link #goal}, clamped to the goal. */
        public final long progress;
        public final long goal;
        /** When the rule was first met (epoch seconds); 0 when not earned. */
        public final long earnedEpochSec;

        Badge(String key, String title, String description, long progress, long goal,
              long earnedEpochSec) {
            this.key = key;
            this.title = title;
            this.description = description;
            this.progress = Math.min(progress, goal);
            this.goal = goal;
            this.earnedEpochSec = earnedEpochSec;
        }

        public boolean earned() { return earnedEpochSec > 0; }
    }

    /** A user collection reduced to the climb ids it contains (see issue #191). */
    public static final class CollectionClimbs {
        public final String id;
        public final String name;
        public final Set<String> climbIds;

        public CollectionClimbs(String id, String name, Set<String> climbIds) {
            this.id = id;
            this.name = name;
            this.climbIds = climbIds;
        }
    }

    static final double EVEREST_M = 8_848;
    /** Rides starting before this local hour count as early. */
    static final int EARLY_HOUR = 7;
    static final int EARLY_RIDES = 5;
    /** A collection needs this many climbs before completing it is worth a badge. */
    static final int MIN_COLLECTION_CLIMBS = 2;

    private BadgeCalculator() {}

    /**
     * @return every badge: earned ones first (newest first), then the rest by progress
     *         fraction (closest first), ties in definition order
     */
    public static List<Badge> compute(List<StoredRide> rides, List<StoredClimbAttempt> attempts,
                                      List<CollectionClimbs> collections, ZoneId zone) {
        List<StoredRide> sortedRides = datedRides(rides);
        List<StoredClimbAttempt> sortedAttempts = datedAttempts(attempts);

        List<Badge> out = new ArrayList<>();
        out.add(singleRide(sortedRides, "ride_100km", "Eerste 100 km",
                "Een rit van minstens 100 km.", 100_000, false));
        out.add(singleRide(sortedRides, "ride_200km", "Dubbele eeuw",
                "Een rit van minstens 200 km.", 200_000, false));
        out.add(singleRide(sortedRides, "ride_2000hm", "Bergdag",
                "Minstens 2.000 hoogtemeters in één rit.", 2_000, true));
        out.add(cumulative(sortedRides, "total_1000km", "1.000 km",
                "In totaal 1.000 km gereden.", 1_000_000, false, 1000));
        out.add(cumulative(sortedRides, "total_10000km", "10.000 km",
                "In totaal 10.000 km gereden.", 10_000_000, false, 1000));
        out.add(cumulative(sortedRides, "everest", "Everest",
                "In totaal 8.848 hoogtemeters geklommen — de Mount Everest.", EVEREST_M, true, 1));
        out.add(earlyBird(sortedRides, zone));
        out.add(distinctClimbs(sortedAttempts, "climbs_10", "10 klimmen",
                "10 verschillende klimmen beklommen.", 10));
        out.add(distinctClimbs(sortedAttempts, "climbs_50", "50 klimmen",
                "50 verschillende klimmen beklommen.", 50));
        if (collections != null) {
            for (CollectionClimbs c : collections) {
                Badge b = collectionComplete(c, sortedAttempts);
                if (b != null) out.add(b);
            }
        }
        sort(out);
        return out;
    }

    private static Badge singleRide(List<StoredRide> rides, String key, String title,
                                    String description, double threshold, boolean elevation) {
        double best = 0;
        for (StoredRide r : rides) {
            double v = elevation ? r.elevationGainM : r.distanceM;
            if (v >= threshold) {
                return new Badge(key, title, description, 1, 1, r.startEpochSec);
            }
            best = Math.max(best, v);
        }
        // Progress in whole km / m for the "not yet" line.
        int unit = elevation ? 1 : 1000;
        return new Badge(key, title, description, (long) (best / unit),
                (long) (threshold / unit), 0);
    }

    private static Badge cumulative(List<StoredRide> rides, String key, String title,
                                    String description, double threshold, boolean elevation,
                                    int unit) {
        double sum = 0;
        for (StoredRide r : rides) {
            sum += Math.max(0, elevation ? r.elevationGainM : r.distanceM);
            if (sum >= threshold) {
                return new Badge(key, title, description, 1, 1, r.startEpochSec);
            }
        }
        return new Badge(key, title, description, (long) (sum / unit),
                (long) (threshold / unit), 0);
    }

    private static Badge earlyBird(List<StoredRide> rides, ZoneId zone) {
        int count = 0;
        for (StoredRide r : rides) {
            int hour = Instant.ofEpochSecond(r.startEpochSec).atZone(zone).getHour();
            if (hour < EARLY_HOUR && ++count >= EARLY_RIDES) {
                return new Badge("early_bird", "Vroege vogel",
                        "5 ritten vóór 7:00 gestart.", 1, 1, r.startEpochSec);
            }
        }
        return new Badge("early_bird", "Vroege vogel", "5 ritten vóór 7:00 gestart.",
                count, EARLY_RIDES, 0);
    }

    private static Badge distinctClimbs(List<StoredClimbAttempt> attempts, String key,
                                        String title, String description, int goal) {
        Set<String> seen = new HashSet<>();
        for (StoredClimbAttempt a : attempts) {
            if (seen.add(a.climbId) && seen.size() >= goal) {
                return new Badge(key, title, description, 1, 1, a.dateEpochSec);
            }
        }
        return new Badge(key, title, description, seen.size(), goal, 0);
    }

    /** Null for collections too small to be worth a badge. */
    static Badge collectionComplete(CollectionClimbs c, List<StoredClimbAttempt> sortedAttempts) {
        if (c == null || c.climbIds == null || c.climbIds.size() < MIN_COLLECTION_CLIMBS) {
            return null;
        }
        String key = "collection:" + c.id;
        String title = "Alle klimmen: " + c.name;
        String description = "Alle " + c.climbIds.size() + " klimmen uit de collectie \""
                + c.name + "\" beklommen.";
        Set<String> done = new HashSet<>();
        for (StoredClimbAttempt a : sortedAttempts) {
            if (c.climbIds.contains(a.climbId) && done.add(a.climbId)
                    && done.size() == c.climbIds.size()) {
                return new Badge(key, title, description, 1, 1, a.dateEpochSec);
            }
        }
        return new Badge(key, title, description, done.size(), c.climbIds.size(), 0);
    }

    static void sort(List<Badge> badges) {
        Map<Badge, Integer> order = new HashMap<>();
        for (int i = 0; i < badges.size(); i++) order.put(badges.get(i), i);
        Collections.sort(badges, new Comparator<Badge>() {
            @Override
            public int compare(Badge a, Badge b) {
                if (a.earned() != b.earned()) return a.earned() ? -1 : 1;
                if (a.earned()) {
                    int byDate = Long.compare(b.earnedEpochSec, a.earnedEpochSec);
                    if (byDate != 0) return byDate;
                } else {
                    int byFraction = Double.compare(fraction(b), fraction(a));
                    if (byFraction != 0) return byFraction;
                }
                return Integer.compare(order.get(a), order.get(b));
            }
        });
    }

    static double fraction(Badge b) {
        return b.goal <= 0 ? 0 : b.progress / (double) b.goal;
    }

    private static List<StoredRide> datedRides(List<StoredRide> rides) {
        List<StoredRide> out = new ArrayList<>();
        if (rides != null) {
            for (StoredRide r : rides) {
                if (r != null && r.startEpochSec > 0) out.add(r);
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(a.startEpochSec, b.startEpochSec));
        return out;
    }

    private static List<StoredClimbAttempt> datedAttempts(List<StoredClimbAttempt> attempts) {
        List<StoredClimbAttempt> out = new ArrayList<>();
        if (attempts != null) {
            for (StoredClimbAttempt a : attempts) {
                if (a != null && a.climbId != null && a.dateEpochSec > 0) out.add(a);
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(a.dateEpochSec, b.dateEpochSec));
        return out;
    }
}
