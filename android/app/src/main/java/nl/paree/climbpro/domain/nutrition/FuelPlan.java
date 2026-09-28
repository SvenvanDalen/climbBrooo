package nl.paree.climbpro.domain.nutrition;

/** Result of {@link FuelPlanner#plan}: what to eat and drink on one ride (issue #185). */
public final class FuelPlan {

    public final int durationSeconds;
    public final int kcal;
    public final int carbsPerHour;
    public final int totalCarbsGrams;
    public final int gels;
    public final int bars;
    public final int fluidMlPerHour;
    public final int totalFluidMl;
    public final int bottleMl;
    public final int bottles;
    /** Bottles beyond what fits in the cages, i.e. refill stops. */
    public final int refills;
    public final boolean electrolytesAdvised;
    /** Temperature the fluid advice is based on. */
    public final double temperatureC;
    /** True when no temperature was given and the default was used. */
    public final boolean temperatureAssumed;

    public FuelPlan(int durationSeconds, int kcal, int carbsPerHour, int totalCarbsGrams,
                    int gels, int bars, int fluidMlPerHour, int totalFluidMl, int bottleMl,
                    int bottles, int refills, boolean electrolytesAdvised,
                    double temperatureC, boolean temperatureAssumed) {
        this.durationSeconds = durationSeconds;
        this.kcal = kcal;
        this.carbsPerHour = carbsPerHour;
        this.totalCarbsGrams = totalCarbsGrams;
        this.gels = gels;
        this.bars = bars;
        this.fluidMlPerHour = fluidMlPerHour;
        this.totalFluidMl = totalFluidMl;
        this.bottleMl = bottleMl;
        this.bottles = bottles;
        this.refills = refills;
        this.electrolytesAdvised = electrolytesAdvised;
        this.temperatureC = temperatureC;
        this.temperatureAssumed = temperatureAssumed;
    }
}
