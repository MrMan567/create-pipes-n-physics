package de.devin.pipesnphysics.api;

import net.neoforged.neoforge.fluids.FluidStack;

/**
 * A behavioral hook that decides, in code, how a fluid splits when centrifuged — for separations a
 * static datapack recipe cannot express (outputs that depend on the fluid's components, amount, or
 * other runtime state). Register one with {@link CentrifugeApi#registerSeparator}.
 *
 * <p>Example — a mod separating its own mixed fluid:
 * <pre>{@code
 * CentrifugeApi.registerSeparator(input -> {
 *     if (input.getFluid() != MyMod.MIXED.get()) return null;
 *     return new CentrifugeRecipe(new FluidStack(MyMod.MIXED.get(), 20),
 *             List.of(new FluidStack(MyMod.PART_A.get(), 10), new FluidStack(MyMod.PART_B.get(), 10)));
 * });
 * }</pre>
 */
@FunctionalInterface
public interface CentrifugeSeparator {
    /**
     * The separation for {@code input} — the fluid+amount consumed per operation and the fluids
     * produced — or null if this separator does not handle that fluid. The returned recipe's input
     * must be the same fluid as {@code input}; results that do not match are ignored.
     */
    CentrifugeRecipe separate(FluidStack input);
}
