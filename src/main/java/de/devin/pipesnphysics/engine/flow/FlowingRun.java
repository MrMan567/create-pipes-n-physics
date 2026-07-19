package de.devin.pipesnphysics.engine.flow;

import de.devin.pipesnphysics.PipesNPhysics;
import de.devin.pipesnphysics.engine.graph.Edge;
import de.devin.pipesnphysics.engine.graph.PipeGeometry;
import de.devin.pipesnphysics.engine.store.PipeStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.List;
import java.util.Set;

/**
 * One edge carrying solved flow in one fluid pass: the cells oriented upstream→downstream, the
 * solved rate, and the per-tick EXIT budget (how much may still leave its downstream
 * end — consumers past a junction pull against it before this run itself ticks).
 *
 * {@link #tick()} is the whole brigade for this run, in the order that makes a chain move one
 * step everywhere in the same tick: deliver at the downstream end (or top up the junction slot),
 * shift every internal boundary forward by at most the solved rate, then take intake at the
 * upstream end. Movement is PLUG FLOW at the run's {@linkplain FlowNetwork#flowDepthMb flow
 * depth}: fluid entering a dry cell parks until the feeding cell carries the depth, so the
 * visible front is a coherent column — never a smear — and a sink only receives once the column
 * has actually arrived (a tail cell at depth). A fast run's depth is a full cell (the old FULL
 * gates); a trickle runs as a shallow stream that still primes cell by cell.
 */
final class FlowingRun {
    private final BrigadePass pass;
    private final FlowNetwork network;
    final Edge edge;
    private final FluidStack fluid;
    /** The solved rate: the most any single boundary of this run moves this tick, in mB. */
    private final int solvedRateMb;
    /** The plug depth this run flows at — what every gate below requires instead of a full cell. */
    private final int flowDepthMb;
    private final boolean flowsAToB;
    /** Cells upstream→downstream; empty when the endpoints touch directly (or cells hold nothing). */
    private final List<BlockPos> cells;
    private int exitBudget;

    FlowingRun(BrigadePass pass, FlowNetwork network, Edge edge, FluidStack fluid,
               int solvedRateMb, boolean flowsAToB) {
        this.pass = pass;
        this.network = network;
        this.edge = edge;
        this.fluid = fluid;
        this.solvedRateMb = solvedRateMb;
        this.flowDepthMb = FlowNetwork.flowDepthMb(solvedRateMb, network.cellCapacity);
        this.flowsAToB = flowsAToB;
        this.cells = network.cellCapacity <= 0 ? List.of()
                : flowsAToB ? edge.pipes() : edge.pipes().reversed();
        this.exitBudget = solvedRateMb;
    }

    int upstreamNode() {
        return flowsAToB ? edge.a() : edge.b();
    }

    int downstreamNode() {
        return flowsAToB ? edge.b() : edge.a();
    }

    void tick() {
        deliver();
        shiftForward();
        intake();
        stampFlowAnimation();
    }

    /**
     * The downstream end: pour an arrived column into a sink reservoir, pull straight through a
     * zero-cell wire, or top up a junction/gate slot. Pass-through consumers were already served
     * (they ran first and PULLED via {@link #pullFromTail}); what they didn't take backs up here.
     */
    private void deliver() {
        Reservoir sink = network.reservoirAt(downstreamNode());
        if (sink != null) {
            if (cells.isEmpty()) deliverThroughWire(sink);
            else deliverFromTail(sink);
            return;
        }
        PipeStore.Store slot = network.slotAt(downstreamNode());
        if (slot == null) return;
        if (cells.isEmpty()) {
            topUpSlotThroughWire(slot);
            return;
        }
        PipeStore.Store tail = network.cellAt(cells.getLast());
        if (tail != null) {
            int moved = plugMove(tail, slot, exitBudget);
            exitBudget -= moved;
            pass.ledger().moved(edge, moved);
        }
    }

    /**
     * A zero-cell run into a junction/gate slot has no tail cell to conduct with, so it tops the
     * slot up by pulling straight through the wire — exactly what {@link #deliverThroughWire}
     * does for a reservoir sink. Without this a pump wedged flush against a junction never moves
     * anything: the slot stays empty, the consumer past the junction stays gated on it (a slot
     * passes fluid only once at the consumer's flow depth), and the whole line reads solved flow
     * with zero actual.
     */
    private void topUpSlotThroughWire(PipeStore.Store slot) {
        int want = Math.min(exitBudget, slot.room(fluid));
        if (want <= 0) return;
        Reservoir source = network.reservoirAt(upstreamNode());
        int got = source != null
                ? source.drain(fluid, want)
                : pass.pullArrivingAt(upstreamNode(), fluid, want, flowDepthMb, pass.freshVisitSet());
        if (got <= 0) return;
        slot.insert(fluid, got);
        exitBudget -= got;
        pass.ledger().moved(edge, got);
    }

    /** Every internal boundary moves at most the solved rate, downstream-first (one step per tick). */
    private void shiftForward() {
        for (int i = cells.size() - 2; i >= 0; i--) {
            PipeStore.Store from = network.cellAt(cells.get(i));
            PipeStore.Store to = network.cellAt(cells.get(i + 1));
            if (from == null || to == null) continue;
            pass.ledger().moved(edge, plugMove(from, to, solvedRateMb));
        }
    }

    /** The upstream end: refill the head cell from a source reservoir or through the node. */
    private void intake() {
        if (cells.isEmpty()) return;
        PipeStore.Store head = network.cellAt(cells.getFirst());
        if (head == null) return;
        int want = Math.min(solvedRateMb, head.room(fluid));
        if (want <= 0) return;
        Reservoir source = network.reservoirAt(upstreamNode());
        int got = source != null
                ? source.drain(fluid, want)
                : pass.pullArrivingAt(upstreamNode(), fluid, want, flowDepthMb, pass.freshVisitSet());
        if (got > 0) {
            head.insert(fluid, got);
            pass.ledger().moved(edge, got);
        }
    }

    /**
     * Deliver the tail cell's own fluid (plug flow may carry a different fluid than the pass)
     * into the sink — but the column must ARRIVE first: a tail cell still filling toward the flow
     * depth delivers nothing (the settle phase drains the last residue once flow stops).
     */
    private void deliverFromTail(Reservoir sink) {
        PipeStore.Store tail = network.cellAt(cells.getLast());
        if (tail == null || tail.amount() < flowDepthMb) return;
        int budget = Math.min(exitBudget, tail.amount());
        if (budget <= 0) return;
        int filled = sink.fill(tail.fluid(), budget);
        if (filled <= 0) return;
        tail.extract(filled);
        exitBudget -= filled;
        pass.ledger().moved(edge, filled);
    }

    /** A zero-cell edge is a wire: pull straight through from the upstream side into the sink. */
    private void deliverThroughWire(Reservoir sink) {
        int budget = exitBudget;
        int want = sink.probeFill(fluid, budget);
        if (want <= 0) return;
        Reservoir source = network.reservoirAt(upstreamNode());
        int got = source != null
                ? source.drain(fluid, want)
                : pass.pullArrivingAt(upstreamNode(), fluid, want, flowDepthMb, pass.freshVisitSet());
        if (got <= 0) return;
        int filled = sink.fill(fluid, got);
        if (filled < got) reinsertLeftover(got - filled);
        exitBudget -= filled;
        pass.ledger().moved(edge, filled);
    }

    /**
     * Let a downstream consumer take up to {@code amount} out of this run's downstream end —
     * from a tail cell at this run's flow depth (the column must have arrived, plug flow), or
     * straight through a wire — bounded by the remaining exit budget.
     */
    int pullFromTail(FluidStack wanted, int amount, Set<Integer> visited) {
        int budget = Math.min(amount, exitBudget);
        if (budget <= 0) return 0;
        int got;
        if (cells.isEmpty()) {
            got = pass.pullArrivingAt(upstreamNode(), wanted, budget, flowDepthMb, visited);
        } else {
            PipeStore.Store tail = network.cellAt(cells.getLast());
            if (tail == null || tail.amount() < flowDepthMb
                    || !FluidStack.isSameFluidSameComponents(tail.fluid(), wanted)) {
                return 0;
            }
            got = tail.extract(budget).getAmount();
        }
        exitBudget -= got;
        pass.ledger().moved(edge, got);
        return got;
    }

    /**
     * Plug flow, not a smear: fluid entering a DRY cell parks there until the feeding cell
     * carries the flow depth. A wet destination (the front itself, or a draining column) moves
     * freely.
     */
    private int plugMove(PipeStore.Store from, PipeStore.Store to, int amount) {
        if (to.amount() <= 0 && from.amount() < flowDepthMb) return 0;
        return from.moveInto(to, amount);
    }

    /**
     * Fluid a two-phase wire move could not place after all is refunded to the source, never
     * voided silently. A junction pull can span several feeders, so a leftover has no single
     * owner to return to past the first reservoir; the SIMULATE probe makes this branch
     * near-unreachable, hence warn-and-void as the last resort.
     */
    private void reinsertLeftover(int leftover) {
        Reservoir source = network.reservoirAt(upstreamNode());
        if (source != null) leftover -= source.refund(fluid, leftover);
        if (leftover > 0) {
            PipesNPhysics.LOGGER.warn("Voided {} mB of {} at {} (sink accepted less than simulated)",
                    leftover, fluid.getFluid(), network.graph.node(upstreamNode()).pos());
        }
    }

    /** Stamp the scroll direction + rate on the wet cells; dry cells ahead of the front stay still. */
    private void stampFlowAnimation() {
        double rate = network.cellCapacity > 0 ? solvedRateMb / (double) network.cellCapacity : 0;
        BlockPos downstream = network.graph.node(downstreamNode()).pos();
        for (int i = 0; i < cells.size(); i++) {
            PipeStore.Store cell = network.cellAt(cells.get(i));
            if (cell == null) continue;
            if (cell.amount() <= 0) {
                cell.clearFlow();
                continue;
            }
            BlockPos next = i < cells.size() - 1 ? cells.get(i + 1) : downstream;
            cell.setFlow(PipeGeometry.between(cells.get(i), next), rate);
        }
    }
}
