package de.devin.pipesnphysics.display;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.api.registry.CreateRegistries;
import de.devin.pipesnphysics.PipesNPhysics;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registers the pipe and pump display-link sources into Create's {@code display_source}
 * registry and, once the block-entity registry is frozen, associates them with Create's
 * pipe and pump block-entity types. The mod owns none of those blocks, so association
 * happens in setup rather than through Registrate's {@code associate} (which would need
 * the types resolved at builder time, before Create registers them).
 */
public final class PnpDisplaySources {
    private static final DeferredRegister<DisplaySource> SOURCES =
            DeferredRegister.create(CreateRegistries.DISPLAY_SOURCE, PipesNPhysics.ID);

    public static final DeferredHolder<DisplaySource, PipeNetworkDisplaySource> PIPE =
            SOURCES.register("pipe", () ->
                    new PipeNetworkDisplaySource(PipeDisplayMetric.PIPE_METRICS, "display_source.pipe_metric", false));
    public static final DeferredHolder<DisplaySource, PipeNetworkDisplaySource> PUMP =
            SOURCES.register("pump", () ->
                    new PipeNetworkDisplaySource(PipeDisplayMetric.PUMP_METRICS, "display_source.pump_metric", true));

    public static void register(IEventBus modBus) {
        SOURCES.register(modBus);
    }

    /** Wire the sources onto Create's pipe/pump BE types; call after the BE registry is frozen. */
    public static void associate() {
        DisplaySource pipe = PIPE.get();
        associate(pipe, AllBlockEntityTypes.FLUID_PIPE.get());
        associate(pipe, AllBlockEntityTypes.ENCASED_FLUID_PIPE.get());
        associate(pipe, AllBlockEntityTypes.GLASS_FLUID_PIPE.get());
        associate(pipe, AllBlockEntityTypes.SMART_FLUID_PIPE.get());
        associate(PUMP.get(), AllBlockEntityTypes.MECHANICAL_PUMP.get());
    }

    private static void associate(DisplaySource source, BlockEntityType<?> type) {
        DisplaySource.BY_BLOCK_ENTITY.add(type, source);
    }

    private PnpDisplaySources() {}
}
