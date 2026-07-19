package de.devin.pipesnphysics.engine.flow;

import de.devin.pipesnphysics.engine.Solution;
import de.devin.pipesnphysics.engine.graph.Edge;
import de.devin.pipesnphysics.engine.graph.Node;
import de.devin.pipesnphysics.engine.graph.PipeGeometry;
import de.devin.pipesnphysics.engine.store.PipeStore;
import net.minecraft.core.BlockPos;

import java.util.Set;

/**
 * Settles every edge the brigade did NOT flow this tick (see {@link SettlingRun} for the physics)
 * plus the junction/shut-valve buffer slots, and clears their scroll stamps. A held or backed-up
 * run — a pump pressing a shut gate or a full sink, a dead conduit against a full tank — settles
 * FILL-ONLY: pressure keeps packing the line toward its reachable ceiling, but never lets it
 * drain back out.
 */
public final class SettlePass {
    private final FlowNetwork network;
    private final FlowLedger ledger;
    private final Solution solution;

    public SettlePass(FlowNetwork network, FlowLedger ledger, Solution solution) {
        this.network = network;
        this.ledger = ledger;
        this.solution = solution;
    }

    public void execute(Set<Integer> flowedEdges) {
        if (network.cellCapacity <= 0) return;
        for (Edge edge : network.graph.edges()) {
            if (flowedEdges.contains(edge.index())) {
                // A flowing run still PRESSURIZES: its submerged cells top up from the end
                // reservoirs toward the waterline alongside the flow (fill-only, no
                // redistribution — see SettlingRun.topUp), source-side-first. Stamps stay.
                if (new SettlingRun(network, ledger, solution, edge, false).topUp()) {
                    ledger.markSettling();
                }
                continue;
            }
            if (new SettlingRun(network, ledger, solution, edge, solution.isBackedUp(edge.index())).settle()) {
                ledger.markSettling();
            }
            network.clearFlowStamps(edge);
        }
        for (Node node : network.graph.nodes()) {
            if (node.isJunction() || node.isClosedGate()) settleSlot(node, flowedEdges);
        }
    }

    /**
     * A junction/gate buffer settles against its own node head, exchanging with the adjacent edge
     * end cells — this is what fills (and renders) a dead-end cell pressed against a solid block.
     */
    private void settleSlot(Node node, Set<Integer> flowedEdges) {
        PipeStore.Store slot = network.slotAt(node.index());
        if (slot == null) return;
        slot.clearFlow();
        // A gas slot is HELD, exactly like a gas run (SettlingRun bails): the waterline target
        // below mixes the node head with world Y, and a gas's INVERTED head reads "drain to 0" —
        // the slot then bled its gas into an idle edge every settle tick while the brigade pushed
        // it back, an endless churn the player sees as the pipe constantly refilling from the top
        // (diagnostic signature: actual= exactly the settle rate on an idle edge).
        if (SettlingRun.lighterThanAir(slot.fluid())) return;
        Double head = solution.nodeHeads().get(node.index());
        if (head == null) return;
        int target = (int) Math.round(
                network.windowFill(node.pos(), head) * network.cellCapacity);
        int rate = SettlingRun.settleRate(network.cellCapacity);
        for (Edge edge : network.graph.edgesOf(node.index())) {
            // The brigade owns the cells of edges it flowed this tick: exchanging with them here
            // would move fluid outside their exit budgets and trim the slot below its pooled
            // depth, breaking the "a slot conducts only once at flow depth" plug gate next tick.
            if (flowedEdges.contains(edge.index())) continue;
            BlockPos adjacent = PipeGeometry.adjacentCell(network.graph, edge, node.index());
            if (adjacent == null || adjacent.equals(node.pos())) continue;
            PipeStore.Store cell = network.cellAt(adjacent);
            if (cell == null) continue;
            if (slot.amount() > target) {
                exchange(edge, slot, cell, Math.min(slot.amount() - target, rate));
            } else if (slot.amount() < target && cell.amount() > 0
                    && !SettlingRun.lighterThanAir(cell.fluid())) {
                // The mirror guard: never pull a neighbouring cell's GAS toward a liquid target.
                exchange(edge, cell, slot, Math.min(target - slot.amount(), rate));
            }
        }
    }

    private void exchange(Edge edge, PipeStore.Store from, PipeStore.Store to, int amount) {
        int moved = from.moveInto(to, amount);
        if (moved > 0) {
            ledger.moved(edge, moved);
            ledger.markSettling();
        }
    }
}
