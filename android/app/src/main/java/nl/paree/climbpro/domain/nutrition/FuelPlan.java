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

    private FuelPlan(Builder b) {
        this.durationSeconds = b.durationSeconds;
        this.kcal = b.kcal;
        this.carbsPerHour = b.carbsPerHour;
        this.totalCarbsGrams = b.totalCarbsGrams;
        this.gels = b.gels;
        this.bars = b.bars;
        this.fluidMlPerHour = b.fluidMlPerHour;
        this.totalFluidMl = b.totalFluidMl;
        this.bottleMl = b.bottleMl;
        this.bottles = b.bottles;
        this.refills = b.refills;
        this.electrolytesAdvised = b.electrolytesAdvised;
        this.temperatureC = b.temperatureC;
        this.temperatureAssumed = b.temperatureAssumed;
    }

    /** Builds a {@link FuelPlan}; every value defaults to Java's default for its type. */
    public static final class Builder {
        private int durationSeconds;
        private int kcal;
        private int carbsPerHour;
        private int totalCarbsGrams;
        private int gels;
        private int bars;
        private int fluidMlPerHour;
        private int totalFluidMl;
        private int bottleMl;
        private int bottles;
        private int refills;
        private boolean electrolytesAdvised;
        private double temperatureC;
        private boolean temperatureAssumed;

        public Builder durationSeconds(int durationSeconds) {
            this.durationSeconds = durationSeconds;
            return this;
        }

        public Builder kcal(int kcal) {
            this.kcal = kcal;
            return this;
        }

        public Builder carbsPerHour(int carbsPerHour) {
            this.carbsPerHour = carbsPerHour;
            return this;
        }

        public Builder totalCarbsGrams(int totalCarbsGrams) {
            this.totalCarbsGrams = totalCarbsGrams;
            return this;
        }

        public Builder gels(int gels) {
            this.gels = gels;
            return this;
        }

        public Builder bars(int bars) {
            this.bars = bars;
            return this;
        }

        public Builder fluidMlPerHour(int fluidMlPerHour) {
            this.fluidMlPerHour = fluidMlPerHour;
            return this;
        }

        public Builder totalFluidMl(int totalFluidMl) {
            this.totalFluidMl = totalFluidMl;
            return this;
        }

        public Builder bottleMl(int bottleMl) {
            this.bottleMl = bottleMl;
            return this;
        }

        public Builder bottles(int bottles) {
            this.bottles = bottles;
            return this;
        }

        public Builder refills(int refills) {
            this.refills = refills;
            return this;
        }

        public Builder electrolytesAdvised(boolean electrolytesAdvised) {
            this.electrolytesAdvised = electrolytesAdvised;
            return this;
        }

        public Builder temperatureC(double temperatureC) {
            this.temperatureC = temperatureC;
            return this;
        }

        public Builder temperatureAssumed(boolean temperatureAssumed) {
            this.temperatureAssumed = temperatureAssumed;
            return this;
        }

        public FuelPlan build() {
            return new FuelPlan(this);
        }
    }
}
