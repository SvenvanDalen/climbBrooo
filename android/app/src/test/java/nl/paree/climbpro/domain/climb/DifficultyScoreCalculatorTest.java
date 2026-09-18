package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DifficultyScoreCalculatorTest {

    @Test
    public void fatigue_increasesScore_forClimbLateInRoute() {
        double early = DifficultyScoreCalculator.score(400, 0.06, 5.0);   // 5 km in
        double late  = DifficultyScoreCalculator.score(400, 0.06, 150.0); // 150 km in

        assertTrue("a climb deep into a long route should score higher than the same climb early on",
                late > early);
    }

    @Test
    public void fatigue_isNeutral_forStandaloneClimbWithNoRouteContext() {
        double standalone = DifficultyScoreCalculator.score(400, 0.06, 0.0);
        double base = 400 * 0.06;

        assertEquals(base, standalone, 1e-9);
        assertEquals(1.0, DifficultyScoreCalculator.fatigueMultiplier(0.0), 1e-9);
    }

    @Test
    public void fatigue_degradesGracefully_forNegativeOrNaNDistance() {
        // Defensive: callers that can't resolve a route position (e.g. unresolved climb)
        // must still get a sane, neutral score rather than NaN/negative multipliers.
        assertEquals(1.0, DifficultyScoreCalculator.fatigueMultiplier(-1.0), 1e-9);
        assertEquals(1.0, DifficultyScoreCalculator.fatigueMultiplier(Double.NaN), 1e-9);
    }

    @Test
    public void fatigue_isCapped_forVeryLongRoutes() {
        double atCap = DifficultyScoreCalculator.fatigueMultiplier(100_000.0); // absurdly far in
        double bound = 1.0 + DifficultyScoreConstants.FATIGUE_MAX_BONUS;

        assertTrue(atCap <= bound + 1e-9);
        assertTrue(atCap > bound - 0.01); // should be very close to the asymptote
    }

    @Test
    public void fatigue_isMonotonic_withIncreasingDistance() {
        double m0   = DifficultyScoreCalculator.fatigueMultiplier(0.0);
        double m10  = DifficultyScoreCalculator.fatigueMultiplier(10.0);
        double m80  = DifficultyScoreCalculator.fatigueMultiplier(80.0);
        double m300 = DifficultyScoreCalculator.fatigueMultiplier(300.0);

        assertTrue(m0 < m10);
        assertTrue(m10 < m80);
        assertTrue(m80 < m300);
    }

    @Test
    public void ordering_steeperAndLongerClimbScoresHigher_atSameRoutePosition() {
        // Same route position (50 km in) for both, so fatigue affects them identically —
        // the ranking must come purely from the climb's own metrics.
        double gentleShort = DifficultyScoreCalculator.score(150, 0.035, 50.0); // 150m gain, 3.5%
        double steepLong   = DifficultyScoreCalculator.score(600, 0.09, 50.0);  // 600m gain, 9%

        assertTrue(steepLong > gentleShort);
    }

    @Test
    public void ordering_steeperClimbScoresHigher_thanGentlerClimbWithSameElevationGain() {
        double gentle = DifficultyScoreCalculator.score(400, 0.04, 20.0);
        double steep  = DifficultyScoreCalculator.score(400, 0.10, 20.0);

        assertTrue("same elevation gain, steeper gradient should score higher", steep > gentle);
    }

    @Test
    public void score_usingClimbOverload_matchesPrimitiveOverload() {
        Climb climb = Climb.builder()
                .startDistance(120_000)
                .endDistance(121_200)
                .length(1200)
                .elevationGain(90)
                .avgGradient(0.075)
                .build();

        double viaClimb = DifficultyScoreCalculator.score(climb, 120.0);
        double viaPrimitives = DifficultyScoreCalculator.score(90, 0.075, 120.0);

        assertEquals(viaPrimitives, viaClimb, 1e-9);
    }

    @Test
    public void score_isNonNegative_forZeroInputs() {
        assertEquals(0.0, DifficultyScoreCalculator.score(0, 0.0, 0.0), 1e-9);
    }
}
