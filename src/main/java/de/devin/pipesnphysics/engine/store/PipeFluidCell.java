package de.devin.pipesnphysics.engine.store;

import net.neoforged.neoforge.fluids.FluidStack;

/**
 * The engine's per-pipe-cell fluid state, carried on the pipe's {@code FluidTransportBehaviour}
 * (see {@code FluidTransportBehaviourMixin}). This is REAL, conserved volume — not render
 * metadata: every mB in a cell was drained from a handler or a neighbouring cell, and leaves the
 * same way ({@link PipeFlowExecutor}). Two fields.
 *
 * Content is the fluid stored in this cell (type + mB, at most {@link PipeStore#capacityMb()}),
 * SAVED to disk and synced to clients: reload resumes with the exact in-transit volume, a
 * contraption assembly carries it along, and the client renderer draws pipes directly from it.
 *
 * Flow data is one packed int ({@link PipeStore#encodeFlow}) with the downstream direction and
 * advance rate of the fluid moving through the cell this tick, or {@code 0} at rest. Synced only
 * (re-derived every tick); it drives the client's scroll animation and sub-tick front
 * extrapolation, never any fluid logic.
 *
 * This replaced the render-only {@code PipeLevelData} triple of the cosmetic pipeline: content is
 * now the single source of truth for both the renderer and the transfer layer.
 *
 * Server code must mutate content only through {@link PipeStore.Store} (capacity clamp, no
 * mixing, batched sync); the raw setters below exist for the mixin/NBT plumbing.
 */
public interface PipeFluidCell {
    /** The fluid stored in this cell (type + mB), {@code FluidStack.EMPTY} when dry. */
    FluidStack pipesnphysics$content();

    /** Raw content write for the mixin/NBT plumbing; server logic goes through {@link PipeStore.Store}. */
    void pipesnphysics$setContent(FluidStack content);

    /** The packed flow stamp ({@link PipeStore#encodeFlow}), or {@code 0} at rest. */
    int pipesnphysics$flowData();

    /** Raw flow-stamp write for the mixin/NBT plumbing; server logic goes through {@link PipeStore.Store}. */
    void pipesnphysics$setFlowData(int data);
}
