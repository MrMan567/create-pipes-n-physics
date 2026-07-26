package de.devin.pipesnphysics.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.PipeConnection;
import com.simibubi.create.content.fluids.pipes.StraightPipeBlockEntity;
import com.simibubi.create.content.fluids.pipes.TransparentStraightPipeRenderer;
import de.devin.pipesnphysics.client.render.PipeFluidRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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

    /**
     * Draw the engine's stored fluid on a PONDER pipe: this BER runs inside ponder's render (Flywheel
     * visuals don't manage the fake level), and there Create's transport is cancelled so it draws
     * nothing. A real (non-virtual) pipe is handled by the main-world {@code PipeFluidRenderer} instead;
     * a non-ponder virtual pipe holds no engine content, so {@code drawPonderCell} bails.
     */
    @Inject(method = "renderSafe(Lcom/simibubi/create/content/fluids/pipes/StraightPipeBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At("TAIL"), remap = false)
    private void pnp$drawPonderFluid(StraightPipeBlockEntity be, float partialTicks, PoseStack ms,
                                     MultiBufferSource buffer, int light, int overlay, CallbackInfo ci) {
        if (be.isVirtual()) PipeFluidRenderer.drawPonderCell(be, ms, buffer, light);
    }
}
