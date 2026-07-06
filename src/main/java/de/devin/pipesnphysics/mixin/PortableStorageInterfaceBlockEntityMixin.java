package de.devin.pipesnphysics.mixin;

import com.simibubi.create.content.contraptions.actors.psi.PortableFluidInterfaceBlockEntity;
import com.simibubi.create.content.contraptions.actors.psi.PortableStorageInterfaceBlockEntity;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.engine.EngineTickHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Wakes the adjacent pipe network the tick a portable fluid interface docks or undocks.
 *
 * <p>A contraption aligning with a stationary interface flips it {@code isConnected()} — swapping the
 * exposed fluid handler between an empty {@code FluidTank(0)} (inert) and the contraption's own
 * storage — with NO block event. {@code NetworkEditHandler} therefore never fires, and a network with
 * no running pump to arm the fast recheck would not notice the new source/sink until the 20-tick
 * heartbeat. That is long enough that a contraption pausing to transfer moves on before any fluid
 * flows, so a passive gravity feed across the interface never gets going.
 *
 * <p>The stationary interface is a ticking world block entity, so on the connected-state flip we
 * {@code markChanged} the six neighbours once — exactly as {@link HosePulleyBlockEntityMixin} does on
 * its priming-&gt;ready flip. The wake bypasses the sleep gate; once fluid starts moving the network
 * stays live on its own each tick, and the undock flip wakes it again to settle/recede promptly.
 *
 * <p>Only fluid interfaces matter (an item interface exposes no fluid handler), and it is a no-op when
 * the engine is disabled, so vanilla behaviour is untouched.
 */
@Mixin(value = PortableStorageInterfaceBlockEntity.class, remap = false)
public abstract class PortableStorageInterfaceBlockEntityMixin {
    @Shadow
    abstract boolean isConnected();

    @Unique
    private boolean pipesnphysics$wasConnected = false;

    @Inject(method = "tick", at = @At("TAIL"))
    private void pipesnphysics$wakeOnConnectionFlip(CallbackInfo ci) {
        if (!((Object) this instanceof PortableFluidInterfaceBlockEntity self)) return;
        Level world = self.getLevel();
        if (world == null || world.isClientSide()) return;
        if (!PipesNPhysicsConfig.ENABLE_ENGINE.get()) return;

        boolean connected = isConnected();
        if (connected != pipesnphysics$wasConnected) {
            BlockPos pos = self.getBlockPos();
            for (Direction d : Direction.values()) {
                EngineTickHandler.markChanged(world, pos.relative(d));
            }
        }
        pipesnphysics$wasConnected = connected;
    }
}
