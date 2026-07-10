package de.devin.pipesnphysics.api;

/**
 * The role a fluid-handler block plays in the pipe network — how the engine treats it. These mirror
 * the five role block tags (is_reservoir, fluid_conduits, relay_endpoint, sink_only,
 * ignore_fluid_handler); declare one in code with FluidHandlerApi.setRole. A matching block tag takes
 * precedence over a code role, so a pack can always override.
 */
public enum FluidHandlerRole {
    /** A normal tank/capacitor: drained and surface-equalized. Also vetoes the automatic relay detector. */
    RESERVOIR,
    /** A passthrough conduit: chained to its neighbours and equalized with them as one shared buffer. */
    CONDUIT,
    /** A relay/paired device (docking connector, hose): drain-priority, bottomless, never equalized. */
    RELAY,
    /** Receive-only: the engine may fill it but never drains or equalizes it. */
    SINK_ONLY,
    /** Skipped entirely — treated as if it held no fluid, for a device that corrupts on drain AND fill. */
    IGNORE
}
