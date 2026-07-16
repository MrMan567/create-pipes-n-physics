package de.devin.pipesnphysics.mixin;

import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.PipeConnection;
import com.simibubi.create.content.fluids.pipes.TransparentStraightPipeRenderer;
import de.devin.pipesnphysics.client.render.PipeFluidRenderer;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The fallback (no-Flywheel) twin of {@link GlassPipeVisualMixin}: hides Create's in-pipe fluid
 * while the engine owns it ({@code PipeFluidRenderer} draws the synced stored content), and widens
 * Create's hairline open-end fill inset for the cases where Create still draws (engine off,
 * virtual pipes).
 */
@Mixin(value = TransparentStraightPipeRenderer.class, remap = false)
public class TransparentStraightPipeRendererMixin {
    @ModifyConstant(method = "renderSafe", constant = @Constant(floatValue = 1e-6f))
    private float pipesnphysics$widenOpenEndInset(float original) {
        return CreatePipeRenderConstants.OPEN_END_INSET;
    }

    @Redirect(method = "renderSafe", at = @At(value = "INVOKE",
            target = "Lcom/simibubi/create/content/fluids/FluidTransportBehaviour;getFlow(Lnet/minecraft/core/Direction;)Lcom/simibubi/create/content/fluids/PipeConnection$Flow;"))
    private PipeConnection.Flow pipesnphysics$hideEngineOwnedFlow(FluidTransportBehaviour pipe, Direction side) {
        if (PipeFluidRenderer.hidesFromCreate(pipe)) return null;
        return pipe.getFlow(side);
    }
}
