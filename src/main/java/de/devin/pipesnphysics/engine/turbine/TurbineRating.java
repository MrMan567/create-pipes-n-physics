package de.devin.pipesnphysics.engine.turbine;

import de.devin.pipesnphysics.PipesNPhysicsConfig;

/**
 * What a turbine is rated for — the one place the numbers live, shared by the solve, the block
 * entity, the goggle, and /pipegraph so none of them can disagree about the same machine.
 *
 * Every quantity derives from ONE tier ({@code TURBINE_RPM}), which is deliberately a config
 * constant and never a function of the flow. Create destroys a generator whose speed flickers or
 * flips sign ({@code RotationPropagator}: +5 flicker per change, decay 1/tick, break at 128), and
 * the fixed tier is also what bounds the power a turbine can win back out of a pumped loop: the
 * head it takes and the flow it swallows are both capped by the rating, so feeding it harder
 * just backs the line up.
 */
public final class TurbineRating {
    private TurbineRating() {}

    /** The fixed speed a generating turbine turns at, in RPM. */
    public static double ratedRpm() {
        return PipesNPhysicsConfig.TURBINE_RPM.get();
    }

    /** Blocks of head a turbine takes out of the line — the fall it needs before it turns. */
    public static double ratedHead() {
        return ratedRpm() * PipesNPhysicsConfig.TURBINE_HEAD_PER_RPM.get();
    }

    /** The most a turbine passes per tick, in mB — its runner's swallowing capacity. */
    public static double swallowMb() {
        return ratedRpm() * PipesNPhysicsConfig.TURBINE_FLOW_PER_RPM.get();
    }

    /**
     * The turbine's internal conductance, the dual of the pump's: flow per block of head, so the
     * branch's free-flow throughput caps at {@link #swallowMb()}.
     */
    public static double internalConductance() {
        double headPerRpm = PipesNPhysicsConfig.TURBINE_HEAD_PER_RPM.get();
        return PipesNPhysicsConfig.TURBINE_FLOW_PER_RPM.get() / headPerRpm;
    }

    /**
     * Stress units a turbine passing {@code flowMb} per tick produces: rated head x flow, with no
     * ceiling of its own — a bigger fall drives more water through and earns more, which is the
     * point of building the drop.
     *
     * A pumped LOOP still cannot win, and not because of a clamp: the turbine only ever converts
     * its RATED head, never the head the pump paid for, so its output is linear in throughput
     * exactly as the pump's cost is linear in RPM. A pump at R RPM costs {@code impact(4) x R} and
     * can push at most {@code R x pumpFlowPerRpm}, against which the turbine returns
     * {@code suPerPower x ratedHead x} that — half of it at the defaults, at EVERY RPM. The
     * break-even knob is {@code TURBINE_SU_PER_POWER} (2.0 at stock numbers), not the flow.
     * What bounds a single turbine in practice is the engine: its own internal conductance, the
     * per-endpoint cap, and a cell's per-tick volume put a real rig near 500 SU.
     */
    public static double stressUnits(int flowMb) {
        return PipesNPhysicsConfig.TURBINE_SU_PER_POWER.get() * ratedHead() * Math.max(0, flowMb);
    }

    /**
     * The same figure as Create wants it: capacity PER RPM, which {@code KineticNetwork} multiplies
     * back by the generated speed ({@code getActualCapacityOf}).
     */
    public static float capacityPerRpm(int flowMb) {
        double rpm = ratedRpm();
        if (rpm <= 0) return 0;
        return (float) (stressUnits(flowMb) / rpm);
    }
}
