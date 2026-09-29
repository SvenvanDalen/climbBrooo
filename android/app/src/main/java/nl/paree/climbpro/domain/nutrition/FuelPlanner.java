package nl.paree.climbpro.domain.nutrition;

import java.time.Instant;

import nl.paree.climbpro.domain.weather.HourlyForecast;

/**
 * Calorie and carbohydrate planner per route (issue #185). Pure.
 *
 * Turns a ride's duration, climbing and temperature into a concrete shopping list:
 * gels, bars and bottles. The rules are common sports-nutrition guidelines, kept
 * deliberately simple:
 * <ul>
 *   <li>Carbs: none under 75 min, 45 g/h up to 2.5 h, 60 g/h up to 4 h, 75 g/h beyond;
 *       +15 g/h when climbing at least {@value #HILLY_ASCENT_PER_HOUR} hm per hour; max 90 g/h.
 *       Half is taken as bars (solid food early), the rest as gels.</li>
 *   <li>Fluid: 400 ml/h at 10 °C or colder, rising linearly to 600 ml/h at 20 °C and
 *       900 ml/h at 30 °C (+30 ml/h per degree above), +100 ml/h on hilly rides, scaled by
 *       body weight relative to 75 kg (clamped), capped at {@value #MAX_FLUID_ML_PER_HOUR}.</li>
 *   <li>Calories: the ride's mechanical work in kJ ≈ kcal burned (≈ 24 % efficiency),
 *       or 8 kcal per kg per hour without a power model.</li>
 * </ul>
 */
public final class FuelPlanner {

    private FuelPlanner() {}

    public static final int GEL_CARBS_G = 25;
    public static final int BAR_CARBS_G = 40;
    public static final int BOTTLE_CAGES = 2;
    public static final int MAX_CARBS_PER_HOUR = 90;
    public static final int MAX_FLUID_ML_PER_HOUR = 1200;
    public static final double DEFAULT_TEMPERATURE_C = 18.0;
    public static final double DEFAULT_WEIGHT_KG = 75.0;

    static final int NO_FUEL_BELOW_SECONDS = 75 * 60;
    static final int HILLY_ASCENT_PER_HOUR = 500;
    static final int HILLY_EXTRA_CARBS = 15;
    static final int HILLY_EXTRA_FLUID_ML = 100;
    static final double KCAL_PER_KG_HOUR = 8.0;
    static final double ELECTROLYTE_TEMP_C = 25.0;
    static final int ELECTROLYTE_SECONDS = 3 * 3600;

    /** Everything the planner needs; temperatureC / workKj may be NaN when unknown. */
    public static final class Input {
        public final int durationSeconds;
        public final double workKj;
        public final int ascentMeters;
        public final double temperatureC;
        public final double bodyWeightKg;
        public final int bottleMl;

        public Input(int durationSeconds, double workKj, int ascentMeters, double temperatureC,
                     double bodyWeightKg, int bottleMl) {
            this.durationSeconds = durationSeconds;
            this.workKj = workKj;
            this.ascentMeters = ascentMeters;
            this.temperatureC = temperatureC;
            this.bodyWeightKg = bodyWeightKg;
            this.bottleMl = bottleMl;
        }
    }

    public static FuelPlan plan(Input in) {
        int seconds = Math.max(0, in.durationSeconds);
        double hours = seconds / 3600.0;
        boolean tempAssumed = Double.isNaN(in.temperatureC);
        double temp = tempAssumed ? DEFAULT_TEMPERATURE_C : in.temperatureC;
        double weight = in.bodyWeightKg > 0 ? in.bodyWeightKg : DEFAULT_WEIGHT_KG;
        int bottleMl = in.bottleMl > 0 ? in.bottleMl : 750;
        if (seconds == 0) {
            return new FuelPlan(0, 0, 0, 0, 0, 0, 0, 0, bottleMl, 0, 0, false, temp, tempAssumed);
        }
        boolean hilly = in.ascentMeters / hours >= HILLY_ASCENT_PER_HOUR;

        int kcal = !Double.isNaN(in.workKj) && in.workKj > 0
                ? (int) Math.round(in.workKj)
                : (int) Math.round(KCAL_PER_KG_HOUR * weight * hours);

        int carbsPerHour = carbsPerHour(seconds, hilly);
        int totalCarbs = (int) Math.round(carbsPerHour * hours);
        int bars = (totalCarbs / 2) / BAR_CARBS_G;
        int gels = (int) Math.ceil(Math.max(0, totalCarbs - bars * BAR_CARBS_G)
                / (double) GEL_CARBS_G);

        int fluidPerHour = fluidPerHour(temp, hilly, weight);
        int totalFluid = (int) (Math.round(fluidPerHour * hours / 50.0) * 50);
        int bottles = Math.max(1, (int) Math.ceil(totalFluid / (double) bottleMl));
        int refills = Math.max(0, bottles - BOTTLE_CAGES);

        boolean electrolytes = temp >= ELECTROLYTE_TEMP_C || seconds >= ELECTROLYTE_SECONDS;
        return new FuelPlan(seconds, kcal, carbsPerHour, totalCarbs, gels, bars,
                fluidPerHour, totalFluid, bottleMl, bottles, refills, electrolytes,
                temp, tempAssumed);
    }

    static int carbsPerHour(int seconds, boolean hilly) {
        if (seconds < NO_FUEL_BELOW_SECONDS) return 0;
        int base;
        if (seconds <= 150 * 60) base = 45;
        else if (seconds <= 240 * 60) base = 60;
        else base = 75;
        return Math.min(MAX_CARBS_PER_HOUR, base + (hilly ? HILLY_EXTRA_CARBS : 0));
    }

    static int fluidPerHour(double temp, boolean hilly, double weightKg) {
        double ml;
        if (temp <= 10) ml = 400;
        else if (temp <= 20) ml = 400 + 20 * (temp - 10);
        else ml = 600 + 30 * (temp - 20);
        if (hilly) ml += HILLY_EXTRA_FLUID_ML;
        double weightFactor = Math.max(0.8, Math.min(1.25, weightKg / DEFAULT_WEIGHT_KG));
        ml *= weightFactor;
        return (int) Math.min(MAX_FLUID_ML_PER_HOUR, Math.round(ml));
    }

    /**
     * Mean forecast temperature over the hours touched by the ride window, skipping
     * missing values; NaN when the forecast does not cover the window.
     */
    public static double averageTemperature(HourlyForecast f, Instant start, long durationSec) {
        if (f == null || start == null) return Double.NaN;
        Instant end = start.plusSeconds(Math.max(1, durationSec));
        double sum = 0;
        int n = 0;
        for (int i = 0; i < f.times.length; i++) {
            Instant h = f.times[i];
            if (!h.plusSeconds(3600).isAfter(start) || !h.isBefore(end)) continue;
            if (Double.isNaN(f.temperature[i])) continue;
            sum += f.temperature[i];
            n++;
        }
        return n == 0 ? Double.NaN : sum / n;
    }
}
