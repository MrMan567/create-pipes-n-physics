package de.devin.pipesnphysics.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValveCharacteristicTest {
    private static final double EPS = 1e-9;

    /** Every curve must span the full range: fully shut passes nothing, fully open passes everything. */
    @Test
    void everyCurveHitsZeroShutAndOneOpen() {
        for (ValveCharacteristic c : ValveCharacteristic.values()) {
            assertEquals(0.0, c.factor(0.0), EPS, c + " must pass nothing at 0 degrees");
            assertEquals(1.0, c.factor(1.0), EPS, c + " must pass everything at 90 degrees");
        }
    }

    /** A larger opening never passes less flow — a valve you crank further open cannot flow less. */
    @Test
    void everyCurveIsMonotonic() {
        for (ValveCharacteristic c : ValveCharacteristic.values()) {
            double prev = -1;
            for (int i = 0; i <= 100; i++) {
                double f = c.factor(i / 100.0);
                assertTrue(f >= prev - EPS, c + " dipped at open=" + (i / 100.0));
                assertTrue(f >= -EPS && f <= 1 + EPS, c + " left [0,1] at open=" + (i / 100.0));
                prev = f;
            }
        }
    }

    /** LINEAR is the plain angle fraction — the default must stay byte-identical to the old angle/90. */
    @Test
    void linearIsTheAngleFraction() {
        assertEquals(0.25, ValveCharacteristic.LINEAR.factor(0.25), EPS);
        assertEquals(0.50, ValveCharacteristic.LINEAR.factor(0.50), EPS);
    }

    /** The nonlinear curves genuinely differ from linear at half-open, in the expected directions. */
    @Test
    void curvesBendTheRightWay() {
        double half = 0.5;
        // Quick-opening reaches most flow early → passes MORE than linear at half.
        assertTrue(ValveCharacteristic.QUICK_OPENING.factor(half) > half + 0.05,
                "quick-opening should be well above 50% at half-open");
        // Equal-percentage and a ball valve are slow to open → pass LESS than linear at half.
        assertTrue(ValveCharacteristic.EQUAL_PERCENTAGE.factor(half) < half - 0.05,
                "equal-percentage should be well below 50% at half-open");
        assertTrue(ValveCharacteristic.BALL_VALVE.factor(half) < half - 0.05,
                "a ball valve should be well below 50% at half-open");
    }
}
