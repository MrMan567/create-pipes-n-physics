package de.devin.pipesnphysics.mixin;

import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.PipeConnection;
import com.simibubi.create.content.fluids.pipes.GlassPipeVisual;
import de.devin.pipesnphysics.client.render.PipeFluidRenderer;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Hands in-pipe fluid rendering to the engine: while it is enabled, a real pipe's {@code getFlow}
 * reads null here so Create's visual draws nothing — {@code PipeFluidRenderer} draws the cell's
 * synced stored content instead. Virtual (ponder/schematic) pipes keep Create's animation, as does
 * everything when the engine is off.
 *
 * Also widens Create's hairline {@code 1e-6} open-end fill inset to a real gap so a fluid front at
 * an open mouth stops z-fighting the pipe rim (relevant when Create IS drawing — engine off /
 * ponder). Mirrors {@link TransparentStraightPipeRendererMixin}.
 */
@Mixin(value = GlassPipeVisual.class, remap = false)
public class GlassPipeVisualMixin {
    @ModifyConstant(method = "beginFrame", constant = @Constant(floatValue = 1e-6f))
    private float pipesnphysics$widenOpenEndInset(float original) {
        return CreatePipeRenderConstants.OPEN_END_INSET;
    }

    @Redirect(method = "beginFrame", at = @At(value = "INVOKE",
            target = "Lcom/simibubi/create/content/fluids/FluidTransportBehaviour;getFlow(Lnet/minecraft/core/Direction;)Lcom/simibubi/create/content/fluids/PipeConnection$Flow;"))
    private PipeConnection.Flow pipesnphysics$hideEngineOwnedFlow(FluidTransportBehaviour pipe, Direction side) {
        if (PipeFluidRenderer.hidesFromCreate(pipe)) return null;
        return pipe.getFlow(side);
    }
}
