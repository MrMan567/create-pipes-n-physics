package de.devin.pipesnphysics.api;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.List;

/**
 * One centrifuging (reverse-mix) separation: an input fluid spun hard enough splits into its outputs.
 * The input amount is the mB consumed per operation and each output amount the mB produced. This is
 * both the datapack format (loaded from {@code data/<ns>/centrifuging/*.json}) and the value a
 * {@link CentrifugeSeparator} returns, so a mod can build one directly: {@code new CentrifugeRecipe(in, outs)}.
 */
public record CentrifugeRecipe(FluidStack input, List<FluidStack> outputs) {
    public static final Codec<CentrifugeRecipe> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            FluidStack.CODEC.fieldOf("input").forGetter(CentrifugeRecipe::input),
            FluidStack.CODEC.listOf().fieldOf("outputs").forGetter(CentrifugeRecipe::outputs)
    ).apply(instance, CentrifugeRecipe::new));

    /** True when {@code held} is this recipe's input fluid with at least one operation's worth. */
    public boolean matches(FluidStack held) {
        return FluidStack.isSameFluidSameComponents(held, input) && held.getAmount() >= input.getAmount();
    }
}
