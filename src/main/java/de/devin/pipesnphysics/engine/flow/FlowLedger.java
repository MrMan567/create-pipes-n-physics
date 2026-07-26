package de.devin.pipesnphysics.engine.flow;

import de.devin.pipesnphysics.engine.graph.Edge;

/**
 * What one tick of flow execution actually did. The per-edge amounts are written straight into
 * the {@code Solution.actualFlow} array (the same instance the graph cache serves to goggle and
 * overlay probes), so "what the player is shown" and "what really moved" are one value.
 */
public final class FlowLedger {
    private final int[] edgeMovedMb;
    private boolean movedAny;
    private boolean settling;

    public FlowLedger(int[] edgeMovedMb) {
        this.edgeMovedMb = edgeMovedMb;
    }

    /**
     * Record a boundary movement on an edge. The array arrives zeroed (a fresh Solution per
     * solve); every boundary of a run moves the same plug one step, so per-boundary amounts are
     * parallel samples of ONE throughput — hence the max below, not a sum.
     */
    void moved(Edge edge, int amount) {
        if (amount <= 0) return;
        movedAny = true;
        if (amount > edgeMovedMb[edge.index()]) edgeMovedMb[edge.index()] = amount;
    }

    /** Idle contents are still moving toward rest — the network must stay awake. */
    void markSettling() {
        settling = true;
    }

    /**
     * Per edge, the strongest single boundary movement this tick in mB — a max, not a sum
     * (see {@link #moved}).
     */
    public int[] edgeMovedMb() { return edgeMovedMb; }

    /** Whether any fluid moved at all this tick, brigade or settle. */
    public boolean movedAny() { return movedAny; }

    /** Whether idle contents are still moving toward rest — the network must stay awake. */
    public boolean settling() { return settling; }
}
