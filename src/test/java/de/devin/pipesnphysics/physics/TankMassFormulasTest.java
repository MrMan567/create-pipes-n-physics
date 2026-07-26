package de.devin.pipesnphysics.physics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TankMassFormulasTest {
    private static final double EPS = 1e-9;
    private static final double MASS_PER_BUCKET = 0.1;
    private static final double LIFT_PER_BUCKET = 0.1;

    /** A liquid weighs down, scaled by its density — 2 buckets of water (density 1000). */
    @Test
    void liquidReadsPositiveWeight() {
        double net = TankMassFormulas.netMassKg(2000, 1000, false, MASS_PER_BUCKET, LIFT_PER_BUCKET);
        assertEquals(0.2, net, EPS);
        assertTrue(net > 0, "a liquid must weigh down");
    }

    /** A lighter-than-air gas reads as upward lift — negative net, one lift-per-bucket per bucket. */
    @Test
    void buoyantGasReadsNegativeLift() {
        double net = TankMassFormulas.netMassKg(2000, -1, true, MASS_PER_BUCKET, LIFT_PER_BUCKET);
        assertEquals(-0.2, net, EPS);
        assertTrue(net < 0, "a buoyant gas must lift");
    }

    /**
     * Lift is density-INDEPENDENT: a gas at density 0 and one at density -1 lift the same. This is
     * the regression guard for scaling buoyancy by density/1000 (CLAUDE.md §4), which would floor
     * the density-0 gas at zero lift.
     */
    @Test
    void liftIgnoresTheGasDensity() {
        double atZero = TankMassFormulas.netMassKg(4000, 0, true, MASS_PER_BUCKET, LIFT_PER_BUCKET);
        double atNegOne = TankMassFormulas.netMassKg(4000, -1, true, MASS_PER_BUCKET, LIFT_PER_BUCKET);
        assertEquals(atZero, atNegOne, EPS);
        assertEquals(-0.4, atZero, EPS);
    }

    /** Buoyancy disabled (lighterThanAir=false) falls back to the density-scaled mass — here ~0. */
    @Test
    void buoyancyOffFallsBackToDensityMass() {
        double net = TankMassFormulas.netMassKg(4000, 0, false, MASS_PER_BUCKET, LIFT_PER_BUCKET);
        assertEquals(0.0, net, EPS);
    }
}
