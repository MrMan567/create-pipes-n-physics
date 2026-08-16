package de.devin.pipesnphysics.engine.valve;

import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;

/**
 * Exposed by Create's fluid valve so the solver can read its scroll-set opening, and a Valve
 * Handle on its shaft can crank it. The shaft still turns the valve; the angle is its position.
 */
public interface ValveThrottle {
    /**
     * Configured opening as a 0..1 factor on the run's conductance; 1 is fully open. The
     * {@code VALVE_CHARACTERISTIC} curve is already applied to the raw angle here — callers
     * multiply the factor in as-is and must not curve it again.
     */
    float pipesnphysics$valveThrottle();

    /** Wind the opening by {@code delta} degrees (clamped 0–90); a Valve Handle calls this. */
    void pipesnphysics$adjustThrottle(int delta);

    /**
     * Which way this valve's own shaft is turning right now: +1 opening, -1 closing, 0 while
     * nothing turns it. Both inputs (raw shaft rotation and a Valve Handle's intent) take their
     * direction from here, so the two can never disagree, and every reversal in the drivetrain
     * (a gearshift, a gearbox output) reverses the valve exactly as it does any other kinetic block.
     */
    int pipesnphysics$openingSign();

    /**
     * The single world direction this valve lets fluid flow, or null when it passes BOTH ways
     * (the default, and always null with the one-way feature off). A non-null direction makes
     * the valve a CHECK VALVE: {@code GraphBuilder} forces its cell to a graph node and the
     * solve/settle wall the reverse direction.
     */
    @Nullable
    Direction pipesnphysics$oneWayFlow();
}
