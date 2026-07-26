package de.devin.pipesnphysics.mixin;

import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import de.devin.pipesnphysics.engine.EngineTickHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels Create's fluid transport tick on every pipe while the engine is enabled,
 * and marks the network as dirty so the engine picks it up on the next server tick.
 *
 * The cancel happens on both server and client so Create's pressure propagation and
 * flow creation don't fight the engine. Create's Flow objects are no longer written, ticked,
 * or drawn under the engine — the pipe render mixins hide them and the client draws each
 * cell's real stored content ({@code PipeFluidRenderer}) — so their fill cosmetics
 * ({@code tickFlowProgress}) are not ticked either; a stale persisted flow from an older
 * world stays frozen and hidden.
 *
 * The high {@code priority} biases this cancel to win when another Create addon ALSO hijacks
 * {@code tick()} with an {@code @At("HEAD") cancellable} injector — CROWNS does exactly this
 * ({@code FluidTransportBehaviourMixin} reimplements the tick to mix real-gas state, cancelling at
 * HEAD with default priority 1000). Two HEAD-cancellable injectors race: whichever executes first
 * cancels, and the other's callback is skipped by the injected cancellation return — and the race
 * has been OBSERVED going either way across launches, so nothing may depend on winning it. The two
 * things that must survive a loss are both hosted race-proof elsewhere:
 * {@link EngineTickHandler#markDirty}, the engine's heartbeat, lives on the pipe block entity's own
 * tick ({@link PipeHeartbeatMixin}); and the PUMP's transport suppression lives on the pump
 * behaviour's SUBCLASS tick ({@link PumpTransferTickMixin}) — the pump override re-pressurizes its
 * connections AFTER {@code super.tick()}, so this base-level cancel never stops it, and a peer
 * winning the race then ran Create's flow management against real pump pressure: a PARALLEL
 * Create-side transfer the engine never saw, silently draining sources into Create's own endpoint
 * buffers (the pump-spill flake). With the pump tick cancelled at its own method, a peer's
 * reimplemented pipe transport is genuinely inert — no pressure source remains, so no flow can
 * start. CROWNS's per-endpoint gas-state mixing still runs through {@code FluidTank.fill}, which
 * our IFluidHandler transfers hit.
 */
@Mixin(value = FluidTransportBehaviour.class, remap = false, priority = 1500)
public abstract class GravityFlowMixin extends BlockEntityBehaviour {
    private GravityFlowMixin() { super(null); }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void pipesnphysics$cancelCreateTransport(CallbackInfo ci) {
        // The suppression decision is shared with the pump's subclass-tick cancel
        // (PumpTransferTickMixin) so the two sites can never disagree: engine on + real block, or
        // ponder's client-side virtual level where the engine runs live (PonderEngineDriver owns
        // the fluid; server-side virtual and flag-off keep Create's animation). Create's Flow
        // objects are then no longer written, ticked, or drawn — the pipe render mixins hide them
        // and PipeFluidRenderer draws the cells' stored content — so a stale persisted flow from
        // an older world stays frozen and hidden.
        if (EngineTickHandler.suppressesCreateTransport(blockEntity)) ci.cancel();
    }
}
