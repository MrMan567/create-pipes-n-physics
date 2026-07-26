package de.devin.pipesnphysics.engine;

import de.devin.pipesnphysics.engine.graph.Graph;
import de.devin.pipesnphysics.engine.graph.GraphBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Top-level entry point for the fluid engine.
 *
 * One tick of one network:
 *
 *   1. {@link GraphBuilder#build} walks Create pipes outward from a seed position
 *      and contracts the network into nodes (tanks, pumps, junctions) and edges
 *      (pipe runs).
 *
 *   2. {@link FlowSolver#solve} reads the live tank fills and pump speeds, runs the
 *      implicit hydraulic solve (see {@link de.devin.pipesnphysics.engine.solve.NetworkSolver}),
 *      and returns a {@link Solution}: per-edge flow plus the per-fluid passes for this
 *      tick. Solving never mutates the world.
 *
 *   3. {@link #apply} executes the passes as conserved plug flow through the pipes'
 *      own stored volume ({@link PipeFlowExecutor}): sources drain into the pipes,
 *      sinks fill from what exits them, idle runs settle toward the hydrostatic
 *      profile. Every handler exchange is simulate-then-execute, so the engine
 *      interoperates with any mod's containers and can neither duplicate nor destroy
 *      fluid; every internal move is paired integer mB between cells.
 *
 * Fluid lives in the endpoint handlers and in the pipes' saved per-cell content
 * ({@link PipeFluidCell}), which rides the block entities through save, chunk unload,
 * and contraption assembly — reload resumes with the exact in-transit volume.
 */
public final class FluidEngine {
    private FluidEngine() {}

    /** Build a graph without solving. Used by /pipegraph and the overlay. */
    public static Graph buildGraph(Level level, BlockPos seedPos) {
        return GraphBuilder.build(level, seedPos);
    }

    /** Build and solve a FRESH graph without applying anything. Used by the visualizer. */
    public static Solution solveFresh(Level level, BlockPos seedPos) {
        Graph graph = GraphBuilder.build(level, seedPos);
        return FlowSolver.solve(level, graph);
    }

    /**
     * Execute one solved tick: run the flow brigade and the idle settle over the network's
     * stored pipe volume. Handlers are resolved fresh here — the world may have changed since
     * the solve — and every exchange is clamped by what the source really gives and the sink
     * really takes, so a stale plan degrades to a smaller (or zero) movement instead of an error.
     */
    public static PipeFlowExecutor.Actuals apply(Level level, Graph graph, Solution solution) {
        return PipeFlowExecutor.run(level, graph, solution);
    }
}
