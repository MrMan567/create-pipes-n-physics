package de.devin.pipesnphysics.engine.boundary;

/**
 * Exposed by Create's hose pulley so the engine can pin it in OUTPUT mode and read that role back.
 * The role lives on the pulley's own block entity — and therefore in its NBT — because it must
 * outlive a world reload: see {@link OpenEndPipes#markPulleyOutput}.
 */
public interface PulleyOutputMode {
    /** Whether this pulley has deposited into the world and is pinned as a one-way sink. */
    boolean pipesnphysics$isOutput();

    /** Pin (or release) the output role; persisted with the pulley. */
    void pipesnphysics$setOutput(boolean output);
}
