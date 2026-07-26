package de.devin.pipesnphysics.engine;

import de.devin.pipesnphysics.engine.graph.Edge;
import de.devin.pipesnphysics.engine.graph.Graph;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A short per-edge history of what ACTUALLY moved — the last {@link #SAMPLES} solves' actual mB
 * and solved direction — served to {@code /pipegraph} so an oscillation reads straight off ONE
 * dump (a limit cycle shows as an alternating strip like {@code ·4 →4 ·4 →4}) instead of
 * needing dumps taken ticks apart and a diff by eye.
 *
 * Keyed by the edge's ENDPOINT POSITIONS (plus length, to split most parallel runs), never the
 * edge or graph identity: cached graphs rebuild every few ticks (the 4/20 TTL) and would
 * truncate any history stored on them, while the node-pair key survives rebuilds, cache
 * flushes, and the cache being disabled outright. Two parallel runs of the SAME length between
 * the same nodes share one trace — accepted for a diagnostic. Memory-only, server-thread only;
 * rings nothing records into for {@link #STALE_TICKS} are swept on a slow cadence, and the map
 * clears on server stop.
 */
public final class FlowTrace {
    /** Solves of history per edge — enough to show a limit cycle's full period at a glance. */
    public static final int SAMPLES = 8;
    private static final int STALE_TICKS = 1200;
    private static final int SWEEP_INTERVAL_TICKS = 200;

    /** One recorded solve: the game tick, the actual mB moved, and the solved direction. */
    public record Sample(long tick, int mb, EdgeFlow.Direction dir) {}

    private record Key(ResourceKey<Level> dim, BlockPos lo, BlockPos hi, int length) {}

    private static final class Ring {
        final long[] tick = new long[SAMPLES];
        final int[] mb = new int[SAMPLES];
        final EdgeFlow.Direction[] dir = new EdgeFlow.Direction[SAMPLES];
        int count;
        int cursor = -1;

        void push(long now, int amount, EdgeFlow.Direction direction) {
            cursor = (cursor + 1) % SAMPLES;
            tick[cursor] = now;
            mb[cursor] = amount;
            dir[cursor] = direction;
            if (count < SAMPLES) count++;
        }

        List<Sample> newestFirst() {
            List<Sample> out = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int slot = Math.floorMod(cursor - i, SAMPLES);
                out.add(new Sample(tick[slot], mb[slot], dir[slot]));
            }
            return out;
        }
    }

    private static final Map<Key, Ring> TRACES = new HashMap<>();
    private static long lastSweep = Long.MIN_VALUE;

    private FlowTrace() {}

    /** Record one solved+executed tick's per-edge actuals; called once per network per solve. */
    public static void record(Level level, Graph graph, Solution solution, long now) {
        for (Edge edge : graph.edges()) {
            EdgeFlow flow = solution.edgeFlows().get(edge.index());
            TRACES.computeIfAbsent(keyOf(level, graph, edge), k -> new Ring())
                    .push(now, solution.actualFlow()[edge.index()],
                            flow == null ? EdgeFlow.Direction.NONE : flow.direction());
        }
        sweep(now);
    }

    /** The edge's recorded solves, newest first — resolves by positions, so ANY built graph works. */
    public static List<Sample> recent(Level level, Graph graph, Edge edge) {
        Ring ring = TRACES.get(keyOf(level, graph, edge));
        return ring == null ? List.of() : ring.newestFirst();
    }

    /** Forget everything — server stop. */
    public static void clear() {
        TRACES.clear();
        lastSweep = Long.MIN_VALUE;
    }

    private static Key keyOf(Level level, Graph graph, Edge edge) {
        BlockPos pa = graph.node(edge.a()).pos();
        BlockPos pb = graph.node(edge.b()).pos();
        boolean ordered = pa.asLong() <= pb.asLong();
        return new Key(level.dimension(), ordered ? pa : pb, ordered ? pb : pa, edge.length());
    }

    /** Drop rings of edges that stopped existing (broken runs, unloaded areas) — no event fires. */
    private static void sweep(long now) {
        if (now - lastSweep < SWEEP_INTERVAL_TICKS) return;
        lastSweep = now;
        TRACES.values().removeIf(ring -> now - ring.tick[Math.max(0, ring.cursor)] > STALE_TICKS);
    }
}
