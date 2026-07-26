package de.devin.pipesnphysics.compat;

import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.engine.EngineTickHandler;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;

/**
 * Drives the engine on Sable sub-levels. A sub-level's blocks live in the parent ServerLevel at
 * far-away "plot" coordinates, so the engine can read and solve them with no changes — but Sable
 * assembles a contraption with raw {@code setBlockState} (no place event) and a dry pipe never
 * self-ticks, so the network is never woken by the normal reactive hooks. This enumerates each
 * sub-level's plot chunks and seeds its pipe cells so the engine drives them like any other
 * network (the QUIET sleep gate still throttles re-solves of idle ones). References full Sable, so
 * it is only loaded when full Sable is present (gated in {@link SableCompat#seedSubLevels}).
 *
 * <p>The pipe cells of each plot chunk are CACHED ({@link #pipeCells}): scanning every block
 * entity of every contraption chunk through a full behaviour resolution each tick dominated the
 * driver's cost while doing nothing at all on an idle server. Sleeping cells are also skipped
 * against the engine's own QUIET state instead of being marked and bounced off it downstream.
 *
 * <p>It also REFRESHES each newly-seen pipe's connection shape once ({@link #refreshConnections}).
 * That raw {@code setBlockState} assembly skips the neighbour {@code updateShape} that normally sets
 * a fluid pipe's per-face connection booleans, so a pipe can carry a STALE shape that
 * {@code FluidPipeBlockEntity.canHaveFlowToward} reads — and {@code GraphBuilder} then drops the real
 * edge, leaving the network solving "no flow" until a manual pipe edit re-runs {@code updateShape}.
 * We do that update ourselves so pumps and networks work without the poke.
 */
final class SableSubLevelDriver {
    /**
     * Pipe cells whose connection shape we have already refreshed, so the (world-mutating) refresh
     * runs once per cell rather than every tick — keyed PER sub-level so the cache tracks the
     * contraption's lifetime, not the world's. A {@link WeakHashMap} on the {@code ServerSubLevel}
     * identity auto-evicts a disassembled sub-level (bounding growth), and a contraption RE-assembled
     * at reused plot coords is a NEW {@code ServerSubLevel} → a cache miss → its pipes re-heal (a
     * flat position set would keep the stale shape and the "no flow until poke" bug would return).
     * Server-thread only; also cleared wholesale on server stop.
     */
    private static final Map<ServerSubLevel, Set<BlockPos>> REFRESHED = new WeakHashMap<>();

    /**
     * Last logical pose of each sub-level. Its projected world heads depend on this pose, so a
     * contraption that moves or tilts changes every column's head with NO block event; comparing
     * against the last pose lets us wake such a sub-level at once instead of letting it lag its motion
     * by the sleep heartbeat. WeakHashMap on the sub-level identity, like {@link #REFRESHED}.
     */
    private static final Map<ServerSubLevel, PoseSample> POSES = new WeakHashMap<>();

    /**
     * Pipe cells per plot chunk, so the per-tick seed walks a prebuilt list instead of re-resolving
     * every block entity on the contraption through the chunk map and behaviour lookup. Keyed on the
     * {@link PlotChunkHolder} identity (weak, like {@link #REFRESHED}): re-assembly and plot growth
     * mint new holders, so their caches start fresh. Invalidation is a fingerprint, not an event:
     * the chunk's block-entity COUNT catches any add or remove within a tick (player edits also fire
     * the normal place/break wakes independently of this list), and a full rescan every
     * {@link #RESCAN_TICKS} catches the count-neutral residue — the same staleness the engine's idle
     * heartbeat already accepts elsewhere. A stale EXTRA position seeds a dead cell harmlessly.
     */
    private static final Map<PlotChunkHolder, ChunkPipes> PIPES = new WeakHashMap<>();

    private static final int RESCAN_TICKS = 20;
    private static final double POSE_EPS_SQ = 1.0e-6;
    private static final double ROT_EPS = 1.0e-5;

    private record PoseSample(Vector3d position, Quaterniond orientation) {}

    private record ChunkPipes(int beCount, long scannedAt, List<BlockPos> pipes) {}

    private enum Motion { NONE, MOVED, ASSEMBLED }

    private SableSubLevelDriver() {}

    static void seed(ServerLevel level, BiConsumer<Level, BlockPos> seed) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;
        boolean refresh = PipesNPhysicsConfig.ENABLE_SUBLEVEL_CONNECTION_REFRESH.get();
        long now = level.getGameTime();
        for (ServerSubLevel sub : container.getAllSubLevels()) {
            if (sub.isRemoved()) continue;
            // A moved/tilted contraption re-projects every head, so escalate its seeds to an URGENT
            // wake (bypassing the QUIET sleep) for the tick the motion is detected; otherwise a settled
            // sub-level network only re-equalizes on its heartbeat, lagging the motion in 1s stair-steps.
            // markMoved, not markChanged: motion re-solves the network but does not reshape it, so the
            // cached graph must survive — else a cruising contraption pays a full rebuild every tick.
            // FIRST SIGHT is the exception: assembly is a topology event, and a reused plot slot may
            // still carry the PREVIOUS contraption's cached graph — markChanged evicts it.
            Motion motion = poseChanged(sub);
            BiConsumer<Level, BlockPos> subSeed = switch (motion) {
                case ASSEMBLED -> EngineTickHandler::markChanged;
                case MOVED -> EngineTickHandler::markMoved;
                case NONE -> seed;
            };
            boolean moved = motion != Motion.NONE;
            Set<BlockPos> refreshed = refresh ? REFRESHED.computeIfAbsent(sub, k -> new HashSet<>()) : null;
            for (PlotChunkHolder holder : sub.getPlot().getLoadedChunks()) {
                for (BlockPos pos : pipeCells(holder, now)) {
                    if (refreshed != null && refreshed.add(pos)) refreshConnections(level, pos);
                    if (!moved && EngineTickHandler.isQuiet(level, pos, now)) continue;
                    subSeed.accept(level, pos);
                }
            }
        }
    }

    /**
     * This chunk's pipe cells, rescanned when its block-entity count changes or the cache ages out.
     * Rescans every tick when the network-cache toggle is off, so the debug escape hatch rules out
     * this cache too (a count-neutral event-less BE swap could otherwise lag the rescan interval).
     */
    private static List<BlockPos> pipeCells(PlotChunkHolder holder, long now) {
        Map<BlockPos, BlockEntity> blockEntities = holder.getChunk().getBlockEntities();
        ChunkPipes cached = PIPES.get(holder);
        if (cached != null && cached.beCount() == blockEntities.size()
                && now - cached.scannedAt() < RESCAN_TICKS
                && PipesNPhysicsConfig.ENABLE_NETWORK_CACHE.get()) {
            return cached.pipes();
        }
        // The block entities are already in hand as the map's values, so membership is the same
        // behaviour lookup the pipe heartbeat mixin uses — no per-position chunk re-resolution.
        List<BlockPos> pipes = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockEntity> entry : blockEntities.entrySet()) {
            if (entry.getValue() instanceof SmartBlockEntity smart
                    && smart.getBehaviour(FluidTransportBehaviour.TYPE) != null) {
                pipes.add(entry.getKey().immutable());
            }
        }
        List<BlockPos> frozen = List.copyOf(pipes);
        PIPES.put(holder, new ChunkPipes(blockEntities.size(), now, frozen));
        return frozen;
    }

    /** Whether this sub-level moved or rotated since the last tick, with first sight kept distinct. */
    private static Motion poseChanged(ServerSubLevel sub) {
        Pose3dc pose = sub.logicalPose();
        if (pose == null) return Motion.NONE;
        Vector3d position = new Vector3d(pose.position());
        Quaterniond orientation = new Quaterniond(pose.orientation());
        PoseSample last = POSES.put(sub, new PoseSample(position, orientation));
        if (last == null) return Motion.ASSEMBLED;
        return last.position().distanceSquared(position) > POSE_EPS_SQ
                || 1.0 - Math.abs(last.orientation().dot(orientation)) > ROT_EPS
                ? Motion.MOVED : Motion.NONE;
    }

    /**
     * Recompute one pipe's connection shape from its neighbours — the {@code updateShape} the raw
     * assembly skipped — and write it back if it changed, waking the network so it re-solves with the
     * now-correct topology. Uses {@code UPDATE_KNOWN_SHAPE} so the write does not cascade neighbour
     * updates: every pipe on the sub-level is refreshed independently off {@code isPipe} geometry, so
     * no propagation is needed.
     */
    private static void refreshConnections(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        BlockState updated = Block.updateFromNeighbourShapes(state, level, pos);
        if (updated == state) return;
        level.setBlock(pos, updated, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        EngineTickHandler.markChanged(level, pos);
    }

    /** Drop the per-sub-level caches — called on server stop so a fresh world starts clean. */
    static void clear() {
        REFRESHED.clear();
        POSES.clear();
        PIPES.clear();
    }
}
