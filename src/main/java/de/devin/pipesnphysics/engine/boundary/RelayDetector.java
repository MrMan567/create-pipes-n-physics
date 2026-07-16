package de.devin.pipesnphysics.engine.boundary;

import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Learns, from behaviour alone, which fluid-handler blocks are RELAYS rather than passive tanks, so
 * the engine stops equalizing them as reservoirs (see CLAUDE.md §2 and {@link HandlerRoles}).
 *
 * The discriminator is spontaneous GAIN. A real capacitor's stored amount only moves by the transfers
 * WE apply; a consumer (a basin, a boiler) spontaneously LOSES fluid to its own recipe; only a relay
 * (a docking connector, a hose, a passthrough) spontaneously GAINS fluid, because it is sourcing from
 * a pair or cascade the engine does not model. A block TYPE observed gaining fluid on its own — with
 * no fill from us — on {@link #STRIKES_TO_DEMOTE} separate ticks is demoted to a relay for the rest of
 * the session; a spontaneous loss (a consumer) forgives a strike.
 *
 * Keying by block TYPE means learning one instance generalizes to a whole mod's block with no
 * hardcoded id. Create's own tanks/basins are exempt: one legitimately fed by a SECOND network from
 * another side would otherwise read as an external gain. The learned set is memory-only and re-learns
 * cheaply after a restart; a false positive is recoverable with the is_reservoir tag.
 */
public final class RelayDetector {
    /** mB of spontaneous change below which a reading is treated as noise (rounding / partial fill). */
    private static final int NOISE_MB = 10;
    /** Distinct ticks of unexplained gain before a block type is demoted to a relay. */
    private static final int STRIKES_TO_DEMOTE = 5;

    private record Sample(Fluid fluid, int amountMb) {}

    /** Last observed contents per handler position, and the net fluid WE moved into it since. */
    private static final Map<BlockPos, Sample> lastSample = new HashMap<>();
    private static final Map<BlockPos, Integer> appliedSince = new HashMap<>();
    /** Suspicion count per block type, and the learned relay set. */
    private static final Map<ResourceLocation, Integer> strikes = new HashMap<>();
    private static final Set<ResourceLocation> relays = new HashSet<>();

    private RelayDetector() {}

    /** Whether this block TYPE has been learned to be a relay (never a passive capacitor). */
    public static boolean isRelay(Block block) {
        return !relays.isEmpty() && relays.contains(BuiltInRegistries.BLOCK.getKey(block));
    }

    /** How many spontaneous-gain strikes a block type has accrued toward relay demotion; 0 once learned. */
    public static int strikeCount(Block block) {
        return strikes.getOrDefault(BuiltInRegistries.BLOCK.getKey(block), 0);
    }

    /**
     * Record a handler's live contents at the start of a network tick (called from the solver's column
     * collection). Compares against last tick's reading minus what WE filled in between: a positive
     * remainder is fluid the block sourced itself, evidence that it is a relay.
     */
    public static void observe(Level level, BlockPos pos, Fluid fluid, int amount) {
        if (!PipesNPhysicsConfig.AUTO_DETECT_RELAY_HANDLERS.get()) return;
        BlockState state = level.getBlockState(pos);
        ResourceLocation type = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (relays.contains(type) || isExempt(level, pos, state)) return;

        Sample prev = lastSample.get(pos);
        int applied = appliedSince.getOrDefault(pos, 0);
        if (prev != null && prev.fluid() == fluid) {
            int spontaneous = amount - prev.amountMb() - applied;
            if (spontaneous > NOISE_MB && applied <= 0) {
                if (strikes.merge(type, 1, Integer::sum) >= STRIKES_TO_DEMOTE) {
                    relays.add(type);
                    strikes.remove(type);
                }
            } else if (spontaneous < -NOISE_MB) {
                // a spontaneous LOSS is a consumer, the opposite of a relay — forgive a strike.
                strikes.computeIfPresent(type, (k, v) -> v > 1 ? v - 1 : null);
            }
        }
        lastSample.put(pos.immutable(), new Sample(fluid, amount));
        appliedSince.put(pos.immutable(), 0);
    }

    /** Record fluid the engine actually moved into (+) or out of (-) a tracked handler this tick. */
    public static void recordApplied(BlockPos pos, int delta) {
        if (lastSample.containsKey(pos)) appliedSince.merge(pos, delta, Integer::sum);
    }

    /** Blocks that are never demoted: already-classified handlers (tag or code) and Create's tanks/basins. */
    private static boolean isExempt(Level level, BlockPos pos, BlockState state) {
        if (HandlerRoles.hasExplicitRole(state)) return true;
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof FluidTankBlockEntity || blockEntity instanceof BasinBlockEntity;
    }

    /** Forget every sample, strike, and learned relay — called on server stop (the set is session-only). */
    public static void clear() {
        lastSample.clear();
        appliedSince.clear();
        strikes.clear();
        relays.clear();
    }
}
