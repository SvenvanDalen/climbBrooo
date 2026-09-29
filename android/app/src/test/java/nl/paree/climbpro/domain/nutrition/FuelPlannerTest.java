package nl.paree.climbpro.domain.nutrition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.time.Instant;

import nl.paree.climbpro.domain.weather.HourlyForecast;

public class FuelPlannerTest {

    private static FuelPlanner.Input input(int seconds, int ascentM, double tempC) {
        return new FuelPlanner.Input(seconds, Double.NaN, ascentM, tempC, 75.0, 750);
    }

    @Test
    public void shortRide_noFoodNeeded_oneBottle() {
        FuelPlan p = FuelPlanner.plan(input(50 * 60, 100, 15.0));
        assertEquals(0, p.carbsPerHour);
        assertEquals(0, p.totalCarbsGrams);
        assertEquals(0, p.gels);
        assertEquals(0, p.bars);
        assertEquals(1, p.bottles);
        assertEquals(0, p.refills);
    }

    @Test
    public void carbRateGrowsWithDuration() {
        assertEquals(45, FuelPlanner.plan(input(2 * 3600, 200, 15.0)).carbsPerHour);
        assertEquals(60, FuelPlanner.plan(input(3 * 3600, 300, 15.0)).carbsPerHour);
        assertEquals(75, FuelPlanner.plan(input(5 * 3600, 500, 15.0)).carbsPerHour);
    }

    @Test
    public void lotsOfClimbingAddsCarbs_cappedAt90() {
        // 3 h with 1800 hm = 600 hm/h -> +15 g/h on top of 60.
        assertEquals(75, FuelPlanner.plan(input(3 * 3600, 1800, 15.0)).carbsPerHour);
        // 6 h with 3600 hm: 75 + 15 = 90 (the cap).
        assertEquals(90, FuelPlanner.plan(input(6 * 3600, 3600, 15.0)).carbsPerHour);
    }

    @Test
    public void totalCarbsSplitOverBarsAndGels() {
        // 4 h at 60 g/h = 240 g: half (120 g) as 40 g bars = 3, rest 120 g as 25 g gels = 5.
        FuelPlan p = FuelPlanner.plan(input(4 * 3600, 400, 15.0));
        assertEquals(60, p.carbsPerHour);
        assertEquals(240, p.totalCarbsGrams);
        assertEquals(3, p.bars);
        assertEquals(5, p.gels);
        assertTrue(p.bars * FuelPlanner.BAR_CARBS_G + p.gels * FuelPlanner.GEL_CARBS_G
                >= p.totalCarbsGrams);
    }

    @Test
    public void hotterMeansMoreFluid() {
        FuelPlan cool = FuelPlanner.plan(input(3 * 3600, 300, 8.0));
        FuelPlan mild = FuelPlanner.plan(input(3 * 3600, 300, 20.0));
        FuelPlan hot = FuelPlanner.plan(input(3 * 3600, 300, 30.0));
        assertEquals(400, cool.fluidMlPerHour);
        assertEquals(600, mild.fluidMlPerHour);
        assertEquals(900, hot.fluidMlPerHour);
        assertTrue(hot.totalFluidMl > mild.totalFluidMl);
        assertTrue(mild.totalFluidMl > cool.totalFluidMl);
    }

    @Test
    public void fluidRateCappedWhenExtremelyHot() {
        assertEquals(FuelPlanner.MAX_FLUID_ML_PER_HOUR,
                FuelPlanner.plan(input(3 * 3600, 300, 45.0)).fluidMlPerHour);
    }

    @Test
    public void bottlesAndRefills() {
        // 4 h at 30 °C: 900 ml/h -> 3600 ml -> 5 bottles of 750 ml, 2 cages -> 3 refills.
        FuelPlan p = FuelPlanner.plan(input(4 * 3600, 400, 30.0));
        assertEquals(3600, p.totalFluidMl);
        assertEquals(5, p.bottles);
        assertEquals(3, p.refills);
    }

    @Test
    public void smallerBottlesMeanMoreOfThem() {
        FuelPlan p = FuelPlanner.plan(
                new FuelPlanner.Input(2 * 3600, Double.NaN, 200, 20.0, 75.0, 500));
        assertEquals(1200, p.totalFluidMl);
        assertEquals(3, p.bottles);
        assertEquals(1, p.refills);
    }

    @Test
    public void heavierRiderDrinksMore_clamped() {
        FuelPlan light = FuelPlanner.plan(
                new FuelPlanner.Input(3 * 3600, Double.NaN, 300, 20.0, 60.0, 750));
        FuelPlan heavy = FuelPlanner.plan(
                new FuelPlanner.Input(3 * 3600, Double.NaN, 300, 20.0, 150.0, 750));
        assertEquals(480, light.fluidMlPerHour);   // 600 * 60/75
        assertEquals(750, heavy.fluidMlPerHour);   // 600 * 1.25 (clamped)
    }

    @Test
    public void caloriesFromWorkWhenKnown_elseFromDurationAndWeight() {
        FuelPlan fromWork = FuelPlanner.plan(
                new FuelPlanner.Input(3 * 3600, 2000.0, 300, 20.0, 75.0, 750));
        assertEquals(2000, fromWork.kcal);
        FuelPlan estimated = FuelPlanner.plan(input(3 * 3600, 300, 20.0));
        assertEquals(1800, estimated.kcal); // 8 kcal/kg/h * 75 kg * 3 h
    }

    @Test
    public void electrolytesAdvisedWhenHotOrLong() {
        assertFalse(FuelPlanner.plan(input(2 * 3600, 200, 18.0)).electrolytesAdvised);
        assertTrue(FuelPlanner.plan(input(2 * 3600, 200, 26.0)).electrolytesAdvised);
        assertTrue(FuelPlanner.plan(input(4 * 3600, 200, 12.0)).electrolytesAdvised);
    }

    @Test
    public void missingTemperatureUsesDefault() {
        FuelPlan p = FuelPlanner.plan(input(3 * 3600, 300, Double.NaN));
        assertEquals(FuelPlanner.DEFAULT_TEMPERATURE_C, p.temperatureC, 1e-9);
        assertTrue(p.temperatureAssumed);
    }

    @Test
    public void zeroDuration_emptyPlan() {
        FuelPlan p = FuelPlanner.plan(input(0, 0, 20.0));
        assertEquals(0, p.bottles);
        assertEquals(0, p.gels);
        assertEquals(0, p.kcal);
    }

    @Test
    public void averageTemperature_overRideWindow() throws IOException {
        HourlyForecast f = HourlyForecast.parse("{\"hourly\":{"
                + "\"time\":[\"2026-07-01T08:00\",\"2026-07-01T09:00\",\"2026-07-01T10:00\","
                + "\"2026-07-01T11:00\"],"
                + "\"temperature_2m\":[10,14,null,20]}}");
        // 08:30 for 2 h touches the 08, 09 and 10 hours; the null hour is skipped.
        assertEquals(12.0, FuelPlanner.averageTemperature(
                f, Instant.parse("2026-07-01T08:30:00Z"), 2 * 3600), 1e-9);
        // Entirely outside the forecast -> NaN.
        assertTrue(Double.isNaN(FuelPlanner.averageTemperature(
                f, Instant.parse("2026-07-02T08:00:00Z"), 3600)));
    }
}
