package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.data.route.StoredClimbAttempt;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Levels and XP (issue #249), derived from climb attempts every time — nothing is stored, so
 * deleting or re-matching attempts can never leave stale XP behind. Pure.
 */
public final class XpCalculator {

    public static final int BASE_XP = 10;
    public static final int FIRST_ASCENT_XP = 50;

    public static final class ClimbStats {
        public final int gainM;
        public final int lengthM;
        public ClimbStats(int gainM, int lengthM) { this.gainM = gainM; this.lengthM = lengthM; }
    }

    public static final class Progress {
        public final long totalXp;
        public final int level;
        public final long xpIntoLevel;
        public final long xpForNextLevel;
        public final String title;

        Progress(long totalXp, int level) {
            this.totalXp = totalXp;
            this.level = level;
            this.xpIntoLevel = totalXp - xpForLevel(level);
            this.xpForNextLevel = xpForLevel(level + 1) - xpForLevel(level);
            this.title = titleFor(level);
        }

        public String label() { return "Level " + level + " · " + title; }
    }

    private XpCalculator() {}

    public static Progress compute(List<StoredClimbAttempt> attempts,
                                   Map<String, ClimbStats> statsByClimbId) {
        long xp = 0;
        // The first-ascent bonus is a flat amount per climb, so only the set of distinct climbs
        // matters, not which attempt was first.
        Set<String> climbed = new HashSet<>();
        for (StoredClimbAttempt a : attempts) {
            ClimbStats s = statsByClimbId.get(a.climbId);
            xp += BASE_XP;
            if (s != null) xp += Math.max(0, s.gainM) + Math.max(0, s.lengthM) / 100;
            climbed.add(a.climbId);
        }
        xp += (long) FIRST_ASCENT_XP * climbed.size();
        int level = 1;
        while (xpForLevel(level + 1) <= xp) level++;
        return new Progress(xp, level);
    }

    public static long xpForLevel(int level) {
        return 50L * level * (level - 1);
    }

    public static String titleFor(int level) {
        if (level >= 16) return "Koning van de berg";
        if (level >= 12) return "Col-jager";
        if (level >= 8) return "Bergrijder";
        if (level >= 5) return "Klimmer";
        if (level >= 3) return "Heuvelrijder";
        return "Beginner";
    }
}
