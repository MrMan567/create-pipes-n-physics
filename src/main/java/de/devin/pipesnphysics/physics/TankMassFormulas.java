package de.devin.pipesnphysics.physics;

/**
 * Pure math for computing fluid tank mass and gravitational impulses.
 * Used by the Sable dynamic tank mass feature.
 */
public final class TankMassFormulas {

    private TankMassFormulas() {}

    /**
     * Compute fluid mass in kilograms from fluid amount, density, and config.
     *
     * @param fluidAmountMb  fluid amount in millibuckets
     * @param fluidDensity   fluid density (water = 1000, lava = 3000)
     * @param massPerBucket  config: kg per bucket of water-density fluid
     * @return mass in kg, scaled by density ratio
     */
    public static double fluidMassKg(int fluidAmountMb, int fluidDensity, double massPerBucket) {
        double densityRatio = fluidDensity / 1000.0;
        double buckets = fluidAmountMb / 1000.0;
        return buckets * massPerBucket * densityRatio;
    }

    /**
     * Net vertical mass contribution of a tank's fluid, in kg: positive = downward weight (a
     * liquid), negative = upward lift (a lighter-than-air gas acting like a balloon).
     *
     * <p>Lift is density-INDEPENDENT — a gas cell lifts by its fill volume, not by its tiny
     * sub-zero density. Scaling buoyancy by {@code density/1000} floors it at ~1% of gravity, the
     * exact regression CLAUDE.md §4 warns against for the gas head model, so a gas would just weigh
     * a hair less than nothing instead of lifting.
     *
     * @param lighterThanAir whether the fluid is lighter than air AND buoyancy is enabled
     * @param liftPerBucket  config: kg of lift per bucket of gas
     */
    public static double netMassKg(int fluidAmountMb, int fluidDensity, boolean lighterThanAir,
                                   double massPerBucket, double liftPerBucket) {
        if (lighterThanAir) {
            double buckets = fluidAmountMb / 1000.0;
            return -buckets * liftPerBucket;
        }
        return fluidMassKg(fluidAmountMb, fluidDensity, massPerBucket);
    }

    /**
     * Compute fill fraction from fluid amount and tank capacity.
     *
     * @return 0.0–1.0 fill level, or 0 if capacity is zero
     */
    public static double fillFraction(int fluidAmount, int capacity) {
        return capacity > 0 ? (double) fluidAmount / capacity : 0.0;
    }
}
