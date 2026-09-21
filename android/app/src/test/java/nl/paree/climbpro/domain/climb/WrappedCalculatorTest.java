package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.domain.climb.WrappedCalculator.ClimbInfo;
import nl.paree.climbpro.domain.climb.WrappedCalculator.Summary;

import org.junit.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WrappedCalculatorTest {

    private static long epoch(int year, int month, int day) {
        return ZonedDateTime.of(year, month, day, 12, 0, 0, 0, ZoneOffset.UTC).toEpochSecond();
    }

    private static StoredClimbAttempt at(String climbId, long actId, long dateSec, int elapsed) {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = climbId;
        a.activityId = actId;
        a.dateEpochSec = dateSec;
        a.elapsedSec = elapsed;
        return a;
    }

    private static Map<String, ClimbInfo> info(Object... nameGainPairs) {
        Map<String, ClimbInfo> map = new HashMap<>();
        for (int i = 0; i < nameGainPairs.length; i += 3) {
            String climbId = (String) nameGainPairs[i];
            String name = (String) nameGainPairs[i + 1];
            int gain = (Integer) nameGainPairs[i + 2];
            map.put(climbId, new ClimbInfo(name, gain));
        }
        return map;
    }

    @Test
    public void emptyYear_noAttempts_returnsZeroedSummary() {
        Summary s = WrappedCalculator.compute(2026, Collections.emptyList(), Collections.emptyMap());

        assertEquals(0, s.totalAttempts);
        assertEquals(0, s.distinctClimbCount);
        assertEquals(0L, s.totalElevationGainM);
        assertEquals(0L, s.totalClimbingTimeSec);
        assertNull(s.favoriteClimbId);
        assertNull(s.biggestImprovementClimbId);
    }

    @Test
    public void attemptsOutsideYear_areExcluded() {
        List<StoredClimbAttempt> attempts = Arrays.asList(
                at("k1", 1, epoch(2025, 6, 1), 500),
                at("k1", 2, epoch(2027, 6, 1), 500));

        Summary s = WrappedCalculator.compute(2026, attempts, Collections.emptyMap());

        assertEquals(0, s.totalAttempts);
        assertNull(s.favoriteClimbId);
    }

    @Test
    public void singleClimbSingleAttempt_hasNoImprovementButHasFavorite() {
        List<StoredClimbAttempt> attempts = Collections.singletonList(
                at("k1", 1, epoch(2026, 3, 1), 600));
        Map<String, ClimbInfo> info = info("k1", "Alpe d'Huez", 1100);

        Summary s = WrappedCalculator.compute(2026, attempts, info);

        assertEquals(1, s.totalAttempts);
        assertEquals(1, s.distinctClimbCount);
        assertEquals(1100L, s.totalElevationGainM);
        assertEquals(600L, s.totalClimbingTimeSec);
        assertEquals("k1", s.favoriteClimbId);
        assertEquals("Alpe d'Huez", s.favoriteClimbName);
        assertEquals(1, s.favoriteClimbAttemptCount);
        assertNull(s.biggestImprovementClimbId); // needs >= 2 attempts
    }

    @Test
    public void favoriteClimb_pickedByFrequency() {
        List<StoredClimbAttempt> attempts = Arrays.asList(
                at("k1", 1, epoch(2026, 1, 1), 600),
                at("k1", 2, epoch(2026, 2, 1), 590),
                at("k1", 3, epoch(2026, 3, 1), 580),
                at("k2", 4, epoch(2026, 4, 1), 900));
        Map<String, ClimbInfo> info = info(
                "k1", "Mont Ventoux", 1600,
                "k2", "Col du Tourmalet", 1400);

        Summary s = WrappedCalculator.compute(2026, attempts, info);

        assertEquals(2, s.distinctClimbCount);
        assertEquals(4, s.totalAttempts);
        assertEquals("k1", s.favoriteClimbId);
        assertEquals("Mont Ventoux", s.favoriteClimbName);
        assertEquals(3, s.favoriteClimbAttemptCount);
    }

    @Test
    public void elevationGain_summedPerAttemptNotPerDistinctClimb() {
        List<StoredClimbAttempt> attempts = Arrays.asList(
                at("k1", 1, epoch(2026, 1, 1), 600), // +500
                at("k1", 2, epoch(2026, 2, 1), 590), // +500
                at("k2", 3, epoch(2026, 3, 1), 900)); // +300
        Map<String, ClimbInfo> info = info(
                "k1", "Climb One", 500,
                "k2", "Climb Two", 300);

        Summary s = WrappedCalculator.compute(2026, attempts, info);

        assertEquals(1300L, s.totalElevationGainM); // 500 + 500 + 300
    }

    @Test
    public void biggestImprovement_comparesEarliestVsFastestWithinYear() {
        List<StoredClimbAttempt> attempts = Arrays.asList(
                // k1: earliest 700s, later drops to 600s -> improvement 100s
                at("k1", 1, epoch(2026, 1, 1), 700),
                at("k1", 2, epoch(2026, 6, 1), 600),
                // k2: earliest 500s, later 490s -> improvement 10s
                at("k2", 3, epoch(2026, 1, 1), 500),
                at("k2", 4, epoch(2026, 6, 1), 490));
        Map<String, ClimbInfo> info = info(
                "k1", "Big Improver", 800,
                "k2", "Small Improver", 800);

        Summary s = WrappedCalculator.compute(2026, attempts, info);

        assertEquals("k1", s.biggestImprovementClimbId);
        assertEquals("Big Improver", s.biggestImprovementClimbName);
        assertEquals(100, s.biggestImprovementSec);
    }

    @Test
    public void biggestImprovement_tie_resolvesDeterministically() {
        // Both climbs improve by exactly 50s; k2 has more attempts so it should win the tie.
        List<StoredClimbAttempt> attempts = Arrays.asList(
                at("k1", 1, epoch(2026, 1, 1), 550),
                at("k1", 2, epoch(2026, 6, 1), 500),
                at("k2", 3, epoch(2026, 1, 1), 550),
                at("k2", 4, epoch(2026, 3, 1), 520),
                at("k2", 5, epoch(2026, 6, 1), 500));

        Summary s = WrappedCalculator.compute(2026, attempts, Collections.emptyMap());

        assertEquals(50, s.biggestImprovementSec);
        assertEquals("k2", s.biggestImprovementClimbId); // more attempts breaks the tie
    }

    @Test
    public void improvement_zeroWhenFastestAttemptWasTheEarliestOne() {
        List<StoredClimbAttempt> attempts = Arrays.asList(
                at("k1", 1, epoch(2026, 1, 1), 500), // fastest and earliest
                at("k1", 2, epoch(2026, 6, 1), 550));

        Summary s = WrappedCalculator.compute(2026, attempts, Collections.emptyMap());

        assertEquals("k1", s.biggestImprovementClimbId);
        assertEquals(0, s.biggestImprovementSec);
    }
}
