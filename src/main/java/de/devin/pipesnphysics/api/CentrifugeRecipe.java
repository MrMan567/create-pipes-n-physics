package de.devin.pipesnphysics.api;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.List;

/**
 * One centrifuging separation: an input fluid, spun hard enough, splits into its outputs. The input
 * amount is the mB consumed per operation and each output amount the mB produced. minAngularSpeed is an
 * optional per-recipe spin floor (radians/second, 0 for none) that stacks on top of the global minimum,
 * so a stubborn mixture can demand a faster spin than the default. This is both the datapack format and
 * the value a CentrifugeSeparator returns, so a mod can build one directly; the two-argument form omits
 * the spin floor.
 */
public record CentrifugeRecipe(FluidStack input, List<FluidStack> outputs, double minAngularSpeed) {
    public static final Codec<CentrifugeRecipe> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            FluidStack.CODEC.fieldOf("input").forGetter(CentrifugeRecipe::input),
            FluidStack.CODEC.listOf().fieldOf("outputs").forGetter(CentrifugeRecipe::outputs),
            Codec.DOUBLE.optionalFieldOf("min_angular_speed", 0.0).forGetter(CentrifugeRecipe::minAngularSpeed)
    ).apply(instance, CentrifugeRecipe::new));

    /** A separation with no spin floor of its own beyond the global minimum. */
    public CentrifugeRecipe(FluidStack input, List<FluidStack> outputs) {
        this(input, outputs, 0);
    }

    /** True when the held stack is this recipe's input fluid with at least one operation's worth. */
    public boolean matches(FluidStack held) {
        return FluidStack.isSameFluidSameComponents(held, input) && held.getAmount() >= input.getAmount();
    }
}
