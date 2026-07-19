package de.devin.pipesnphysics.client.ponder;

import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.engine.FluidEngine;
import de.devin.pipesnphysics.engine.FlowSolver;
import de.devin.pipesnphysics.engine.Solution;
import de.devin.pipesnphysics.engine.graph.Graph;
import de.devin.pipesnphysics.engine.graph.GraphBuilder;
import net.createmod.ponder.api.level.PonderLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Runs the fluid engine over a Ponder scene's virtual level, so scenes demonstrate the mod's real
 * physics (height-bounded reach, siphons, communicating vessels) instead of Create's stock transport.
 *
 * Ponder is client-side and the server never ticks it, so this mirrors the network loop of
 * {@link de.devin.pipesnphysics.engine.EngineTickHandler} but with LOCAL per-tick state — no
 * {@code GraphCache}/{@code DIRTY}/{@code QUIET} (server-thread) and no sleep: scenes are tiny, so a
 * fresh build→solve→apply every tick is cheap. Every server-only side effect (relay learning,
 * open-end latches) no-ops on this client level via the {@code isClientSide()} gates in the boundary
 * package, so a ponder run can neither race nor pollute the real game's engine state.
 */
public final class PonderEngineDriver {
    /** Scene levels whose engine is frozen — held so the fluid stays put for a narration beat. */
    private static final Set<PonderLevel> FROZEN = Collections.newSetFromMap(new WeakHashMap<>());

    private PonderEngineDriver() {}

    /** Freeze (or resume) the engine for a scene's level; a frozen scene holds its current fluid state. */
    public static void setFrozen(PonderLevel level, boolean frozen) {
        if (frozen) FROZEN.add(level); else FROZEN.remove(level);
    }

    /** Solve and apply every pipe network in the scene once, deduped by graph coverage. */
    public static void tick(PonderLevel level) {
        if (!PipesNPhysicsConfig.ENABLE_ENGINE.get() || !PipesNPhysicsConfig.ENABLE_PONDER_ENGINE.get()) return;
        if (FROZEN.contains(level)) return;

        Set<BlockPos> covered = new HashSet<>();
        for (BlockEntity be : pipeCells(level)) {
            BlockPos pos = be.getBlockPos();
            if (covered.contains(pos)) continue;
            BlockPos seed = GraphBuilder.findSeed(level, pos);
            if (seed == null || covered.contains(seed)) continue;
            Graph graph = GraphBuilder.build(level, seed);
            if (graph.isEmpty()) continue;
            covered.addAll(graph.coverage());
            Solution solution = FlowSolver.solve(level, graph);
            FluidEngine.apply(level, graph, solution);
        }
    }

    /** The scene's pipe cells, snapshotted so applying a network can't disturb the iteration. */
    private static List<BlockEntity> pipeCells(PonderLevel level) {
        List<BlockEntity> pipes = new ArrayList<>();
        for (BlockEntity be : level.getBlockEntities()) {
            if (be instanceof SmartBlockEntity sbe && sbe.getBehaviour(FluidTransportBehaviour.TYPE) != null) {
                pipes.add(be);
            }
        }
        return pipes;
    }
}
