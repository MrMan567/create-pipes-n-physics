package de.devin.pipesnphysics.api;

import net.neoforged.neoforge.fluids.FluidStack;

/**
 * A hook that decides in code how a fluid splits when centrifuged — for separations a static datapack
 * recipe cannot express, where the outputs depend on the fluid's components, amount, or other runtime
 * state. Register one with CentrifugeApi.registerSeparator.
 */
@FunctionalInterface
public interface CentrifugeSeparator {
    /**
     * The separation for the input fluid — the fluid and amount consumed per operation and the fluids
     * produced — or null if this separator does not handle it. The returned recipe's input must be the
     * same fluid as the input; results that do not match are ignored.
     */
    CentrifugeRecipe separate(FluidStack input);
}
