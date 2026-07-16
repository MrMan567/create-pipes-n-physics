package de.devin.pipesnphysics.mixin;

import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.engine.EngineTickHandler;
import net.minecraft.world.level.Level;
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
 * cancels, and the other's callback is skipped by the injected cancellation return. Winning here
 * keeps CROWNS's reimplemented transport from running, which is tidiest — but it is NOT load-bearing,
 * because that reimplementation is inert under our engine anyway (its {@code manageFlows} needs
 * Create pump pressure, which {@link PumpBlockEntityMixin} cancels). The one thing that MUST fire
 * every tick — {@link EngineTickHandler#markDirty}, the engine's heartbeat — no longer rides this
 * cancel: it lives on the pipe block entity's own tick ({@link PipeHeartbeatMixin}), which no
 * behaviour-level cancel can preempt. So if CROWNS wins this race the engine still wakes and moves
 * fluid. CROWNS's per-endpoint gas-state mixing still runs through {@code FluidTank.fill}, which our
 * IFluidHandler transfers hit.
 */
@Mixin(value = FluidTransportBehaviour.class, remap = false, priority = 1500)
public abstract class GravityFlowMixin extends BlockEntityBehaviour {
    private GravityFlowMixin() { super(null); }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void pipesnphysics$cancelCreateTransport(CallbackInfo ci) {
        if (!PipesNPhysicsConfig.ENABLE_ENGINE.get()) return;
        if (blockEntity.isVirtual()) return; // Ponder scenes & schematics keep Create's animation
        Level level = blockEntity.getLevel();
        if (level == null) return;
        // The heartbeat (markDirty) lives on the block-entity tick now (PipeHeartbeatMixin), so it
        // survives even when a peer addon wins this HEAD-cancel race and skips this callback.

        // Create's Flow objects are no longer written or drawn under the engine (the pipe render
        // mixins hide them; PipeFluidRenderer draws the cells' stored content), so their fill
        // cosmetics (tickFlowProgress) are not ticked either — a stale persisted flow from an
        // older world stays frozen and hidden.
        ci.cancel();
    }
}
