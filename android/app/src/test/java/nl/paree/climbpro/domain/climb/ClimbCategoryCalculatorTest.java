package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ClimbCategoryCalculatorTest {

    @Test
    public void score_isLengthTimesGradientPercent() {
        // 1000 m * 8% = 8000
        assertEquals(8000.0, ClimbCategoryCalculator.score(1000, 0.08), 1e-9);
    }

    @Test
    public void score_isNonNegative_forZeroInputs() {
        assertEquals(0.0, ClimbCategoryCalculator.score(0, 0.0), 1e-9);
    }

    @Test
    public void score_clampsNegativeInputsToZero() {
        // defensive: no caller should ever pass negative length/gradient, but the
        // formula must not produce a negative (and therefore mis-bucketed) score.
        assertEquals(0.0, ClimbCategoryCalculator.score(-100, -0.05), 1e-9);
    }

    @Test
    public void categorize_belowCat4Threshold_isUncategorized() {
        // A climb right at the domain-rule minimums (800 m / 3%): score = 2400,
        // well short of the 8000 CAT_4 cutoff.
        assertEquals(ClimbCategory.UNCATEGORIZED,
                ClimbCategoryCalculator.categorize(800, 0.03));
    }

    @Test
    public void categorize_justBelowCat4Threshold_isUncategorized() {
        // score = 999 * 8% = 7992, just under 8000.
        assertEquals(ClimbCategory.UNCATEGORIZED,
                ClimbCategoryCalculator.categorize(999, 0.08));
    }

    @Test
    public void categorize_atCat4Threshold_isCat4() {
        // score = 1000 * 8% = 8000, exactly the CAT_4 boundary (inclusive).
        assertEquals(ClimbCategory.CAT_4,
                ClimbCategoryCalculator.categorize(1000, 0.08));
    }

    @Test
    public void categorize_atCat3Threshold_isCat3() {
        // score = 2000 * 8% = 16000, exactly the CAT_3 boundary.
        assertEquals(ClimbCategory.CAT_3,
                ClimbCategoryCalculator.categorize(2000, 0.08));
    }

    @Test
    public void categorize_justBelowCat3Threshold_isCat4() {
        // score = 1999 * 8% = 15992, just under 16000 stays CAT_4.
        assertEquals(ClimbCategory.CAT_4,
                ClimbCategoryCalculator.categorize(1999, 0.08));
    }

    @Test
    public void categorize_atCat2Threshold_isCat2() {
        // score = 4000 * 8% = 32000, exactly the CAT_2 boundary.
        assertEquals(ClimbCategory.CAT_2,
                ClimbCategoryCalculator.categorize(4000, 0.08));
    }

    @Test
    public void categorize_atCat1Threshold_isCat1() {
        // score = 8000 * 8% = 64000, exactly the CAT_1 boundary.
        assertEquals(ClimbCategory.CAT_1,
                ClimbCategoryCalculator.categorize(8000, 0.08));
    }

    @Test
    public void categorize_atHcThreshold_isHc() {
        // score = 10000 * 8% = 80000, exactly the HC boundary.
        assertEquals(ClimbCategory.HC,
                ClimbCategoryCalculator.categorize(10000, 0.08));
    }

    @Test
    public void categorize_wellAboveHcThreshold_isHc() {
        // A monster climb: Alpe d'Huez-scale (~13.8 km at ~8.1%): score ~ 111780.
        assertEquals(ClimbCategory.HC,
                ClimbCategoryCalculator.categorize(13_800, 0.081));
    }

    @Test
    public void categorize_usingClimbOverload_matchesPrimitiveOverload() {
        Climb climb = Climb.builder()
                .startDistance(0)
                .endDistance(2000)
                .length(2000)
                .elevationGain(160)
                .avgGradient(0.08)
                .build();

        assertEquals(ClimbCategoryCalculator.categorize(2000, 0.08),
                ClimbCategoryCalculator.categorize(climb));
    }

    @Test
    public void categorize_veryShortGentleClimb_isUncategorized() {
        // Barely qualifies as a "climb" at all under domain rules (800m / 3%).
        assertEquals(ClimbCategory.UNCATEGORIZED,
                ClimbCategoryCalculator.categorize(800, 0.031));
    }
}
