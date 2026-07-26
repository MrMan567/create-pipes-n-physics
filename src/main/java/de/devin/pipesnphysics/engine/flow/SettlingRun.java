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
 * first below-target cell past it draws in, the first above-target one pours out; the DRAW
 * side's hysteresis band keeps the tank-surface↔target feedback from ping-ponging (pours are
 * self-stabilizing and act on any excess), and dregs leave in one go.
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
     * Whether this content is a lighter-than-air gas, which settles in the MIRRORED elevation
     * frame — buoyancy is gravity upside down, so the same target/walk machinery runs with world
     * Y negated (the frame wrappers below) and the gas pools UP and pours into the vessel ABOVE.
     * Never mix a gas with the solve's display heads: a gas head is INVERTED (fill − baseY, §4),
     * not an elevation, which is why the mirrored frame reads live surfaces only.
     */
    static boolean lighterThanAir(FluidStack fluid) {
        return !fluid.isEmpty() && fluid.getFluid().getFluidType().isLighterThanAir();
    }
    /**
     * DRAW-side hysteresis (fraction of a cell): drawing from a tank lowers its surface, which
     * lowers the targets, which would pour the same fluid straight back — so draws act only on a
     * deficit beyond this band. Pours need no band (raising the tank raises the target, so they
     * self-stabilize), and banding them left a visible film standing in every near-empty cell.
     */
    private static final double SETTLE_BAND = 0.1;
    /**
     * Small elevation epsilon (blocks) for cell-HEIGHT and seal/pour comparisons only. It is NOT
     * added to a fill target's waterline: divided by the narrow bore it becomes a 13% distortion,
     * settling a pipe visibly off the tank it equalized with. The mB-based {@link #SETTLE_BAND} is
     * the anti-flap deadband.
     */
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
    /** Settling a lighter-than-air gas: every elevation below reads through the mirrored frame. */
    private boolean mirrored;

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

        // Crossing the streams with NO flow: a tank joined to the run holds a fluid the mouth
        // cell's resting fluid is incompatible with — the two meet at the boundary exactly as
        // Create pulls a tank's fluid into a pipe already carrying another. The brigade never
        // catches this on two idle tanks (each fluid's pass bails with a single participant, the
        // opposite endpoint walling it), so a water pipe touching a lava tank would just sit.
        // Checked BEFORE the gas/sealed bails, which would otherwise skip a full primed run.
        if (reactToBoundaryCollision()) return false;

        // A lighter-than-air gas settles in the MIRRORED frame: the wrappers below negate world
        // Y, so the SAME target/walk machinery pools it upward and pours it into the vessel
        // ABOVE. A held (fill-only) gas column stays frozen — its packing target is the display
        // CEILING field, which mixes heads with elevations the mirror cannot read.
        mirrored = lighterThanAir(settleMedium());
        if (mirrored && fillOnly) return false;

        // A sealed primed column holds: with every cell FULL and both end reservoirs still
        // reaching their openings, no air can enter the run, so an idle siphon keeps its prime
        // (a real sealed siphon holds its column indefinitely). Without this, the waterline
        // recede below drained the crest on every pause — invisible while a dry crest could
        // self-prime, a permanent break now that it cannot.
        if (sealedPrimedColumn()) return false;

        Double lineA = restingLine(edge.a(), edge.b());
        Double lineB = restingLine(edge.b(), edge.a());
        if (lineA == null && lineB == null) return gravityPool();
        double headA = emptyFloorCap(edge.a(), lineA != null ? lineA : lineB);
        double headB = emptyFloorCap(edge.b(), lineB != null ? lineB : lineA);

        // Two profiles: what the run may RETAIN (a previously-primed siphon leg holds a barometric
        // column up to surface + suction limit — a vacuum gap at the crest supports it) versus what
        // it may DRAW from a reservoir (never above the surface: with air at the broken crest,
        // nothing pushes water UP an open leg). On an unbroken run the two are the same waterline.
        int[] retain = retentionTargets(headA, headB);
        int[] draw = isCrestBroken() ? drawTargets(headA, headB) : retain;
        boolean moved = levelToTargets(retain);
        if (!fillOnly) moved |= fallAndSpread(retain);
        moved |= exchangeWithReservoirs(retain, draw, headA, headB);
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
        // A flowing GAS run tops up in the mirrored frame: its cells pack downward from the bore
        // top toward the interface profile while the brigade flows — without this, flowing gas
        // rode at plug-flow depth and its hanging fill visibly missed the tank's interface until
        // the flow stopped ("the gas heights inside the pipe and the tank don't match").
        mirrored = lighterThanAir(settleMedium());
        Double lineA = restingLine(edge.a(), edge.b());
        Double lineB = restingLine(edge.b(), edge.a());
        if (lineA == null && lineB == null) return false;
        double headA = emptyFloorCap(edge.a(), lineA != null ? lineA : lineB);
        double headB = emptyFloorCap(edge.b(), lineB != null ? lineB : lineA);
        int[] draw = drawTargets(headA, headB);
        boolean moved = drawFromReservoir(draw, false);
        moved |= drawFromReservoir(draw, true);
        return moved;
    }

    // ------------------------------------------------------------ the elevation frame
    // A gas is a liquid under inverted gravity, so its rest state is the SAME math run in a
    // MIRRORED frame: every elevation negated, min/max meanings preserved. All settle logic
    // reads elevations exclusively through these wrappers; with {@code mirrored} false they
    // are the identity, so the liquid paths are bit-for-bit unchanged.

    /** A reservoir's resting line in-frame: the rendered liquid surface, or the gas interface. */
    private double surfaceOf(Reservoir reservoir) {
        return mirrored ? -reservoir.gasSurface() : reservoir.surface();
    }

    /** The low edge of a cell's fluid window in-frame (liquid: window bottom; gas: minus its top). */
    private double windowLow(BlockPos pos) {
        return mirrored ? -(network.windowBottomY(pos) + network.windowHeight(pos))
                : network.windowBottomY(pos);
    }

    /** Fraction of a cell's window past the in-frame line — the one fill↔height conversion. */
    private double windowFillFrac(BlockPos pos, double line) {
        return Math.clamp((line - windowLow(pos)) / network.windowHeight(pos), 0, 1);
    }

    /** A cell's low block edge in-frame (fall/spread comparisons: gas "falls" upward). */
    private double cellLow(BlockPos pos) {
        return mirrored ? -(network.cellBottomY(pos) + 1) : network.cellBottomY(pos);
    }

    /** A cell's centre in-frame. */
    private double cellMid(BlockPos pos) {
        return mirrored ? -network.cellCenterY(pos) : network.cellCenterY(pos);
    }

    /** The medium this run settles as: its content, the solved rest fluid, or an end reservoir's. */
    private FluidStack settleMedium() {
        FluidStack present = presentFluid();
        if (!present.isEmpty()) return present;
        FluidStack rest = solution.restFluids().getOrDefault(edge.index(), FluidStack.EMPTY);
        if (!rest.isEmpty()) return rest;
        Reservoir a = network.reservoirAt(edge.a());
        if (a != null && a.isFiniteReservoir() && a.holdsFluid()) return a.contents();
        Reservoir b = network.reservoirAt(edge.b());
        if (b != null && b.isFiniteReservoir() && b.holdsFluid()) return b.contents();
        return FluidStack.EMPTY;
    }

    private boolean isCrestBroken() {
        // The solve's crest data is a LIQUID quantity (suction/cavitation over a high point);
        // a gas run always settles as unbroken — its "crest" would be a dip, a deferred mirror.
        return !mirrored && solution.isCrestBroken(edge.index());
    }

    /**
     * Register Create's crossing-the-streams for an idle run: each end reservoir presses its own
     * fluid down its side of the run (a tank joined to a pipe pushes its fluid at the mouth — no
     * flow needed), and where that column meets an INCOMPATIBLE fluid the two react, exactly as
     * Create pulls a tank's fluid into a pipe already carrying another. The meeting point is the
     * MOUTH cell for a uniform run into a rejecting tank, or an interface DEEP in the run where two
     * tanks' columns touch — water settled in from the water end, lava from the lava end, meeting
     * mid-run (each mouth cell then matches its OWN tank, so the old end-cell-only check saw no
     * collision and the fluids just sat there touching). Two foreign pipe cells with no reservoir
     * driving them still just block, as two idle fluids do.
     */
    private boolean reactToBoundaryCollision() {
        // Non-short-circuit `|`: a run walled by a rejecting tank at BOTH ends reacts at both.
        return pressColumn(edge.a(), false) | pressColumn(edge.b(), true);
    }

    /**
     * Walk in from one end reservoir through the cells its own fluid fills — the column it presses
     * into the run — and react at the first cell holding a fluid it REJECTS (can neither accept nor
     * supply). Stops at a dry cell (a gap it would simply fill: no contact yet) or a compatible
     * foreign fluid (a multi-fluid tank carrying it — not a collision). No fill-level gate, exactly
     * as Create's own collision checks none.
     */
    private boolean pressColumn(int nodeIndex, boolean fromB) {
        Reservoir reservoir = network.reservoirAt(nodeIndex);
        if (reservoir == null || !reservoir.isFiniteReservoir() || !reservoir.holdsFluid()) {
            return false;
        }
        FluidStack pressed = reservoir.contents();
        for (int step = 0; step < cells.size(); step++) {
            int i = fromB ? cells.size() - 1 - step : step;
            BlockPos pos = cells.get(i);
            PipeStore.Store cell = network.cellAt(pos);
            if (cell == null || cell.amount() <= 0) return false;              // dry gap: no contact
            if (FluidStack.isSameFluidSameComponents(cell.fluid(), pressed)) continue; // own column
            if (!reservoir.rejects(cell.fluid())) return false;               // compatible: no react
            return network.collides(pos, cell, pressed);                      // crossing the streams
        }
        return false;
    }

    /**
     * Whether this run is a sealed, fully primed column: every cell FULL and each end reservoir
     * wet up to its opening (so no air can enter), with the crest within the suction limit of
     * both surfaces (higher would cavitate at the top and collapse). Such a column is what a
     * working siphon leaves behind when it goes idle — it must be RETAINED, not receded to the
     * waterline, because a dry crest can no longer self-prime.
     */
    private boolean sealedPrimedColumn() {
        if (mirrored) return false; // barometric gas columns: a deferred mirror
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

    /**
     * An end is sealed while its finite reservoir's live surface still wets the end cell's BORE
     * opening — the block bottom is not enough: a waterline in the gap below the bore leaves the
     * opening in the tank's head space, air enters, and the column must recede (a full run
     * between two low tanks held its 250 mB forever — "the pipes hold 250 instead of equalizing").
     */
    private boolean sealsItsEnd(Reservoir reservoir, BlockPos endCell) {
        return reservoir != null && reservoir.isFiniteReservoir() && reservoir.holdsFluid()
                && reservoir.surface() > network.windowBottomY(endCell) + SURFACE_EPS;
    }

    /**
     * The resting surface an endpoint contributes: a finite reservoir's LIVE surface; an open end
     * defers to the far side (its mouth is a spill threshold, not a surface); anything else (a
     * pump or junction) uses the solved head — or, for a fill-only run, the CEILING field
     * (reservoir anchors + pump boosts: how high the line can be packed).
     *
     * An EMPTY reservoir has no surface and defers too — its floor only CAPS the far side's line
     * ({@link #emptyFloorCap}). Anchoring the line at the floor froze runs solid: an empty tank
     * up a riser set targets ABOVE the run's whole content, so nothing was ever "excess" and the
     * pour/fall/spread machinery never engaged — a dreg beside an open mouth just sat there.
     * With no live surface or head at either end the run is headless and gravity-pools.
     */
    private Double restingLine(int nodeIndex, int farNodeIndex) {
        Reservoir reservoir = network.reservoirAt(nodeIndex);
        if (reservoir != null && reservoir.isFiniteReservoir()) {
            if (!reservoir.holdsFluid()) return null;
            // In the gas frame a vessel holding a LIQUID has no gas interface to contribute —
            // its line would be phantom; the exchange walks still fill it if it accepts the gas.
            if (mirrored && !lighterThanAir(reservoir.contents())) return null;
            return surfaceOf(reservoir);
        }
        if (reservoir != null && reservoir.isOpenMouth()) return null; // read the far side instead
        // Display heads/ceilings are LIQUID elevations; the gas frame cannot read them (a gas
        // head is fill − baseY). A pump/junction end contributes no gas line.
        if (mirrored) return null;
        Double head = solution.nodeHeads().get(nodeIndex);
        if (!fillOnly) return head;
        Double ceiling = solution.nodeCeilings().get(nodeIndex);
        return ceiling != null ? ceiling : head;
    }

    /** An empty reservoir's floor still caps its side of the line: fluid drains down toward it
     *  (in the gas frame its CEILING caps upward: gas rises toward the empty vessel above). */
    private double emptyFloorCap(int nodeIndex, double line) {
        Reservoir reservoir = network.reservoirAt(nodeIndex);
        if (reservoir != null && reservoir.isFiniteReservoir() && !reservoir.holdsFluid()) {
            return Math.min(line, surfaceOf(reservoir));
        }
        return line;
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
        // A barometric leg is supported by the VACUUM in the broken crest's gap, which exists
        // only while the tube is sealed against air at BOTH ends (air entering either end rises
        // into the gap and both legs fall to their bare surfaces). A wet-but-unsealed end — a
        // tank whose surface sits below its end cell's bore — is an air path like an empty one:
        // per-leg "endpoint holds fluid" kept a run's sink leg hanging full in mid-air forever
        // beside a drained source.
        boolean sealed = crestBroken && suctionAllowance > 0
                && sealsItsEnd(network.reservoirAt(edge.a()), cells.getFirst())
                && sealsItsEnd(network.reservoirAt(edge.b()), cells.getLast());
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
                double head = i <= crest ? headA : headB;
                line = sealed ? head + suctionAllowance : head;
            } else {
                line = Math.min(headA, headB);
            }
            // Map the line onto the cell's drawn fluid window (bore for a horizontal cell, the
            // full block for a vertical riser), so a settled pipe's surface lands exactly on the
            // tank waterline it equalized with — whichever way the cell renders. The line is NOT
            // nudged: a 0.05-block bump is 13% of the 6/16 bore and would settle the pipe visibly
            // ABOVE the tank (and dead-zone a run whose waterline sits just above the bore floor);
            // the mB-based SETTLE_BAND hysteresis is the anti-flap deadband, not a world-Y nudge.
            target[i] = (int) Math.round(
                    windowFillFrac(cells.get(i), line) * network.cellCapacity);
        }
        return target;
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
     *
     * POURS gate on each end's OWN line, never the flattened profile: pouring into a reservoir
     * is a gravity act, so only fluid standing ABOVE that reservoir's surface may enter it. The
     * min-flattened retain targets read a film beside the HIGHER tank as excess and poured it
     * back UP into it — with the flow pass pulling the same film out through the lip's dregs
     * allowance, fluid ping-ponged tank↔head-cell at 4 mB forever while the true sink starved
     * (the "flows shortly, stops" limit cycle at the lip equilibrium).
     */
    private boolean exchangeWithReservoirs(int[] retain, int[] draw, double headA, double headB) {
        boolean moved = drawFromReservoir(draw, false);
        moved |= drawFromReservoir(draw, true);
        if (!fillOnly) {
            moved |= pourIntoReservoir(pourTargets(retain, pourLine(edge.a(), headA)), false);
            moved |= pourIntoReservoir(pourTargets(retain, pourLine(edge.b(), headB)), true);
            moved |= pourOutOpenEnd(false);
            moved |= pourOutOpenEnd(true);
        }
        return moved;
    }

    /**
     * The line a pour into this end gates on: the end's OWN surface, an EMPTY reservoir
     * included (its floor — in the gas frame its ceiling). The flattened fallback substituted
     * the FAR side's line at an empty end, which read fluid resting beside the empty vessel as
     * excess it could jump INTO it across the opening — for a gas, pouring DOWN into an empty
     * tank below the run, the exact inversion of buoyancy.
     */
    private double pourLine(int nodeIndex, double flattenedFallback) {
        Reservoir reservoir = network.reservoirAt(nodeIndex);
        if (reservoir != null && reservoir.isFiniteReservoir() && !reservoir.holdsFluid()) {
            return surfaceOf(reservoir);
        }
        return flattenedFallback;
    }

    /**
     * The pour gate for one end: on an unbroken run, the profile of that end's OWN line. A
     * crest-broken run keeps the retain targets — they are already per-leg, and a collapsing
     * barometric leg must pour against its retention allowance, not the bare surface.
     */
    private int[] pourTargets(int[] retain, double endHead) {
        if (isCrestBroken()) return retain;
        int[] target = new int[cells.size()];
        for (int i = 0; i < cells.size(); i++) {
            target[i] = (int) Math.round(
                    windowFillFrac(cells.get(i), endHead) * network.cellCapacity);
        }
        return target;
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
        if (surfaceOf(reservoir) <= windowLow(endCell)) return false;
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
     * riser). Pours act on ANY excess — no hysteresis: pouring in RAISES the tank's surface and
     * with it the target, so this direction is self-stabilizing, and the DRAW side's band alone
     * breaks the draw↔pour loop. Sharing the band here left a visible ~10%-of-a-cell film
     * standing above the waterline in every cell beside a near-empty tank ("the flagged pipe
     * still holds fluid and does not flow into the tank").
     */
    private boolean pourIntoReservoir(int[] target, boolean fromB) {
        Reservoir reservoir = network.reservoirAt(fromB ? edge.b() : edge.a());
        if (reservoir == null || !reservoir.isFiniteReservoir()) return false;
        for (int step = 0; step < cells.size(); step++) {
            int i = fromB ? cells.size() - 1 - step : step;
            PipeStore.Store cell = network.cellAt(cells.get(i));
            if (cell == null) return false;
            int excess = cell.amount() - target[i];
            if (excess > 0) {
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
        // A dead-headed GAS line stays with the brigade: pump packing reads ceiling-field
        // quantities the gas frame cannot, and the flow passes already move powered gas.
        if (mirrored) return false;
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
                // A running pump packing its supply fluid into an outlet cell holding a DIFFERENT one
                // is crossing the streams (a dead-headed pump has no brigade run to catch it).
                if (network.collides(endCell, cell, fluid)) return true;
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
        moved |= pourOutOpenEnd(false);
        moved |= pourOutOpenEnd(true);
        moved |= equalizeWithReservoir(false);
        moved |= equalizeWithReservoir(true);
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

    /** Let a cell's above-target fluid fall into a strictly LOWER in-frame neighbour with room. */
    private boolean fallDownhill(BlockPos fromPos, BlockPos toPos, int fromTarget) {
        if (cellLow(fromPos) <= cellLow(toPos) + SURFACE_EPS) return false;
        PipeStore.Store from = network.cellAt(fromPos);
        PipeStore.Store to = network.cellAt(toPos);
        if (from == null || to == null) return false;
        int excess = from.amount() - fromTarget;
        if (excess <= 0) return false;
        return moveBetween(from, to, Math.min(excess, rate));
    }

    /** Level out above-target fluid between SAME-HEIGHT neighbours (water runs flat). */
    private boolean spreadLevel(BlockPos fromPos, BlockPos toPos, int fromTarget) {
        if (Math.abs(cellLow(fromPos) - cellLow(toPos)) > SURFACE_EPS) {
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

    /** A headless run's plain-gravity trickle (no targets: anything runs downhill in-frame). */
    private boolean trickleDownhill(BlockPos fromPos, BlockPos toPos) {
        if (cellLow(fromPos) <= cellLow(toPos) + SURFACE_EPS) return false;
        PipeStore.Store from = network.cellAt(fromPos);
        PipeStore.Store to = network.cellAt(toPos);
        if (from == null || to == null) return false;
        return moveBetween(from, to, Math.min(from.amount(), rate));
    }

    /**
     * Pour the run's fluid out of an open mouth at or below it (the spill of a dying run). The
     * walk crosses EMPTY cells in from the mouth to the first one holding fluid — the last dregs
     * otherwise strand one cell short forever, because {@link #spreadLevel}'s anti-slosh gate
     * refuses to push the final {@code DREGS_MB} across a level pair and nothing else moves them.
     */
    private boolean pourOutOpenEnd(boolean fromB) {
        int nodeIndex = fromB ? edge.b() : edge.a();
        Reservoir mouth = network.reservoirAt(nodeIndex);
        if (mouth == null || !mouth.isOpenMouth() || mouth.isInfiniteSource()) return false;
        int i = firstWetCellFrom(fromB);
        if (i < 0) return false;
        BlockPos pos = cells.get(i);
        double mouthY = cellMid(network.graph.node(nodeIndex).pos());
        if (mouthY > cellMid(pos) + SURFACE_EPS) {
            return false; // the mouth sits above in-frame: gravity keeps the fluid in the pipe
        }
        PipeStore.Store cell = network.cellAt(pos);
        int move = cell.amount() <= Reservoir.DREGS_MB ? cell.amount() : Math.min(cell.amount(), rate);
        int poured = mouth.fill(cell.fluid(), move);
        if (poured <= 0) return false;
        cell.extract(poured);
        ledger.moved(edge, poured);
        return true;
    }

    /**
     * Even with no solve data, the fluid IN the pipes still communicates with an adjacent
     * reservoir: pour the first wet cell in from that end into it while the cell's own surface
     * sits above the reservoir's — a wet run beside an emptied tank drains back in instead of
     * hanging forever. Walks like {@link #pourOutOpenEnd} so dregs cannot strand behind an empty
     * end cell.
     */
    private boolean equalizeWithReservoir(boolean fromB) {
        Reservoir reservoir = network.reservoirAt(fromB ? edge.b() : edge.a());
        if (reservoir == null || !reservoir.isFiniteReservoir()) return false;
        int i = firstWetCellFrom(fromB);
        if (i < 0) return false;
        BlockPos pos = cells.get(i);
        PipeStore.Store cell = network.cellAt(pos);
        double cellSurface = windowLow(pos)
                + cell.amount() / (double) network.cellCapacity * network.windowHeight(pos);
        if (cellSurface <= surfaceOf(reservoir) + SURFACE_EPS) return false;
        int move = cell.amount() <= Reservoir.DREGS_MB ? cell.amount() : Math.min(cell.amount(), rate);
        int poured = reservoir.fill(cell.fluid(), move);
        if (poured <= 0) return false;
        cell.extract(poured);
        ledger.moved(edge, poured);
        return true;
    }

    /**
     * Index of the first cell holding fluid, walking in from the given end across empty cells —
     * or -1 with nothing to find. The walk never climbs: an empty cell whose floor sits above the
     * wet cell it leads to is a dry rise the fluid cannot cross (an air gap, not a channel).
     */
    private int firstWetCellFrom(boolean fromB) {
        double pathFloor = Double.NEGATIVE_INFINITY;
        for (int step = 0; step < cells.size(); step++) {
            int i = fromB ? cells.size() - 1 - step : step;
            PipeStore.Store cell = network.cellAt(cells.get(i));
            if (cell == null) return -1;
            if (cell.amount() > 0) {
                return pathFloor > windowLow(cells.get(i)) + SURFACE_EPS ? -1 : i;
            }
            pathFloor = Math.max(pathFloor, windowLow(cells.get(i)));
        }
        return -1;
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
