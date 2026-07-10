package de.devin.pipesnphysics.mixin;

import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.PipeConnection;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.compat.CreatePipeRendering;
import de.devin.pipesnphysics.engine.EngineTickHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
 * flow creation don't fight the engine. The one piece we KEEP is
 * {@link PipeConnection#tickFlowProgress} — pure cosmetics that advances the fill
 * animation Create draws — so engine-seeded fluid fronts visibly travel down a pipe
 * instead of popping full. It moves no fluid and starts no flows on its own.
 *
 * EXCEPT cells the in-pipe LEVEL renderer owns ({@code CreatePipeRendering.ownsAnimation}):
 * their front is integrated by the engine into a dedicated synced field, and letting Create
 * advance its Flow progress underneath would run a second, disagreeing integrator. Skipping
 * the call also skips its client cosmetics (the idle rim drip particles) on those cells —
 * acceptable, the renderer owns them. Stock-rendered cells (flag off, gas, junctions) keep
 * the tick unchanged.
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

        FluidTransportBehaviour self = (FluidTransportBehaviour) (Object) this;
        BlockPos pos = blockEntity.getBlockPos();
        if (!CreatePipeRendering.ownsAnimation(self)) {
            for (Direction dir : Direction.values()) {
                PipeConnection conn = self.getConnection(dir);
                if (conn != null) conn.tickFlowProgress(level, pos);
            }
        }
        ci.cancel();
    }
}
