package de.devin.pipesnphysics.engine.flow;

import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.engine.Solution;
import de.devin.pipesnphysics.engine.graph.Edge;
import de.devin.pipesnphysics.engine.graph.Node;
import de.devin.pipesnphysics.engine.graph.PipeGeometry;
import de.devin.pipesnphysics.engine.store.PipeStore;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One fluid pass of the brigade: every edge the solver flowed this fluid over becomes a
 * {@link FlowingRun}, and they tick CONSUMERS-FIRST (reverse-topological along the flow) so a
 * chain moves one step everywhere in the same tick — a run past a junction pulls from its
 * feeders' tail cells before the feeders themselves advance. Cycles (a pump ring through
 * junctions) fall out of the order and are appended as-is; the seam edge lags one tick, which a
 * primed ring never notices.
 *
 * The pass also installs each source reservoir's LIP drain cap (it knows the out-flowing
 * openings), which then also bounds the settle phase — same physical opening, same rule.
 */
public final class BrigadePass {
    private final FlowNetwork network;
    private final FlowLedger ledger;
    private final FluidStack fluid;
    private final Map<Integer, FlowingRun> runs = new HashMap<>();
    /** The runs flowing INTO each pass-through node — the feeders a consumer may pull from. */
    private final Map<Integer, List<FlowingRun>> feedersInto = new HashMap<>();

    public BrigadePass(FlowNetwork network, FlowLedger ledger, Solution.FlowPass pass) {
        this.network = network;
        this.ledger = ledger;
        this.fluid = pass.fluid();
        for (Edge edge : network.graph.edges()) {
            double flow = pass.edgeFlow()[edge.index()];
            int solvedRateMb = (int) Math.round(Math.abs(flow));
            // A sub-1 mB/t trickle carries no whole millibucket this tick; the edge settles
            // instead, so equalization still finishes.
            if (solvedRateMb < 1) continue;
            boolean flowsAToB = flow > 0;
            FlowingRun run = new FlowingRun(this, network, edge, fluid, solvedRateMb, flowsAToB);
            runs.put(edge.index(), run);
            feedersInto.computeIfAbsent(run.downstreamNode(), k -> new ArrayList<>()).add(run);
        }
    }

    /** The edge indices that carry solved flow in this pass; the settle phase skips them. */
    public Set<Integer> flowingEdges() {
        return runs.keySet();
    }

    FlowLedger ledger() {
        return ledger;
    }

    /** One brigade tick: install the source lip caps, then tick every run consumers-first. */
    public void execute() {
        if (runs.isEmpty()) return;
        installLipCaps();
        for (FlowingRun run : consumersFirst()) {
            run.tick();
        }
    }

    /**
     * Fluid arriving at a node this tick, for a consumer pulling through it: a reservoir drains
     * on demand; a junction/gate node yields its SLOT — plug flow: the slot passes fluid on only
     * once FULL, so the junction cell visibly fills before anything continues past it (feeders
     * top it up in their own ticks); a slot-less pass-through (a pump) pulls straight from its
     * feeders' tails, recursing through wires. The visited set breaks pull cycles.
     */
    int pullArrivingAt(int nodeIndex, FluidStack wanted, int amount, Set<Integer> visited) {
        if (amount <= 0 || !visited.add(nodeIndex)) return 0;
        Reservoir reservoir = network.reservoirAt(nodeIndex);
        if (reservoir != null) return reservoir.drain(wanted, amount);

        PipeStore.Store slot = network.slotAt(nodeIndex);
        if (slot != null) {
            if (slot.amount() >= network.cellCapacity
                    && FluidStack.isSameFluidSameComponents(slot.fluid(), wanted)) {
                return slot.extract(amount).getAmount();
            }
            return 0;
        }
        int got = 0;
        for (FlowingRun feeder : feedersInto.getOrDefault(nodeIndex, List.of())) {
            if (got >= amount) break;
            got += feeder.pullFromTail(wanted, amount - got, visited);
        }
        return got;
    }

    Set<Integer> freshVisitSet() {
        return new HashSet<>();
    }

    /**
     * A run ticks only after every run OUT of its downstream PASS-THROUGH node has ticked (a
     * reservoir buffers, so it breaks the dependency chain). A junction slot buffers too, but it
     * only conducts once FULL (and does not exist in wire mode), so runs into a pass-through
     * still order behind the runs out of it — only a reservoir truly decouples. Kahn's algorithm
     * over that relation; cycle members that never free up are appended in discovery order.
     */
    private List<FlowingRun> consumersFirst() {
        Map<Integer, Integer> waitingOn = new HashMap<>();
        for (FlowingRun run : runs.values()) {
            int down = run.downstreamNode();
            waitingOn.put(run.edge.index(),
                    network.reservoirAt(down) != null ? 0 : runsOutOf(down));
        }
        ArrayDeque<FlowingRun> ready = new ArrayDeque<>();
        for (FlowingRun run : runs.values()) {
            if (waitingOn.get(run.edge.index()) == 0) ready.add(run);
        }
        List<FlowingRun> order = new ArrayList<>(runs.size());
        Set<Integer> done = new HashSet<>();
        while (!ready.isEmpty()) {
            FlowingRun run = ready.poll();
            if (!done.add(run.edge.index())) continue;
            order.add(run);
            for (FlowingRun feeder : feedersInto.getOrDefault(run.upstreamNode(), List.of())) {
                int left = waitingOn.merge(feeder.edge.index(), -1, Integer::sum);
                if (left == 0) ready.add(feeder);
            }
        }
        for (FlowingRun run : runs.values()) {
            if (!done.contains(run.edge.index())) order.add(run);
        }
        return order;
    }

    private int runsOutOf(int nodeIndex) {
        int count = 0;
        for (FlowingRun run : runs.values()) {
            if (run.upstreamNode() == nodeIndex) count++;
        }
        return count;
    }

    /**
     * The lip drain cap per source reservoir: at most half its volume above the LOWEST opening it
     * flows out of this tick. A pump actively pulling the reservoir is exempt when
     * {@code pumpDrainAnyLevel} is on (the dip-tube rule), as are gases and non-finite endpoints
     * (open mouths, hose pulleys, relays — no surface to flap across a lip). The cap's formula
     * and consumption live on {@link Reservoir#capDrawAtLip}.
     */
    private void installLipCaps() {
        boolean drainAny = PipesNPhysicsConfig.PUMP_DRAIN_ANY_LEVEL.get();
        Map<Reservoir, Double> lowestLip = new HashMap<>();
        for (Node node : network.graph.nodes()) {
            Reservoir reservoir = network.reservoirAt(node.index());
            if (reservoir == null || !reservoir.isFiniteReservoir() || reservoir.isInfiniteSource()) continue;
            FluidStack contents = reservoir.contents();
            if (!contents.isEmpty() && contents.getFluid().getFluidType().isLighterThanAir()) continue;
            for (Edge edge : network.graph.edgesOf(node.index())) {
                FlowingRun run = runs.get(edge.index());
                if (run == null || run.upstreamNode() != node.index()) continue;
                if (drainAny && pumpPullsFrom(edge, node.index())) continue;
                BlockPos opening = PipeGeometry.adjacentCell(network.graph, edge, node.index());
                lowestLip.merge(reservoir, network.cellBottomY(opening), Math::min);
            }
        }
        lowestLip.forEach(Reservoir::capDrawAtLip);
    }

    /** Whether the far end of {@code edge} is a pump whose PULL side faces this endpoint. */
    private boolean pumpPullsFrom(Edge edge, int nodeIndex) {
        Node far = network.graph.node(edge.other(nodeIndex));
        if (!far.isPump()) return false;
        BlockPos toward = PipeGeometry.adjacentCell(network.graph, edge, far.index());
        return toward != null && toward.equals(far.pullCell());
    }
}
