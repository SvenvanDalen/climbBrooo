package nl.paree.climbpro.domain.hydration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class SweatLossCalculatorTest {

    private static final double EPS = 1e-9;

    @Test
    public void sweatLoss_isWeightDropPlusFluidDrunk() {
        // 75.0 -> 74.0 kg over 2 h while drinking 1 L: 2 L sweat, 1 L/h.
        SweatLossCalculator.Result r = SweatLossCalculator.calculate(75.0, 74.0, 1000, 120);
        assertEquals(2.0, r.sweatLossL, EPS);
        assertEquals(1.0, r.sweatRateLPerH, EPS);
        assertEquals(1.0 / 75.0 * 100.0, r.massLossPct, EPS);
        assertEquals(120, r.durationMin);
    }

    @Test
    public void validate_rejectsOutOfRangeInput() {
        assertEquals(SweatLossCalculator.Invalid.WEIGHT_OUT_OF_RANGE,
                SweatLossCalculator.validate(20.0, 19.5, 500, 60));
        assertEquals(SweatLossCalculator.Invalid.WEIGHT_OUT_OF_RANGE,
                SweatLossCalculator.validate(75.0, 300.0, 500, 60));
        assertEquals(SweatLossCalculator.Invalid.WEIGHT_OUT_OF_RANGE,
                SweatLossCalculator.validate(Double.NaN, 74.0, 500, 60));
        assertEquals(SweatLossCalculator.Invalid.DURATION_OUT_OF_RANGE,
                SweatLossCalculator.validate(75.0, 74.5, 500, 5));
        assertEquals(SweatLossCalculator.Invalid.DURATION_OUT_OF_RANGE,
                SweatLossCalculator.validate(75.0, 74.5, 500, 25 * 60));
        assertEquals(SweatLossCalculator.Invalid.FLUID_OUT_OF_RANGE,
                SweatLossCalculator.validate(75.0, 74.5, -1, 60));
        assertEquals(SweatLossCalculator.Invalid.FLUID_OUT_OF_RANGE,
                SweatLossCalculator.validate(75.0, 74.5, 25_000, 60));
        assertNull(SweatLossCalculator.validate(75.0, 74.5, 500, 60));
    }

    @Test
    public void validate_rejectsImplausibleWeightChange() {
        // More than 10 % of body weight lost in one ride: almost certainly a typo.
        assertEquals(SweatLossCalculator.Invalid.IMPLAUSIBLE_CHANGE,
                SweatLossCalculator.validate(75.0, 66.0, 500, 120));
        // Gaining more than was drunk would mean negative sweat.
        assertEquals(SweatLossCalculator.Invalid.IMPLAUSIBLE_CHANGE,
                SweatLossCalculator.validate(75.0, 76.0, 500, 120));
    }

    @Test(expected = IllegalArgumentException.class)
    public void calculate_throwsOnInvalidInput() {
        SweatLossCalculator.calculate(75.0, 60.0, 0, 60);
    }

    @Test
    public void status_bandsFollowBodyMassChange() {
        assertEquals(SweatLossCalculator.Status.GAINED,
                SweatLossCalculator.calculate(75.0, 75.4, 1000, 120).status);
        assertEquals(SweatLossCalculator.Status.GOOD,
                SweatLossCalculator.calculate(75.0, 74.5, 1000, 120).status);  // 0.67 %
        assertEquals(SweatLossCalculator.Status.MODERATE,
                SweatLossCalculator.calculate(75.0, 74.0, 1000, 120).status);  // 1.33 %
        assertEquals(SweatLossCalculator.Status.HIGH,
                SweatLossCalculator.calculate(75.0, 73.5, 1000, 120).status);  // 2.0 %
    }

    @Test
    public void advice_replacesMostOfTheSweatRate_roundedTo50Ml() {
        // 1.0 L/h -> 80 % = 800 ml/h.
        assertEquals(800, SweatLossCalculator.advisedMlPerHour(1.0));
        // 0.6 L/h -> 480 -> rounded to 500.
        assertEquals(500, SweatLossCalculator.advisedMlPerHour(0.6));
        assertEquals(0, SweatLossCalculator.advisedMlPerHour(0.0));
        assertEquals(0, SweatLossCalculator.advisedMlPerHour(-1.0));
    }

    @Test
    public void advice_isCappedAtWhatTheGutAbsorbs() {
        assertEquals(SweatLossCalculator.MAX_ADVISED_ML_PER_H,
                SweatLossCalculator.advisedMlPerHour(2.0));
        assertTrue(SweatLossCalculator.exceedsAbsorption(2.0));
        assertFalse(SweatLossCalculator.exceedsAbsorption(1.0));
    }

    @Test
    public void bottlesPerHour_roundsUpToHalfBottles() {
        assertEquals(1.0, SweatLossCalculator.bottlesPerHour(500, 500), EPS);
        assertEquals(1.5, SweatLossCalculator.bottlesPerHour(600, 500), EPS);
        assertEquals(0.5, SweatLossCalculator.bottlesPerHour(100, 500), EPS);
        assertEquals(0.0, SweatLossCalculator.bottlesPerHour(0, 500), EPS);
    }

    @Test
    public void summarize_isDurationWeighted() {
        // 1 h at 0.5 L/h and 3 h at 1.0 L/h -> 3.5 L over 4 h = 0.875 L/h.
        List<SweatLossCalculator.Result> results = Arrays.asList(
                SweatLossCalculator.calculate(70.0, 69.5, 0, 60),
                SweatLossCalculator.calculate(70.0, 69.0, 2000, 180));
        SweatLossCalculator.Summary s = SweatLossCalculator.summarize(results);
        assertEquals(2, s.count);
        assertEquals(0.875, s.avgSweatRateLPerH, EPS);
        assertEquals(0.5, s.minSweatRateLPerH, EPS);
        assertEquals(1.0, s.maxSweatRateLPerH, EPS);
        assertEquals(700, s.advisedMlPerHour);
    }

    @Test
    public void summarize_emptyOrNull_hasNoAdvice() {
        assertNull(SweatLossCalculator.summarize(Collections.emptyList()));
        assertNull(SweatLossCalculator.summarize(null));
        List<SweatLossCalculator.Result> onlyNulls = new ArrayList<>();
        onlyNulls.add(null);
        assertNull(SweatLossCalculator.summarize(onlyNulls));
    }
}
