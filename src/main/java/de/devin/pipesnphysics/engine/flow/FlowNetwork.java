package de.devin.pipesnphysics.engine.flow;

import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.compat.SableCompat;
import de.devin.pipesnphysics.engine.boundary.BoundaryColumn;
import de.devin.pipesnphysics.engine.boundary.OpenEndPipes;
import de.devin.pipesnphysics.engine.graph.Edge;
import de.devin.pipesnphysics.engine.graph.Graph;
import de.devin.pipesnphysics.engine.graph.Node;
import de.devin.pipesnphysics.engine.store.PipeStore;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One executed tick's object view of a pipe network: every pipe cell as a {@link PipeStore.Store},
 * every endpoint as a {@link Reservoir}, and the junction/shut-valve buffer slots — resolved FRESH
 * from the world (it may have changed since the solve) and shared by the brigade and settle
 * phases, so per-tick endpoint budgets are enforced across both.
 *
 * A multiblock tank reached from several graph nodes resolves to ONE {@code Reservoir} (deduped
 * by column identity), which is what makes its give/take budgets per-tank rather than per-pipe.
 * Built by {@code PipeFlowExecutor.run} once per solved tick and discarded after {@link #flush()}
 * — that lifetime is what makes the budgets per-tick.
 */
public final class FlowNetwork {
    final ServerLevel level;
    final Graph graph;
    /** mB one pipe cell holds; 0 = pipes store nothing and every run degrades to a wire. */
    final int cellCapacity = PipeStore.capacityMb();

    private final Map<BlockPos, PipeStore.Store> cells = new HashMap<>();
    private final Map<Integer, Reservoir> reservoirs = new HashMap<>();

    public FlowNetwork(ServerLevel level, Graph graph) {
        this.level = level;
        this.graph = graph;

        // If ANY open end on this network spilled recently, hold off finite-source intake
        // everywhere on it — the network must not suck back what it just spat out.
        int cooldown = PipesNPhysicsConfig.OPEN_END_INTAKE_COOLDOWN_TICKS.get();
        boolean networkSpilled = false;
        for (Node node : graph.nodes()) {
            if (node.isOpenEnd() && OpenEndPipes.recentlySpilled(level, node.pos(), cooldown)) {
                networkSpilled = true;
                break;
            }
        }
        int maxFlow = PipesNPhysicsConfig.MAX_FLOW_PER_ENDPOINT.get();
        Map<BlockPos, Reservoir> reservoirByIdentity = new LinkedHashMap<>();
        for (Node node : graph.nodes()) {
            BoundaryColumn column = node.isHandler() ? BoundaryColumn.resolve(level, node)
                    : node.isOpenEnd() ? BoundaryColumn.forOpenEnd(level, node, networkSpilled)
                    : null;
            if (column == null) continue;
            Reservoir reservoir = reservoirByIdentity.computeIfAbsent(column.identity(),
                    k -> new Reservoir(level, column, maxFlow));
            reservoirs.put(node.index(), reservoir);
        }
    }

    /** The stored-fluid cell at a pipe position, or null where nothing there can hold pipe fluid. */
    PipeStore.Store cellAt(BlockPos pos) {
        return cells.computeIfAbsent(pos.immutable(), k -> PipeStore.at(level, k));
    }

    /** The reservoir behind a graph node, or null for pass-through nodes (pumps, junctions). */
    Reservoir reservoirAt(int nodeIndex) {
        return reservoirs.get(nodeIndex);
    }

    /** The one-cell buffer of a junction or shut-valve node; pumps and open ends hold nothing. */
    PipeStore.Store slotAt(int nodeIndex) {
        if (cellCapacity <= 0) return null;
        Node node = graph.node(nodeIndex);
        if (!node.isJunction() && !node.isClosedGate()) return null;
        return cellAt(node.pos());
    }

    /** A cell's bottom elevation in true world space (Sable-projected). */
    double cellBottomY(BlockPos pos) {
        return SableCompat.getWorldY(level, pos) - 0.5;
    }

    /**
     * A pipe cell's stored volume lives in its BORE — the 6/16-wide fluid window the renderer
     * draws — not the full block. Hydrostatic fill↔height conversions must use the bore, or a
     * settled pipe's rendered surface sits visibly off the tank waterline it equalized with
     * ("fluid height in the pipe doesn't match the tank" report).
     */
    static final double BORE_BOTTOM = 0.5 - 3.0 / 16; // matches the renderer's PIPE_RADIUS
    static final double BORE_HEIGHT = 2 * (3.0 / 16);

    /** The bottom of a cell's bore in true world space. */
    double boreBottomY(BlockPos pos) {
        return cellBottomY(pos) + BORE_BOTTOM;
    }

    /** The fraction of a cell's bore sitting below the given surface line, clamped 0..1. */
    double boreFill(BlockPos pos, double line) {
        return Math.clamp((line - boreBottomY(pos)) / BORE_HEIGHT, 0, 1);
    }

    /** A cell's centre elevation in true world space (Sable-projected). */
    double cellCenterY(BlockPos pos) {
        return SableCompat.getWorldY(level, pos);
    }

    /** Clear the scroll-animation stamps on an idle edge's cells. */
    void clearFlowStamps(Edge edge) {
        for (BlockPos pos : edge.pipes()) {
            PipeStore.Store cell = cellAt(pos);
            if (cell != null) cell.clearFlow();
        }
    }

    /** Send one sync per changed cell — the end of the tick. */
    public void flush() {
        for (PipeStore.Store cell : cells.values()) {
            if (cell != null) cell.flush();
        }
    }
}
