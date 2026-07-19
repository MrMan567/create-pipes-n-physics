package de.devin.pipesnphysics.client.ponder;

import com.simibubi.create.AllBlocks;
import com.tterrag.registrate.util.entry.ItemProviderEntry;
import de.devin.pipesnphysics.PipesNPhysics;
import de.devin.pipesnphysics.client.ponder.scenes.PhysicScenes;
import de.devin.pipesnphysics.client.ponder.scenes.PipeScenes;
import net.createmod.ponder.api.registration.MultiSceneBuilder;
import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.minecraft.resources.ResourceLocation;

/**
 * Ponder plugin: registers the mod's fluid scenes against Create's pump/pipe/tank items. The
 * schematic name resolves under this mod's namespace, so {@code "pump"} loads
 * {@code assets/pipesnphysics/ponder/pump.nbt}. Scene bodies live in {@link PnpPonderScene}.
 */
public class PnpPonderPlugin implements PonderPlugin {
    @Override
    public String getModId() {
        return PipesNPhysics.ID;
    }

    @Override
    public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        PonderSceneRegistrationHelper<ItemProviderEntry<?, ?>> scenes =
                helper.withKeyFunction((ItemProviderEntry<?, ?> entry) -> entry.getId());


        var pump = scenes.forComponents(AllBlocks.MECHANICAL_PUMP)
            .addStoryBoard("pump/intro", PipeScenes::basics);

        registerPhysicScenes(pump);



    }

    private MultiSceneBuilder registerPhysicScenes(MultiSceneBuilder builder) {
        builder.addStoryBoard("physics/equalizing_fluids", PhysicScenes::equalizingFluids);
        builder.addStoryBoard("physics/pipe_gravity", PipeScenes::gravity);
        return builder;
    }
}
