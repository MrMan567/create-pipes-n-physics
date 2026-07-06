package de.devin.pipesnphysics.engine;

import de.devin.pipesnphysics.PipesNPhysics;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Decides what ROLE a fluid-handler block plays in the network — the single place that separates a
 * real reservoir from a relay device (a docking connector, a hose, a passthrough), so the engine
 * stops equalizing the latter as a tank. See CLAUDE.md §2.
 *
 * A behavioural {@link RelayDetector} learns relays automatically; four block tags override it.
 * Precedence for any block exposing an {@code IFluidHandler} (first match wins):
 *   1. is_reservoir         → normal capacitor (drain + equalize). Vetoes the detector.
 *   2. fluid_conduits       → passthrough conduit: chained AND equalized as a shared buffer
 *                             (handled in {@link GraphBuilder}; a relay we WANT to equalize, e.g. a
 *                             row of liquid burners feeding each other).
 *   3. ignore_fluid_handler → skipped entirely: not a graph node at all, as if the block held no
 *                             fluid — for a device that corrupts on both drain AND fill.
 *   4. relay_endpoint, OR a block the detector has learned is a relay
 *                           → drain-priority BOTTOMLESS endpoint (a docking connector, a hose): a
 *                             one-way source while it holds fluid, a one-way sink while empty, never
 *                             surface-equalized. {@link BoundaryColumn#resolve} builds it.
 *   5. sink_only            → receive-only: the engine may fill it but never drains or equalizes it.
 *   6. otherwise            → normal capacitor.
 *
 * All tags are {@code required: false}, so an entry for a missing mod is silently ignored.
 */
public final class HandlerRoles {
    public static final TagKey<Block> FLUID_CONDUITS = tag("fluid_conduits");
    public static final TagKey<Block> IS_RESERVOIR = tag("is_reservoir");
    public static final TagKey<Block> IGNORE = tag("ignore_fluid_handler");
    public static final TagKey<Block> SINK_ONLY = tag("sink_only");
    public static final TagKey<Block> RELAY_ENDPOINT = tag("relay_endpoint");

    private HandlerRoles() {}

    private static TagKey<Block> tag(String path) {
        return TagKey.create(Registries.BLOCK,
                ResourceLocation.fromNamespaceAndPath(PipesNPhysics.ID, path));
    }

    /**
     * Whether the block at {@code pos} should be skipped as a fluid target: the pipe treats it as if
     * it had no handler (a dead end / open face). The is_reservoir tag vetoes ignore.
     */
    public static boolean isIgnored(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(IGNORE) && !state.is(IS_RESERVOIR);
    }

    /**
     * Whether the handler at {@code pos} is a RELAY endpoint — a paired/passthrough device (a docking
     * connector, a hose) that moves fluid through its own logic and must NOT be modelled as a
     * surface-elevation capacitor. {@link BoundaryColumn} resolves these drain-priority and bottomless
     * (like a hose pulley): a one-way SOURCE while they hold fluid, a one-way SINK while empty — so the
     * engine always drains a receiving connector and always fills a sending one, no matter the levels,
     * instead of calling them "balanced" and refusing to move fluid (the equalization stall). True for
     * the relay_endpoint tag or a detector-learned relay, unless pinned as a real tank / conduit.
     */
    public static boolean isRelayEndpoint(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.is(IS_RESERVOIR) || state.is(FLUID_CONDUITS) || state.is(IGNORE)) return false;
        if (state.is(RELAY_ENDPOINT)) return true;
        return PipesNPhysicsConfig.AUTO_DETECT_RELAY_HANDLERS.get()
                && RelayDetector.isRelay(state.getBlock());
    }

    /**
     * Whether the handler at {@code pos} is receive-only — the engine may fill it but never drains or
     * equalizes it. Only the sink_only tag opts in (a detector-learned relay is a {@link #isRelayEndpoint
     * relay endpoint} instead, which is the bidirectional-friendly demotion); vetoed by is_reservoir,
     * a conduit, or ignore.
     */
    public static boolean isReceiveOnly(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.is(IS_RESERVOIR) || state.is(FLUID_CONDUITS) || state.is(IGNORE)) return false;
        return state.is(SINK_ONLY);
    }
}
