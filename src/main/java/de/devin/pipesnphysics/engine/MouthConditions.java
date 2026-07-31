package de.devin.pipesnphysics.engine;

import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.engine.boundary.BoundaryColumn;
import de.devin.pipesnphysics.engine.boundary.OpenEndPipes;
import de.devin.pipesnphysics.engine.graph.Edge;
import de.devin.pipesnphysics.engine.graph.Graph;
import de.devin.pipesnphysics.engine.graph.Node;
import de.devin.pipesnphysics.engine.graph.PipeGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * What the open mouths of ONE network may drink this tick — resolved once per solve and asked per
 * mouth, so the solve and the executor can never disagree about a mouth's role.
 *
 * Two network-wide facts decide it. A network that SPILLED recently may not suck a finite source
 * back in anywhere on it (its own spit, or a sibling mouth's after the spill flowed over); lakes
 * and cauldrons are unaffected. And a mouth a RUNNING PUMP pulls on is under real suction, so it
 * may draw a fluid block in through a HORIZONTAL face — otherwise a spill outlet only, since a
 * sideways mouth sits at the elevation of whatever it spills and unpowered intake there just
 * reclaims its own spilled block tick after tick. Suction is the discriminator the vertical rule
 * approximated: a pump cannot spill out of its own suction flank, so the mouth it evacuates is
 * never the mouth it feeds.
 */
public final class MouthConditions {
    /** No network context — every mouth reads as unspilled and unpumped (diagnostics only). */
    public static final MouthConditions NONE = new MouthConditions(false, Set.of());

    private final boolean spilled;
    private final Set<BlockPos> pulled;

    private MouthConditions(boolean spilled, Set<BlockPos> pulled) {
        this.spilled = spilled;
        this.pulled = pulled;
    }

    public static MouthConditions of(Level level, Graph graph) {
        int cooldown = PipesNPhysicsConfig.OPEN_END_INTAKE_COOLDOWN_TICKS.get();
        boolean spilled = false;
        boolean anyMouth = false;
        for (Node node : graph.nodes()) {
            if (!node.isOpenEnd()) continue;
            anyMouth = true;
            if (OpenEndPipes.recentlySpilled(level, node.pos(), cooldown)) {
                spilled = true;
                break;
            }
        }
        return new MouthConditions(spilled, anyMouth ? pulledMouths(level, graph) : Set.of());
    }

    /** The column behind one OPEN_END node under this tick's conditions. */
    public BoundaryColumn column(Level level, Node mouth) {
        return BoundaryColumn.forOpenEnd(level, mouth, spilled, pulled.contains(mouth.pos()));
    }

    /**
     * Every mouth a running pump pulls on: walk out of each pump's SUCTION flank and collect the
     * open ends the vacuum reaches. The walk stops at whatever breaks that vacuum — another pump
     * (its flank check valves let nothing through) or a reservoir (a free surface: a pump sucking
     * "through" a tank just drains the tank) — and carries on through pipe runs and junctions.
     */
    private static Set<BlockPos> pulledMouths(Level level, Graph graph) {
        Set<BlockPos> mouths = new HashSet<>();
        Set<Integer> visited = new HashSet<>();
        for (Node pump : graph.pumps()) {
            BlockPos pullCell = pump.pullCell();
            if (pullCell == null || !FlowSolver.isPumpRunning(level, pump)) continue;
            for (Edge suction : graph.edgesOf(pump.index())) {
                if (pullCell.equals(PipeGeometry.adjacentCell(graph, suction, pump.index()))) {
                    walkSuction(graph, suction.other(pump.index()), visited, mouths);
                }
            }
        }
        return mouths;
    }

    private static void walkSuction(Graph graph, int from, Set<Integer> visited, Set<BlockPos> mouths) {
        Deque<Integer> pending = new ArrayDeque<>();
        pending.add(from);
        while (!pending.isEmpty()) {
            int index = pending.poll();
            if (!visited.add(index)) continue;
            Node node = graph.node(index);
            if (node.isOpenEnd()) {
                mouths.add(node.pos());
            } else if (!node.isPump() && !node.isHandler()) {
                for (Edge edge : graph.edgesOf(index)) pending.add(edge.other(index));
            }
        }
    }
}
