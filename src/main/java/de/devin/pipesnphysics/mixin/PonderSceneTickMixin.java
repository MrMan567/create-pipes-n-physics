package de.devin.pipesnphysics.mixin;

import de.devin.pipesnphysics.client.ponder.PonderEngineDriver;
import net.createmod.ponder.foundation.PonderScene;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives the fluid engine over a Ponder scene's level once per scene tick, so the mod's physics run
 * live in ponder (see {@link PonderEngineDriver}). Client-only; the scene ticks only while a player
 * is viewing it.
 */
@Mixin(PonderScene.class)
public class PonderSceneTickMixin {
    @Inject(method = "tick", at = @At("TAIL"), remap = false)
    private void pnp$driveEngine(CallbackInfo ci) {
        PonderEngineDriver.tick(((PonderScene) (Object) this).getWorld());
    }
}
