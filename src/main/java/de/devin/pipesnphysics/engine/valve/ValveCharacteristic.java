package de.devin.pipesnphysics.engine.valve;

/**
 * Maps a fluid valve's opening fraction (its 0-90 degree angle as 0..1) to the share of the run's
 * conductance it passes. LINEAR is the plain angle fraction; the others reshape it into a real
 * rotary-valve characteristic — the nonlinear angle-to-flow-area relationship of actual hardware.
 * The engine models pipes as linear resistors, so this only reshapes the knob's feel; it is a
 * gameplay dial, not a fidelity fix. Every curve hits 0 at fully shut and 1 at fully open.
 */
public enum ValveCharacteristic {
    /** Flow share equals the angle: 45 degrees passes half. Predictable — the default. */
    LINEAR {
        @Override public double factor(double open) { return open; }
    },
    /** Opens fast, then tapers — most flow is reached in the first part of the turn (~sqrt). */
    QUICK_OPENING {
        @Override public double factor(double open) { return Math.sqrt(open); }
    },
    /** Opens slow, then rushes — equal steps of angle multiply the flow (an equal-percentage valve). */
    EQUAL_PERCENTAGE {
        @Override public double factor(double open) {
            return (Math.pow(RANGEABILITY, open) - 1) / (RANGEABILITY - 1);
        }
    },
    /** The lens-shaped overlap of a ball valve's bore as it rotates — very restrictive until near open. */
    BALL_VALVE {
        @Override public double factor(double open) {
            // Two identical bore circles whose centres separate as the ball turns; the open throat is
            // their intersection area, normalized by a full circle. s is the centre separation over the
            // diameter: 1 (no overlap) when shut, 0 (full overlap) when open.
            double s = Math.cos(open * Math.PI / 2);
            return (2 / Math.PI) * (Math.acos(s) - s * Math.sqrt(Math.max(0, 1 - s * s)));
        }
    };

    /** Rangeability of the modelled equal-percentage valve (max:min controllable flow ratio). */
    private static final double RANGEABILITY = 50;

    /** The conductance share (0..1) for an opening fraction {@code open} (angle/90, clamped 0..1). */
    public abstract double factor(double open);
}
