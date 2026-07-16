package de.devin.pipesnphysics.engine.flow;

import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.engine.FlowSolver;
import de.devin.pipesnphysics.engine.Solution;
import de.devin.pipesnphysics.engine.graph.Edge;
import de.devin.pipesnphysics.engine.graph.Node;
import de.devin.pipesnphysics.engine.graph.PipeGeometry;
import de.devin.pipesnphysics.engine.store.PipeStore;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.List;

/**
 * One idle (no solved flow) edge settling its stored fluid toward rest.
 *
 * The resting shape is a per-cell TARGET profile ({@link #hydrostaticTargets}): the flat
 * waterline at the lower connected surface — a finite reservoir's LIVE surface, never the display
 * head, which spreads a source's head across zero-flow branches and would paint a phantom surface
 * on an empty sink — or, on a CREST-broken run (a broken siphon), each leg's barometric column.
 * One {@link #settle()} then moves fluid toward that profile a rate-limited step per tick.
 * {@link #levelToTargets} lets excess flow to an adjacent deficit, cell to cell.
 * {@link #fallAndSpread} lets above-target fluid FALL into lower cells with room and SPREAD
 * between same-height cells (a crest arch drains off its corners), skipped for a HELD column
 * that pressure pins in place. {@link #exchangeWithReservoirs} has the run communicate with each
 * end reservoir through the CONDUCTING prefix of at-target cells (the shared waterline): the
 * first below-target cell past it draws in, the first above-target one pours out; a hysteresis
 * band keeps the tank-surface↔target feedback from ping-ponging, and dregs leave in one go.
 * {@link #primeFromPumps} lets a running pump pack its dead-headed line from its supply side
 * (its solved steady-state flow is 0, so nothing else would fill it). {@link #gravityPool} is
 * the fallback with no solve data at all (every reservoir gone or empty): plain gravity trickles
 * contents downhill and out of an open mouth at/below, so fluid pools in the dips instead of
 * hanging frozen in a riser.
 *
 * A HELD/backed-up run (a pump pressing a shut gate or a full sink, a dead conduit against a full
 * tank) settles FILL-ONLY: it may draw toward its reachable CEILING but never gives anything
 * back. Only genuine traps — a U-dip below every outlet — keep fluid at rest.
 */
final class SettlingRun {
    /** Per-boundary settle rate floor (mB/t); the working rate is a quarter cell, whichever is larger. */
    private static final int MIN_SETTLE_MB = 8;

    /** The shared per-boundary settle rate in mB/t — one definition for runs AND node slots. */
    static int settleRate(int cellCapacity) {
        return Math.max(cellCapacity / 4, MIN_SETTLE_MB);
    }

    /**
     * Whether this content is a lighter-than-air gas, which the settle HOLDS in place — runs and
     * node slots alike: every target here mixes heads with world elevations, and a gas's head is
     * INVERTED (fill − baseY, §4), so the liquid waterline math reads garbage for it.
     */
    static boolean lighterThanAir(FluidStack fluid) {
        return !fluid.isEmpty() && fluid.getFluid().getFluidType().isLighterThanAir();
    }
    /**
     * Endpoint-exchange hysteresis (fraction of a cell): drawing from a tank lowers its surface,
     * which lowers the targets, which would pour the same fluid straight back — so the exchange
     * acts only on an error beyond this band and the settled state sits stably inside it.
     */
    private static final double SETTLE_BAND = 0.1;
    /** Waterline deadband so a cell exactly at the surface doesn't flap. */
    static final double SURFACE_EPS = 0.05;

    private final FlowNetwork network;
    private final FlowLedger ledger;
    private final Solution solution;
    private final Edge edge;
    private final List<BlockPos> cells;
    /** Held/backed-up: pressure packs the line toward its ceiling but never lets it drain out. */
    private final boolean fillOnly;
    private final int rate;
    private final int hysteresisMb;

    SettlingRun(FlowNetwork network, FlowLedger ledger, Solution solution, Edge edge, boolean fillOnly) {
        this.network = network;
        this.ledger = ledger;
        this.solution = solution;
        this.edge = edge;
        this.cells = edge.pipes();
        this.fillOnly = fillOnly;
        this.rate = settleRate(network.cellCapacity);
        this.hysteresisMb = (int) Math.ceil(SETTLE_BAND * network.cellCapacity);
    }

    /** One settle step; returns whether anything moved (the network then stays awake). */
    boolean settle() {
        if (cells.isEmpty()) return false;

        // A gas neither pools at the bottom nor drains downhill; hold it in place for now.
        if (lighterThanAir(presentFluid())) return false;

        // A sealed primed column holds: with every cell FULL and both end reservoirs still
        // reaching their openings, no air can enter the run, so an idle siphon keeps its prime
        // (a real sealed siphon holds its column indefinitely). Without this, the waterline
        // recede below drained the crest on every pause — invisible while a dry crest could
        // self-prime, a permanent break now that it cannot.
        if (sealedPrimedColumn()) return false;

        Double lineA = restingLine(edge.a(), edge.b());
        Double lineB = restingLine(edge.b(), edge.a());
        if (lineA == null && lineB == null) return gravityPool();
        double headA = lineA != null ? lineA : lineB;
        double headB = lineB != null ? lineB : lineA;

        // Two profiles: what the run may RETAIN (a previously-primed siphon leg holds a barometric
        // column up to surface + suction limit — a vacuum gap at the crest supports it) versus what
        // it may DRAW from a reservoir (never above the surface: with air at the broken crest,
        // nothing pushes water UP an open leg). On an unbroken run the two are the same waterline.
        int[] retain = retentionTargets(headA, headB);
        int[] draw = isCrestBroken() ? drawTargets(headA, headB) : retain;
        boolean moved = levelToTargets(retain);
        if (!fillOnly) moved |= fallAndSpread(retain);
        moved |= exchangeWithReservoirs(retain, draw);
        moved |= primeFromPumps(retain);
        return moved;
    }

    /**
     * The top-up a FLOWING run gets alongside the brigade: cells below the waterline draw from
     * the end reservoirs toward the hydrostatic profile, source-side-first (the conducting-prefix
     * walk), so a submerged run fills up WHILE it flows — the plug rules alone only ever top the
     * tail cell (delivery gates on a full tail; every upstream cell nets zero), freezing the run
     * fullest-at-the-sink ("the pipes get increasingly more fluid toward the sink" report).
     * STRICTLY fill-only and no internal redistribution: the brigade owns the moving column, and
     * leveling a flowing edge toward its resting profile would drain a working siphon's crest and
     * break the column. Bare-surface targets (no suction allowance) — never draws above the line.
     */
    boolean topUp() {
        if (cells.isEmpty()) return false;
        if (lighterThanAir(presentFluid())) return false;
        Double lineA = restingLine(edge.a(), edge.b());
        Double lineB = restingLine(edge.b(), edge.a());
        if (lineA == null && lineB == null) return false;
        double headA = lineA != null ? lineA : lineB;
        double headB = lineB != null ? lineB : lineA;
        int[] draw = drawTargets(headA, headB);
        boolean moved = drawFromReservoir(draw, false);
        moved |= drawFromReservoir(draw, true);
        return moved;
    }

    private boolean isCrestBroken() {
        return solution.isCrestBroken(edge.index());
    }

    /**
     * Whether this run is a sealed, fully primed column: every cell FULL and each end reservoir
     * wet up to its opening (so no air can enter), with the crest within the suction limit of
     * both surfaces (higher would cavitate at the top and collapse). Such a column is what a
     * working siphon leaves behind when it goes idle — it must be RETAINED, not receded to the
     * waterline, because a dry crest can no longer self-prime.
     */
    private boolean sealedPrimedColumn() {
        Reservoir a = network.reservoirAt(edge.a());
        Reservoir b = network.reservoirAt(edge.b());
        if (!sealsItsEnd(a, cells.getFirst()) || !sealsItsEnd(b, cells.getLast())) return false;
        double crestY = Double.NEGATIVE_INFINITY;
        for (BlockPos pos : cells) {
            PipeStore.Store cell = network.cellAt(pos);
            if (cell == null || cell.amount() < network.cellCapacity) return false;
            crestY = Math.max(crestY, network.cellCenterY(pos));
        }
        double limit = PipesNPhysicsConfig.SUCTION_LIMIT.get();
        return crestY <= a.surface() + limit && crestY <= b.surface() + limit;
    }

    /** An end is sealed while its finite reservoir's live surface still reaches the opening. */
    private boolean sealsItsEnd(Reservoir reservoir, BlockPos endCell) {
        return reservoir != null && reservoir.isFiniteReservoir() && reservoir.holdsFluid()
                && reservoir.surface() > network.cellBottomY(endCell) + SURFACE_EPS;
    }

    /**
     * The resting surface an endpoint contributes: a finite reservoir's LIVE surface; an open end
     * defers to the far side (its mouth is a spill threshold, not a surface); anything else (a
     * pump or junction) uses the solved head — or, for a fill-only run, the CEILING field
     * (reservoir anchors + pump boosts: how high the line can be packed).
     */
    private Double restingLine(int nodeIndex, int farNodeIndex) {
        Reservoir reservoir = network.reservoirAt(nodeIndex);
        if (reservoir != null && reservoir.isFiniteReservoir()) return reservoir.surface();
        if (reservoir != null && reservoir.isOpenMouth()) return null; // read the far side instead
        Double head = solution.nodeHeads().get(nodeIndex);
        if (!fillOnly) return head;
        Double ceiling = solution.nodeCeilings().get(nodeIndex);
        return ceiling != null ? ceiling : head;
    }

    /** What the run may RETAIN: waterline plus the barometric allowance on a broken siphon's legs. */
    private int[] retentionTargets(double headA, double headB) {
        return hydrostaticTargets(headA, headB, PipesNPhysicsConfig.SUCTION_LIMIT.get());
    }

    /** What the run may DRAW from a reservoir: never above the surface (air sits at a broken crest). */
    private int[] drawTargets(double headA, double headB) {
        return hydrostaticTargets(headA, headB, 0);
    }

    private int[] hydrostaticTargets(double headA, double headB, double suctionAllowance) {
        boolean crestBroken = isCrestBroken();
        int crest = 0;
        if (crestBroken) {
            double crestY = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < cells.size(); i++) {
                double y = network.cellCenterY(cells.get(i));
                if (y > crestY) {
                    crestY = y;
                    crest = i;
                }
            }
        }
        int[] target = new int[cells.size()];
        for (int i = 0; i < cells.size(); i++) {
            double line;
            if (crestBroken) {
                boolean sideA = i <= crest;
                double head = sideA ? headA : headB;
                // A leg whose endpoint holds nothing is open to air from below: no vacuum can
                // support a barometric column there, so it targets the bare surface.
                line = endHoldsFluid(sideA ? edge.a() : edge.b()) ? head + suctionAllowance : head;
            } else {
                line = Math.min(headA, headB);
            }
            // Map the line onto the BORE (where the stored volume lives and renders), so a settled
            // pipe's drawn surface lands exactly on the tank waterline it equalized with.
            target[i] = (int) Math.round(
                    network.boreFill(cells.get(i), line + SURFACE_EPS) * network.cellCapacity);
        }
        return target;
    }

    private boolean endHoldsFluid(int nodeIndex) {
        Reservoir reservoir = network.reservoirAt(nodeIndex);
        return reservoir != null && reservoir.isFiniteReservoir() && reservoir.holdsFluid();
    }

    /** Excess flows to an adjacent deficit, strictly cell to cell, both sweeps. */
    private boolean levelToTargets(int[] target) {
        boolean moved = false;
        for (int i = 0; i < cells.size() - 1; i++) {
            moved |= moveTowardTargets(cells.get(i), cells.get(i + 1), target[i], target[i + 1]);
        }
        for (int i = cells.size() - 2; i >= 0; i--) {
            moved |= moveTowardTargets(cells.get(i + 1), cells.get(i), target[i + 1], target[i]);
        }
        return moved;
    }

    /**
     * Above-target fluid also FALLS and SPREADS: a column hovering over the waterline (its cells
     * all target 0, so no pair sees a deficit) still runs downhill into whatever room is below —
     * the receiver may transiently exceed its own target and pours onward next sweep — and a
     * horizontal cell's water runs toward an emptier same-height neighbour (a crest arch drains
     * off its corners). Without these a riser drained at the bottom kept its fluid hanging
     * mid-air. A fill-only (held) column is pinned by pressure and never falls.
     */
    private boolean fallAndSpread(int[] target) {
        boolean moved = false;
        for (int i = 0; i < cells.size() - 1; i++) {
            moved |= fallDownhill(cells.get(i), cells.get(i + 1), target[i]);
            moved |= fallDownhill(cells.get(i + 1), cells.get(i), target[i + 1]);
            moved |= spreadLevel(cells.get(i), cells.get(i + 1), target[i]);
            moved |= spreadLevel(cells.get(i + 1), cells.get(i), target[i + 1]);
        }
        return moved;
    }

    /**
     * The run communicates with each end reservoir through the CONDUCTING prefix of at-target
     * cells at that end (the shared waterline): fluid enters the first below-target cell past it
     * and leaves from the first above-target one — everything between just passes it through.
     * Strictly hydraulic: no exchange past a dry gap. Excess may also pour out of an open mouth
     * sitting at or below the run. Fill-only runs never give anything back.
     */
    private boolean exchangeWithReservoirs(int[] retain, int[] draw) {
        boolean moved = drawFromReservoir(draw, false);
        moved |= drawFromReservoir(draw, true);
        if (!fillOnly) {
            moved |= pourIntoReservoir(retain, false);
            moved |= pourIntoReservoir(retain, true);
            moved |= pourOutOpenEnd(cells.getFirst(), edge.a());
            moved |= pourOutOpenEnd(cells.getLast(), edge.b());
        }
        return moved;
    }

    /**
     * A below-target cell past the conducting prefix DRAWS straight from the end reservoir — but
     * only while the reservoir's LIVE surface actually reaches the opening at its end cell (the
     * draw lip), so an empty tank can never be asked to supply a phantom column.
     */
    private boolean drawFromReservoir(int[] target, boolean fromB) {
        Reservoir reservoir = network.reservoirAt(fromB ? edge.b() : edge.a());
        if (reservoir == null || !reservoir.isFiniteReservoir() || !reservoir.holdsFluid()) return false;
        BlockPos endCell = fromB ? cells.getLast() : cells.getFirst();
        if (reservoir.surface() <= network.boreBottomY(endCell) + SURFACE_EPS) return false;
        for (int step = 0; step < cells.size(); step++) {
            int i = fromB ? cells.size() - 1 - step : step;
            PipeStore.Store cell = network.cellAt(cells.get(i));
            if (cell == null || target[i] <= 0) return false; // dry-target cell: stops conducting
            int deficit = target[i] - cell.amount();
            // The hysteresis band guards PARTIAL targets (they wobble with the tank's own
            // surface); a full-cell target is clamped and cannot wobble, so it tops off exactly —
            // otherwise a run whose flow stopped mid-fill sits forever a few mB short.
            if (deficit > hysteresisMb || (target[i] >= network.cellCapacity && deficit > 0)) {
                FluidStack want = cell.amount() > 0 ? cell.fluid() : settleFluid(reservoir);
                if (want.isEmpty() || cell.room(want) <= 0) return false;
                int got = reservoir.drain(want, Math.min(deficit, rate));
                if (got <= 0) return false;
                cell.insert(want, got);
                ledger.moved(edge, got);
                return true;
            }
        }
        return false;
    }

    /**
     * The mirror walk: the first ABOVE-target cell past the conducting prefix pours straight into
     * the end reservoir (a broken siphon's crest collapsing, a hump receding through a full
     * riser). Hysteresis: acts only past the band — a target wobbling with the tank's own surface
     * must not ping-pong — except target-0 dregs, which always leave.
     */
    private boolean pourIntoReservoir(int[] target, boolean fromB) {
        Reservoir reservoir = network.reservoirAt(fromB ? edge.b() : edge.a());
        if (reservoir == null || !reservoir.isFiniteReservoir()) return false;
        for (int step = 0; step < cells.size(); step++) {
            int i = fromB ? cells.size() - 1 - step : step;
            PipeStore.Store cell = network.cellAt(cells.get(i));
            if (cell == null) return false;
            int excess = cell.amount() - target[i];
            if (excess > hysteresisMb || (target[i] == 0 && excess > 0)) {
                int move = excess <= Reservoir.DREGS_MB ? excess : Math.min(excess, rate);
                int poured = reservoir.fill(cell.fluid(), move);
                if (poured <= 0) return false;
                cell.extract(poured);
                ledger.moved(edge, poured);
                return true;
            }
            // A wet at-target cell conducts (the shared waterline); a dry-target cell is a gap.
            if (target[i] <= 0 || cell.amount() < target[i] - hysteresisMb) return false;
        }
        return false;
    }

    /**
     * A running pump at an endpoint whose PUSH side faces this run PACKS the line: pull from the
     * pump's other side (a directly-adjacent reservoir, or the pump-adjacent cell of its supply
     * run, which its own settle keeps refilling) into this run's end cell, up to the cell's
     * target. This is how a dead-headed line — a pump against a shut valve or an over-high sink —
     * fills with real fluid even though its steady-state solved flow is 0.
     */
    private boolean primeFromPumps(int[] target) {
        // Non-short-circuit `|`: BOTH ends must attempt priming, not just the first that moves.
        return pumpPrime(cells.getFirst(), target[0], edge.a())
                | pumpPrime(cells.getLast(), target[target.length - 1], edge.b());
    }

    private boolean pumpPrime(BlockPos endCell, int targetMb, int nodeIndex) {
        Node pump = network.graph.node(nodeIndex);
        if (!pump.isPump() || !FlowSolver.isPumpRunning(network.level, pump)) return false;
        BlockPos toward = PipeGeometry.adjacentCell(network.graph, edge, nodeIndex);
        if (toward == null || !toward.equals(pump.pushCell())) return false;
        PipeStore.Store cell = network.cellAt(endCell);
        if (cell == null) return false;
        int want = Math.min(targetMb - cell.amount(), rate);
        if (want <= 0) return false;

        for (Edge supply : network.graph.edgesOf(nodeIndex)) {
            if (supply.index() == edge.index()) continue;
            int got = 0;
            FluidStack fluid;
            if (supply.pipes().isEmpty()) {
                Reservoir source = network.reservoirAt(supply.other(nodeIndex));
                if (source == null) continue;
                fluid = cell.amount() > 0 ? cell.fluid() : source.contents();
                if (fluid.isEmpty() || cell.room(fluid) <= 0) continue;
                got = source.drain(fluid, want);
            } else {
                PipeStore.Store feed = network.cellAt(
                        PipeGeometry.adjacentCell(network.graph, supply, nodeIndex));
                if (feed == null || feed.amount() <= 0) continue;
                fluid = feed.fluid();
                if (cell.room(fluid) <= 0) continue;
                if (feed.amount() >= network.cellCapacity) {
                    // The supply column has arrived: it CONDUCTS — draw from the reservoir
                    // behind it, so a held suction line never dips while the pump packs its
                    // outlet. Only when nothing is behind (tank empty, below its lip) does the
                    // pump drain its own suction line forward.
                    Reservoir behind = network.reservoirAt(supply.other(nodeIndex));
                    if (behind != null && behind.isFiniteReservoir() && behind.holdsFluid()
                            && FluidStack.isSameFluidSameComponents(behind.contents(), fluid)) {
                        got = behind.drain(fluid, want);
                    }
                }
                if (got <= 0) got = feed.extract(Math.min(want, feed.amount())).getAmount();
            }
            if (got > 0) {
                cell.insert(fluid, got);
                ledger.moved(edge, got);
                return true;
            }
        }
        return false;
    }

    /**
     * No solve data at all (every reservoir gone or empty — a run whose tank was broken away):
     * plain gravity still acts. Contents trickle downhill cell-to-cell, spread level, pour out of
     * an open mouth at/below, and equalize with an adjacent reservoir by live surfaces — so fluid
     * pools in the dips instead of hanging frozen in a riser.
     */
    private boolean gravityPool() {
        boolean moved = false;
        for (int i = 0; i < cells.size() - 1; i++) {
            moved |= trickleDownhill(cells.get(i), cells.get(i + 1));
            moved |= trickleDownhill(cells.get(i + 1), cells.get(i));
            moved |= spreadLevel(cells.get(i), cells.get(i + 1), 0);
            moved |= spreadLevel(cells.get(i + 1), cells.get(i), 0);
        }
        moved |= pourOutOpenEnd(cells.getFirst(), edge.a());
        moved |= pourOutOpenEnd(cells.getLast(), edge.b());
        moved |= equalizeWithReservoir(cells.getFirst(), edge.a());
        moved |= equalizeWithReservoir(cells.getLast(), edge.b());
        return moved;
    }

    // ---------------------------------------------------------------- single moves

    /** Move from an above-target cell into an adjacent below-target one, rate-limited. */
    private boolean moveTowardTargets(BlockPos fromPos, BlockPos toPos, int fromTarget, int toTarget) {
        PipeStore.Store from = network.cellAt(fromPos);
        PipeStore.Store to = network.cellAt(toPos);
        if (from == null || to == null) return false;
        int excess = from.amount() - fromTarget;
        int deficit = toTarget - to.amount();
        if (excess <= 0 || deficit <= 0) return false;
        int move = Math.min(Math.min(excess, deficit), rate);
        if (excess <= Reservoir.DREGS_MB) move = Math.min(excess, deficit); // dregs leave at once
        return moveBetween(from, to, move);
    }

    /** Let a cell's above-target fluid fall into a strictly LOWER neighbour with room. */
    private boolean fallDownhill(BlockPos fromPos, BlockPos toPos, int fromTarget) {
        if (network.cellBottomY(fromPos) <= network.cellBottomY(toPos) + SURFACE_EPS) return false;
        PipeStore.Store from = network.cellAt(fromPos);
        PipeStore.Store to = network.cellAt(toPos);
        if (from == null || to == null) return false;
        int excess = from.amount() - fromTarget;
        if (excess <= 0) return false;
        return moveBetween(from, to, Math.min(excess, rate));
    }

    /** Level out above-target fluid between SAME-HEIGHT neighbours (water runs flat). */
    private boolean spreadLevel(BlockPos fromPos, BlockPos toPos, int fromTarget) {
        if (Math.abs(network.cellBottomY(fromPos) - network.cellBottomY(toPos)) > SURFACE_EPS) {
            return false;
        }
        PipeStore.Store from = network.cellAt(fromPos);
        PipeStore.Store to = network.cellAt(toPos);
        if (from == null || to == null) return false;
        int excess = from.amount() - fromTarget;
        int diff = from.amount() - to.amount();
        if (excess <= 0 || diff <= Reservoir.DREGS_MB) return false;
        return moveBetween(from, to, Math.min(Math.min(excess, diff / 2), rate));
    }

    /** A headless run's plain-gravity trickle (no targets: anything runs downhill). */
    private boolean trickleDownhill(BlockPos fromPos, BlockPos toPos) {
        if (network.cellBottomY(fromPos) <= network.cellBottomY(toPos) + SURFACE_EPS) return false;
        PipeStore.Store from = network.cellAt(fromPos);
        PipeStore.Store to = network.cellAt(toPos);
        if (from == null || to == null) return false;
        return moveBetween(from, to, Math.min(from.amount(), rate));
    }

    /** Pour an end cell's fluid out of an open mouth at or below it (the spill of a dying run). */
    private boolean pourOutOpenEnd(BlockPos endCell, int nodeIndex) {
        Reservoir mouth = network.reservoirAt(nodeIndex);
        if (mouth == null || !mouth.isOpenMouth() || mouth.isInfiniteSource()) return false;
        double mouthY = network.cellCenterY(network.graph.node(nodeIndex).pos());
        if (mouthY > network.cellCenterY(endCell) + SURFACE_EPS) {
            return false; // the mouth sits above: gravity keeps the fluid in the pipe
        }
        PipeStore.Store cell = network.cellAt(endCell);
        if (cell == null || cell.amount() <= 0) return false;
        int move = cell.amount() <= Reservoir.DREGS_MB ? cell.amount() : Math.min(cell.amount(), rate);
        int poured = mouth.fill(cell.fluid(), move);
        if (poured <= 0) return false;
        cell.extract(poured);
        ledger.moved(edge, poured);
        return true;
    }

    /**
     * Even with no solve data, the fluid IN the pipes still communicates with an adjacent
     * reservoir: pour the end cell into it while the cell's own surface sits above the
     * reservoir's — a wet run beside an emptied tank drains back in instead of hanging forever.
     */
    private boolean equalizeWithReservoir(BlockPos endCell, int nodeIndex) {
        Reservoir reservoir = network.reservoirAt(nodeIndex);
        if (reservoir == null || !reservoir.isFiniteReservoir()) return false;
        PipeStore.Store cell = network.cellAt(endCell);
        if (cell == null || cell.amount() <= 0) return false;
        double cellSurface = network.boreBottomY(endCell)
                + cell.amount() / (double) network.cellCapacity * FlowNetwork.BORE_HEIGHT;
        if (cellSurface <= reservoir.surface() + SURFACE_EPS) return false;
        int move = cell.amount() <= Reservoir.DREGS_MB ? cell.amount() : Math.min(cell.amount(), rate);
        int poured = reservoir.fill(cell.fluid(), move);
        if (poured <= 0) return false;
        cell.extract(poured);
        ledger.moved(edge, poured);
        return true;
    }

    private boolean moveBetween(PipeStore.Store from, PipeStore.Store to, int amount) {
        return from.moveInto(to, amount) > 0;
    }

    /** The fluid this run settles with: its own content, the solved rest fluid, or the reservoir's. */
    private FluidStack settleFluid(Reservoir reservoir) {
        FluidStack present = presentFluid();
        if (!present.isEmpty()) return present;
        FluidStack rest = solution.restFluids().getOrDefault(edge.index(), FluidStack.EMPTY);
        if (!rest.isEmpty()) return rest;
        return reservoir.contents();
    }

    private FluidStack presentFluid() {
        for (BlockPos pos : cells) {
            PipeStore.Store cell = network.cellAt(pos);
            if (cell != null && cell.amount() > 0) return cell.fluid();
        }
        return FluidStack.EMPTY;
    }
}
