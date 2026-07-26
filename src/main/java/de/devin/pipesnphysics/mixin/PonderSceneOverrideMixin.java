package de.devin.pipesnphysics.mixin;

import de.devin.pipesnphysics.PipesNPhysics;
import net.createmod.ponder.api.registration.StoryBoardEntry;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.registration.PonderSceneRegistry;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Map;

/**
 * Replaces Create's stock fluid scenes with ours: Ponder keys scenes by item in an append-only
 * multimap, so pondering a pump shows Create's (now-wrong) transport scene alongside ours. For any
 * item this mod registers a scene for, compile ONLY this mod's scenes and drop the rest — leaving
 * every other item untouched. Reload-safe (runs off the live registry each time an item is opened).
 */
@Mixin(PonderSceneRegistry.class)
public class PonderSceneOverrideMixin {
    @Inject(method = "compile(Lnet/minecraft/resources/ResourceLocation;)Ljava/util/List;",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void pnp$overrideOwnedScenes(ResourceLocation id, CallbackInfoReturnable<List<PonderScene>> cir) {
        PonderSceneRegistry self = (PonderSceneRegistry) (Object) this;
        List<StoryBoardEntry> owned = self.getRegisteredEntries().stream()
                .filter(entry -> entry.getKey().equals(id))
                .map(Map.Entry::getValue)
                .filter(board -> board.getSchematicLocation().getNamespace().equals(PipesNPhysics.ID))
                .toList();
        if (owned.isEmpty()) return;
        cir.setReturnValue(self.compile(owned));
    }
}
