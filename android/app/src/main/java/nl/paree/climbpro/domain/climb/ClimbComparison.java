package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.domain.power.ClimbTimeEstimate;
import nl.paree.climbpro.domain.power.ClimbTimeEstimator;
import nl.paree.climbpro.domain.power.RiderProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Compares two different climbs side by side (issue #214): size, steepness, difficulty, the
 * rider's estimated time and own history, and both profiles on one scale. Built entirely from
 * data the phone already has. Pure.
 */
public final class ClimbComparison {

    private ClimbComparison() {}

    private static final Locale DUTCH = new Locale("nl");

    /** Everything shown for one climb. */
    public static final class Side {
        public final String routeId;
        public final int climbIndex;
        public final String name;
        public final int lengthM;
        public final int elevationGainM;
        /** Fractions (0.07 = 7 %). */
        public final double avgGradient;
        public final double maxSegmentGradient;
        /** {@link DifficultyScoreCalculator} score without route fatigue, so both are fresh. */
        public final double difficulty;
        public final String categoryLabel;
        /** Fresh-legs estimate from the rider profile; null without FTP and weight. */
        public final Integer estimateSec;
        public final int attempts;
        /** Fastest recorded time; null without attempts. */
        public final Integer prSec;
        /** Profile as cumulative distance (m) and height above the foot (m), from segments. */
        public final double[] profileDist;
        public final double[] profileHeight;

        Side(String routeId, int climbIndex, String name, StoredClimb c, Integer estimateSec,
             int attempts, Integer prSec) {
            this.routeId = routeId;
            this.climbIndex = climbIndex;
            this.name = name;
            this.lengthM = c.length > 0 ? c.length : c.endDistance - c.startDistance;
            this.elevationGainM = c.elevationGain;
            this.avgGradient = c.avgGradient;
            double max = 0;
            List<StoredSegment> segs = c.segments != null ? c.segments
                    : Collections.<StoredSegment>emptyList();
            profileDist = new double[segs.size() + 1];
            profileHeight = new double[segs.size() + 1];
            for (int i = 0; i < segs.size(); i++) {
                StoredSegment s = segs.get(i);
                max = Math.max(max, s.gradient);
                profileDist[i + 1] = profileDist[i] + s.distance;
                profileHeight[i + 1] = profileHeight[i] + s.distance * s.gradient;
            }
            this.maxSegmentGradient = max;
            this.difficulty = DifficultyScoreCalculator.score(c.elevationGain, c.avgGradient, 0);
            this.categoryLabel = ClimbCategoryLabel.forStoredClimb(c);
            this.estimateSec = estimateSec;
            this.attempts = attempts;
            this.prSec = prSec;
        }
    }

    /** A climb the rider can pick to compare with. */
    public static final class Candidate {
        public final String routeId;
        public final int climbIndex;
        public final String name;
        public final int lengthM;
        public final double avgGradient;

        Candidate(String routeId, int climbIndex, String name, int lengthM, double avgGradient) {
            this.routeId = routeId;
            this.climbIndex = climbIndex;
            this.name = name;
            this.lengthM = lengthM;
            this.avgGradient = avgGradient;
        }
    }

    public static Side side(String routeId, int climbIndex, StoredClimb c,
                            List<StoredClimbAttempt> allAttempts, RiderProfile profile) {
        String id = ClimbIdentity.of(c.startLat, c.startLon, length(c));
        int attempts = 0;
        Integer pr = null;
        if (allAttempts != null) {
            for (StoredClimbAttempt a : allAttempts) {
                if (a == null || !id.equals(a.climbId) || a.elapsedSec <= 0) continue;
                attempts++;
                if (pr == null || a.elapsedSec < pr) pr = a.elapsedSec;
            }
        }
        return new Side(routeId, climbIndex, displayName(c, climbIndex), c,
                estimate(c, profile), attempts, pr);
    }

    /**
     * Every climb in the rider's routes except {@code exclude}'s identity, once per climb (the
     * same climb in several routes is listed once), sorted by name.
     */
    public static List<Candidate> candidates(List<StoredRoute> routes, StoredClimb exclude) {
        Set<String> seen = new HashSet<>();
        if (exclude != null) seen.add(ClimbIdentity.of(exclude.startLat, exclude.startLon,
                length(exclude)));
        List<Candidate> out = new ArrayList<>();
        if (routes == null) return out;
        for (StoredRoute r : routes) {
            if (r == null || r.climbs == null) continue;
            for (int i = 0; i < r.climbs.size(); i++) {
                StoredClimb c = r.climbs.get(i);
                if (!seen.add(ClimbIdentity.of(c.startLat, c.startLon, length(c)))) continue;
                out.add(new Candidate(r.routeId, i, displayName(c, i), length(c), c.avgGradient));
            }
        }
        out.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    /** One line saying which climb is harder and by how much, on the difficulty score. */
    public static String verdict(Side a, Side b) {
        if (a.difficulty <= 0 || b.difficulty <= 0) return "";
        Side harder = a.difficulty >= b.difficulty ? a : b;
        Side easier = harder == a ? b : a;
        double ratio = harder.difficulty / easier.difficulty;
        if (ratio < 1.1) {
            return String.format(DUTCH,
                    "%s en %s zijn ongeveer even zwaar.", a.name, b.name);
        }
        return String.format(DUTCH, "%s is %.1f× zo zwaar als %s.",
                harder.name, ratio, easier.name);
    }

    private static Integer estimate(StoredClimb c, RiderProfile profile) {
        if (profile == null || !profile.isComplete() || c.segments == null
                || c.segments.isEmpty()) {
            return null;
        }
        int n = c.segments.size();
        int[] dist = new int[n];
        double[] grad = new double[n];
        int[] surface = new int[n];
        for (int i = 0; i < n; i++) {
            StoredSegment s = c.segments.get(i);
            dist[i] = s.distance;
            grad[i] = s.gradient;
            surface[i] = s.surfaceType;
        }
        ClimbTimeEstimate e = ClimbTimeEstimator.estimate(dist, grad, surface, profile);
        return e != null ? e.totalSeconds : null;
    }

    private static int length(StoredClimb c) {
        return c.length > 0 ? c.length : c.endDistance - c.startDistance;
    }

    private static String displayName(StoredClimb c, int climbIndex) {
        if (c.userDisplayName != null && !c.userDisplayName.trim().isEmpty()) {
            return c.userDisplayName;
        }
        if (c.name != null && !c.name.trim().isEmpty()) return c.name;
        return "Klim " + (climbIndex + 1);
    }
}
