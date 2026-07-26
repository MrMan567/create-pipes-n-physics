package de.devin.pipesnphysics.engine.graph;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * A single node in a {@link Graph}.
 *
 * Nodes are the boundary entities of the contracted graph. They are either:
 *   HANDLER  — a block with an IFluidHandler capability (tank, basin, drain, etc.),
 *   PUMP     — a Create pump (carries a facing for push/pull side),
 *   JUNCTION — a pipe cell whose connection count is not exactly 2 (split, dead-end),
 *   OPEN_END — the world-space block an open pipe end faces (air, fluid, cauldron);
 *              pos is the space block, openFace points from it back to its pipe.
 *   CLOSED_GATE — a fully-shut valve cell. It splits its run into two segments that meet
 *              here but do NOT conduct across it: the solver gives each incident edge its
 *              OWN dead-end node, so a pump on one side holds its head up to the gate while
 *              the other side settles. Reopening removes the gate and the run rejoins.
 *
 * Pipes with exactly two connections are pass-through and become part of an {@link Edge}
 * rather than a Node — UNLESS they are a closed gate, which is forced to a node so the run
 * splits there.
 *
 * {@code pumpFacing}, {@code openFace}, and {@code accessFace} are only meaningful for their
 * respective kinds — PUMP, OPEN_END, and (side-specific) HANDLER — and are null on every other kind.
 *
 * {@code accessFace} is the face a HANDLER is reached through when it is SIDE-SPECIFIC (exposes no
 * side-agnostic {@code null} capability): the network resolves and transfers the handler through that
 * exact face, so a block with a different tank per side serves each side its own fluid. It is null for
 * an ordinary side-agnostic handler (resolved through {@code null}) and for every non-handler node.
 *
 * {@code gateFlow} marks a JUNCTION that is really an OPEN one-way valve (a check valve): the single
 * world direction fluid may flow through it. Like a shut valve it is forced to a node so the run
 * splits there; unlike one it CONDUCTS — the solver walls the reverse direction on its incident
 * branches (the same sign mechanism as a pump's flank check valves) and the settle's slot exchange
 * honors it. Null everywhere else, including ordinary junctions.
 */
public record Node(int index, BlockPos pos, Kind kind, double worldY,
                   Direction pumpFacing, Direction openFace, Direction accessFace,
                   Direction gateFlow) {
    public enum Kind { HANDLER, PUMP, JUNCTION, OPEN_END, CLOSED_GATE }

    /** The common shape: every node except a one-way valve junction. */
    public Node(int index, BlockPos pos, Kind kind, double worldY,
                Direction pumpFacing, Direction openFace, Direction accessFace) {
        this(index, pos, kind, worldY, pumpFacing, openFace, accessFace, null);
    }

    public boolean isHandler() { return kind == Kind.HANDLER; }
    public boolean isPump() { return kind == Kind.PUMP; }
    public boolean isJunction() { return kind == Kind.JUNCTION; }
    public boolean isOpenEnd() { return kind == Kind.OPEN_END; }
    public boolean isClosedGate() { return kind == Kind.CLOSED_GATE; }

    /** Whether this junction is an open one-way valve — a check valve the flow must honor. */
    public boolean isOneWayGate() { return gateFlow != null; }

    /** The cell a PUMP pushes into (its FACING side), or null while the facing is unresolved. */
    public BlockPos pushCell() {
        return pumpFacing == null ? null : pos.relative(pumpFacing);
    }

    /** The cell a PUMP pulls from (opposite its FACING), or null while the facing is unresolved. */
    public BlockPos pullCell() {
        return pumpFacing == null ? null : pos.relative(pumpFacing.getOpposite());
    }
}
