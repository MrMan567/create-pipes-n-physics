package de.devin.pipesnphysics.engine.boundary;

import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
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
 * a pair or cascade the engine does not model. A POSITION observed gaining fluid on its own — with no
 * fill from us — on {@link #STRIKES_TO_DEMOTE} separate ticks is demoted to a relay for the rest of
 * the session; a spontaneous loss (a consumer) forgives a strike.
 *
 * Learning is PER POSITION, deliberately NOT per block type: one type can carry role-diverse
 * instances — every level of a diesel-generators distillation tower is the same block, but the
 * bottom is a fillable crude INPUT (it spontaneously loses to the recipe) while the levels above
 * spontaneously GAIN products. Type-keyed strikes let the product levels demote the whole type, and
 * the input then resolved as a brimming one-way SOURCE that refused every fill until a restart
 * cleared the learned set ("why can't we pump into here anymore?"). Per-position, each level earns
 * its own role. The cost is that a fresh instance re-learns over a few ticks — the paired-device
 * blocks that motivated the detector (docking connectors) ship in the relay_endpoint tag anyway,
 * which applies instantly. Create's own tanks/basins stay exempt: one legitimately fed by a SECOND
 * network from another side would otherwise read as an external gain. The learned set is memory-only
 * (cleared on server stop, forgotten on block break); a false positive is recoverable with the
 * is_reservoir tag.
 */
public final class RelayDetector {
    /** mB of spontaneous change below which a reading is treated as noise (rounding / partial fill). */
    private static final int NOISE_MB = 10;
    /** Distinct ticks of unexplained gain before a position is demoted to a relay. */
    private static final int STRIKES_TO_DEMOTE = 5;
    /** Ticks without an observation before a position's samples and strikes are dropped. */
    private static final int STALE_TICKS = 1200;

    private record Sample(Fluid fluid, int amountMb, long seenAt) {}

    /** Last observed contents per handler position, and the net fluid WE moved into it since. */
    private static final Map<BlockPos, Sample> lastSample = new HashMap<>();
    private static final Map<BlockPos, Integer> appliedSince = new HashMap<>();
    /** Suspicion count per position, and the learned relay set. */
    private static final Map<BlockPos, Integer> strikes = new HashMap<>();
    private static final Set<BlockPos> relays = new HashSet<>();

    private RelayDetector() {}

    /** Whether this POSITION has been learned to be a relay (never a passive capacitor). */
    public static boolean isRelay(BlockPos pos) {
        return !relays.isEmpty() && relays.contains(pos);
    }

    /** How many spontaneous-gain strikes a position has accrued toward relay demotion; 0 once learned. */
    public static int strikeCount(BlockPos pos) {
        return strikes.getOrDefault(pos, 0);
    }

    /**
     * Record a handler's live contents at the start of a network tick (called from the solver's column
     * collection). Compares against last tick's reading minus what WE filled in between: a positive
     * remainder is fluid the block sourced itself, evidence that it is a relay.
     */
    public static void observe(Level level, BlockPos pos, Fluid fluid, int amount) {
        if (level.isClientSide()) return; // a client (ponder) run must never teach the real, pos-keyed detector
        if (!PipesNPhysicsConfig.AUTO_DETECT_RELAY_HANDLERS.get()) return;
        BlockState state = level.getBlockState(pos);
        if (relays.contains(pos) || isExempt(level, pos, state)) return;

        Sample prev = lastSample.get(pos);
        int applied = appliedSince.getOrDefault(pos, 0);
        if (prev != null && prev.fluid() == fluid) {
            int spontaneous = amount - prev.amountMb() - applied;
            if (spontaneous > NOISE_MB && applied <= 0) {
                if (strikes.merge(pos.immutable(), 1, Integer::sum) >= STRIKES_TO_DEMOTE) {
                    relays.add(pos.immutable());
                    strikes.remove(pos);
                }
            } else if (spontaneous < -NOISE_MB) {
                // a spontaneous LOSS is a consumer, the opposite of a relay — forgive a strike.
                strikes.computeIfPresent(pos, (k, v) -> v > 1 ? v - 1 : null);
            }
        }
        lastSample.put(pos.immutable(), new Sample(fluid, amount, level.getGameTime()));
        appliedSince.put(pos.immutable(), 0);
    }

    /**
     * Drop samples and strikes for positions nothing has observed in {@link #STALE_TICKS} —
     * handlers that left the network without their block breaking (a severed pipe, a disassembled
     * contraption) would otherwise accumulate forever. A dropped position simply re-learns in a
     * few observations if it returns; the learned relay set is deliberately sticky and stays.
     * Called on the tick handler's slow sweep cadence.
     */
    public static void sweep(long now) {
        lastSample.entrySet().removeIf(entry -> now - entry.getValue().seenAt() >= STALE_TICKS);
        appliedSince.keySet().removeIf(pos -> !lastSample.containsKey(pos));
        strikes.keySet().removeIf(pos -> !lastSample.containsKey(pos));
    }

    /** Record fluid the engine actually moved into (+) or out of (-) a tracked handler this tick. */
    public static void recordApplied(BlockPos pos, int delta) {
        if (lastSample.containsKey(pos)) appliedSince.merge(pos, delta, Integer::sum);
    }

    /**
     * Drop everything learned about a position — called when its block is broken, so a tank placed
     * where a demoted relay stood does not inherit the relay role.
     */
    public static void forget(BlockPos pos) {
        lastSample.remove(pos);
        appliedSince.remove(pos);
        strikes.remove(pos);
        relays.remove(pos);
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
