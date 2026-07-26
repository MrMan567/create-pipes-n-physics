package de.devin.pipesnphysics.mixin;

import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import de.devin.pipesnphysics.engine.EngineTickHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels the PUMP's own transport-behaviour tick under the engine — the race-proof half
 * of the Create-transport suppression that {@link GravityFlowMixin} alone cannot provide.
 *
 * {@code PumpFluidTransferBehaviour.tick()} OVERRIDES the base tick: it calls
 * {@code super.tick()} and then re-pressurizes the pump's own {@code PipeConnection}s from
 * the kinetic speed. A HEAD cancel on the BASE method (GravityFlowMixin) only skips the
 * super call — the subclass remainder still runs, so the pump's connections carry live
 * pressure every tick no matter what. That pressure is harmless only while NOTHING runs
 * Create's flow management; but a peer addon that HEAD-hijacks the base tick and wins the
 * race (CROWNS's {@code replaceTick} reimplementation — the same race the heartbeat had to
 * be moved for) then manages the pump's flows against real pressure: Create-side Flows
 * form at the pump and its Layer-III {@code FluidNetwork} runs a PARALLEL transfer the
 * engine never sees — draining a source ~|speed|/2 mB/t into Create's own endpoint
 * buffers, fluid simply vanishing from the engine's books (the
 * {@code pumpSpillsLowSourceOncePastBlockThreshold} flake: the mouth's block-place buffer
 * starved 89 mB short of its 1000 threshold).
 *
 * Cancelling HERE — the subclass method no peer targets — is immune to that race, and it
 * removes the pressure source entirely, which is what makes a peer's reimplemented pipe
 * transport genuinely inert under the engine (no pump pressure anywhere, so no flow ever
 * starts). Same suppression decision as the base cancel
 * ({@link EngineTickHandler#suppressesCreateTransport}), so ponder/virtual/flag-off
 * behavior stays identical.
 */
@Mixin(targets = "com.simibubi.create.content.fluids.pump.PumpBlockEntity$PumpFluidTransferBehaviour",
        remap = false, priority = 1500)
public abstract class PumpTransferTickMixin extends BlockEntityBehaviour {
    private PumpTransferTickMixin() { super(null); }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void pipesnphysics$cancelPumpTransport(CallbackInfo ci) {
        if (EngineTickHandler.suppressesCreateTransport(blockEntity)) ci.cancel();
    }
}
