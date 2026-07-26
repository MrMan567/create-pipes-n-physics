package de.devin.pipesnphysics.client.ponder;

import com.simibubi.create.AllBlocks;
import com.tterrag.registrate.util.entry.ItemProviderEntry;
import de.devin.pipesnphysics.PipesNPhysics;
import de.devin.pipesnphysics.client.ponder.scenes.PhysicScenes;
import de.devin.pipesnphysics.client.ponder.scenes.PipeScenes;
import de.devin.pipesnphysics.client.ponder.scenes.PumpScenes;
import de.devin.pipesnphysics.client.ponder.scenes.ValveScenes;
import net.createmod.ponder.api.registration.MultiSceneBuilder;
import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.registration.PonderTagRegistrationHelper;
import net.minecraft.resources.ResourceLocation;

/**
 * Ponder plugin: registers the mod's fluid scenes against Create's pump/pipe/tank items. The
 * schematic name resolves under this mod's namespace, so {@code "pump"} loads
 * {@code assets/pipesnphysics/ponder/pump.nbt}. Scene bodies live in {@link PnpPonderScene}.
 */
public class PnpPonderPlugin implements PonderPlugin {
    /** The index category grouping this mod's fluid scenes. */
    public static final ResourceLocation FLUID_PHYSICS = PipesNPhysics.asResource("fluid_physics");

    @Override
    public String getModId() {
        return PipesNPhysics.ID;
    }

    @Override
    public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        PonderSceneRegistrationHelper<ItemProviderEntry<?, ?>> scenes =
                helper.withKeyFunction((ItemProviderEntry<?, ?> entry) -> entry.getId());


        // The uphill scene hands off to the siphon ("you paid RPM to climb — if the far side
        // goes back down, you only pay once"), so they stay adjacent in the Up Next chain.
        var pump = scenes.forComponents(AllBlocks.MECHANICAL_PUMP)
            .addStoryBoard("pump/intro", PumpScenes::basics, FLUID_PHYSICS)
            .addStoryBoard("pump/pump_vertically", PumpScenes::pumpingUphill, FLUID_PHYSICS)
            .addStoryBoard("physics/siphon", PhysicScenes::siphon, FLUID_PHYSICS);

        var pipe = scenes.forComponents(AllBlocks.FLUID_PIPE)
            .addStoryBoard("pipe/overview", PipeScenes::overview, FLUID_PHYSICS)
            .addStoryBoard("physics/siphon", PhysicScenes::siphon, FLUID_PHYSICS);

        scenes.forComponents(AllBlocks.FLUID_VALVE)
            .addStoryBoard("pipe/valve", ValveScenes::throttle, FLUID_PHYSICS);

        registerPhysicScenes(pump);
        registerPhysicScenes(pipe);
    }

    @Override
    public void registerTags(PonderTagRegistrationHelper<ResourceLocation> helper) {
        PonderTagRegistrationHelper<ItemProviderEntry<?, ?>> tags =
                helper.withKeyFunction((ItemProviderEntry<?, ?> entry) -> entry.getId());

        helper.registerTag(FLUID_PHYSICS)
                .addToIndex()
                .item(AllBlocks.MECHANICAL_PUMP.get(), true, false)
                .title("Fluid Physics")
                .description("How fluids behave in pipes: gravity, equalizing levels, pumping uphill and siphons")
                .register();

        tags.addToTag(FLUID_PHYSICS)
                .add(AllBlocks.MECHANICAL_PUMP)
                .add(AllBlocks.FLUID_PIPE)
                .add(AllBlocks.FLUID_TANK)
                .add(AllBlocks.FLUID_VALVE);
    }

    private MultiSceneBuilder registerPhysicScenes(MultiSceneBuilder builder) {
        builder.addStoryBoard("physics/equalizing_fluids", PhysicScenes::equalizingFluids, FLUID_PHYSICS);
        builder.addStoryBoard("physics/pipe_gravity", PumpScenes::gravity, FLUID_PHYSICS);
        return builder;
    }
}
