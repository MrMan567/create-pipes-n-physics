package de.devin.pipesnphysics.engine.turbine;

/**
 * Exposed by Create's Mechanical Pump so the engine can tell it how much fluid actually fell
 * through it. The valve's {@code ValveThrottle} reads a player setting INTO the solve; this runs
 * the other way — the solve's result drives the machine's rotation.
 *
 * The block entity owns the debounce: it decides when that flow is sustained enough to start
 * generating and quiet enough to stop, because Create breaks a generator that changes its mind
 * too often.
 */
public interface HydroTurbine {
    /** Whether this pump is dialed to TURBINE (and the feature is enabled). */
    boolean pipesnphysics$isTurbine();

    /**
     * One sample of the fluid that really moved through this turbine this tick, in mB. The engine
     * calls it once per solve; going quiet is itself the signal to spin down, so a sleeping
     * network needs no separate stop call.
     */
    void pipesnphysics$driveTurbine(int flowMb);

    /** The stress units it is currently producing — 0 when it is not turning. */
    double pipesnphysics$turbineStress();
}
