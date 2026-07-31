package de.devin.pipesnphysics.engine;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.compat.SableCompat;
import de.devin.pipesnphysics.engine.boundary.RelayDetector;
import de.devin.pipesnphysics.engine.graph.Graph;
import de.devin.pipesnphysics.engine.graph.GraphBuilder;
import de.devin.pipesnphysics.engine.graph.GraphCache;
import de.devin.pipesnphysics.engine.graph.Node;
import de.devin.pipesnphysics.engine.motion.CentrifugeField;
import de.devin.pipesnphysics.engine.motion.CentrifugeProcessor;
import de.devin.pipesnphysics.engine.motion.MomentumField;
import de.devin.pipesnphysics.engine.probe.SublevelSpinProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Server tick driver for the engine.
 *
 * Pipes mark themselves dirty every tick (the transport-cancel mixin), so every
 * live network is seeded by all of its pipes. Three rules keep this cheap and make
 * each network tick exactly ONCE per server tick:
 *
 *   1. Seeds are resolved to a pipe position first ({@link GraphBuilder#findSeed}),
 *      so a mark on a pump's open face or a tank wall cannot bypass deduplication.
 *   2. The first seed to reach a network claims every position its discovery walk
 *      covered ({@link Graph#coverage}); later seeds inside the coverage are skipped.
 *      Without this an N-pipe network would be solved and transferred N times per
 *      tick — one of the root causes of the old engine's oscillations.
 *   3. Networks that solved to "no flow" are put to sleep and only re-checked on a
 *      heartbeat, unless something meaningful changed ({@link #markChanged}: pump
 *      flips, speed changes, topology edits), which wakes them immediately. The
 *      heartbeat is SLOW for a settled, pumpless network but FAST for one holding a
 *      running pump (it is armed — see {@link #recheckTicks}), so a pump-fed sink
 *      drained by a recipe, or fed from a source that just rose past its draw lip,
 *      catches up promptly instead of waiting out the full idle interval.
 */
public final class EngineTickHandler {
    private static final int IDLE_RECHECK_TICKS = 20;

    /**
     * The fast re-check for an ARMED-but-idle network: one holding a RUNNING PUMP that moved
     * nothing this tick. Such a pump is burning stress to deliver and is idle only because its
     * sink is momentarily full (or too high to lift) or its source momentarily below the draw
     * lip / empty — level changes inside a tank or basin that fire NO block event to wake us.
     * It must top its sink off (or resume from its refilling source) the instant conditions
     * allow, so it re-checks this much sooner than a truly idle (pumpless, settled) network —
     * otherwise a basin consumed by a recipe, or a sink one block above a draining source,
     * only catches up once per {@link #IDLE_RECHECK_TICKS} heartbeat, reading as "only refills
     * after it empties". A dead-headed pump (a NO_HEAD edge) is the original case; a strong
     * pump pinned to zero flow by an unsuppliable source — which carries no NO_HEAD flag — is
     * the one {@link #recheckTicks} also catches.
     */
    private static final int BACKED_UP_RECHECK_TICKS = 4;

    /** How often orphaned graph-cache entries are swept (memory reclamation, not correctness). */
    private static final int CACHE_SWEEP_INTERVAL_TICKS = 200;

    private static final Map<ResourceKey<Level>, Set<BlockPos>> DIRTY = new HashMap<>();
    private static final Map<ResourceKey<Level>, Set<BlockPos>> URGENT = new HashMap<>();
    /** Per dimension: pos → game time until which its network sleeps. */
    private static final Map<ResourceKey<Level>, Map<BlockPos, Long>> QUIET = new HashMap<>();

    private EngineTickHandler() {}

    /**
     * The ONE decision for suppressing Create's own fluid transport at a block entity — shared
     * by every cancel site (the base behaviour tick AND the pump behaviour's subclass tick), so
     * they can never disagree. True when the engine owns transport here: engine enabled and the
     * block is real — or virtual on a PONDER client, where the engine runs live and Create's
     * animation would fight it. Server-side virtual blocks and the flag-off case keep Create's
     * behavior untouched.
     */
    public static boolean suppressesCreateTransport(SmartBlockEntity blockEntity) {
        if (!PipesNPhysicsConfig.ENABLE_ENGINE.get()) return false;
        Level level = blockEntity.getLevel();
        if (level == null) return false;
        if (blockEntity.isVirtual()) {
            return level.isClientSide() && PipesNPhysicsConfig.ENABLE_PONDER_ENGINE.get();
        }
        return true;
    }

    /** Routine per-tick mark; honored unless the network is sleeping. */
    public static void markDirty(Level level, BlockPos pos) {
        if (level.isClientSide()) return;
        DIRTY.computeIfAbsent(level.dimension(), k -> new HashSet<>()).add(pos.immutable());
    }

    /**
     * Something meaningful changed (pump flip, speed, topology): wake the network AND evict its
     * cached graph, so the wake re-discovers the network instead of re-solving a stale shape.
     */
    public static void markChanged(Level level, BlockPos pos) {
        if (level.isClientSide()) return;
        markDirty(level, pos);
        URGENT.computeIfAbsent(level.dimension(), k -> new HashSet<>()).add(pos.immutable());
        GraphCache.invalidateAround(level, pos);
    }

    /**
     * The network must re-solve (its sub-level moved or tilted, re-projecting every elevation) but
     * its shape is unchanged: wake WITHOUT evicting the cached graph. Plot-coordinate topology is
     * motion-invariant and every solve-relevant elevation is re-read fresh each solve, so a cruising
     * contraption keeps its graph instead of paying a full rebuild every tick.
     */
    public static void markMoved(Level level, BlockPos pos) {
        if (level.isClientSide()) return;
        markDirty(level, pos);
        URGENT.computeIfAbsent(level.dimension(), k -> new HashSet<>()).add(pos.immutable());
    }

    /** Whether pos is queued for an URGENT (wake) re-check this tick. Test/diagnostic hook. */
    public static boolean hasPendingUrgent(Level level, BlockPos pos) {
        Set<BlockPos> urgent = URGENT.get(level.dimension());
        return urgent != null && urgent.contains(pos);
    }

    /** Discard all pending work — called on server stop. */
    public static void clear() {
        DIRTY.clear();
        URGENT.clear();
        QUIET.clear();
        GraphCache.clear();
        FlowTrace.clear();
    }

    /**
     * Whether pos belongs to a network that is currently sleeping. The sub-level driver uses this
     * to skip seeding quiet contraption cells — one lookup instead of the mark-and-bounce through
     * DIRTY that every sleeping cell otherwise pays each tick.
     */
    public static boolean isQuiet(Level level, BlockPos pos, long now) {
        Map<BlockPos, Long> quiet = QUIET.get(level.dimension());
        if (quiet == null) return false;
        Long until = quiet.get(pos);
        return until != null && until > now;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!PipesNPhysicsConfig.ENABLE_ENGINE.get()) return;
        // Sable contraptions are assembled with no place event and their dry pipes never
        // self-tick, so the engine never wakes on a sub-level. Seed every sub-level pipe cell
        // each tick (the QUIET sleep still throttles re-solves); a no-op without full Sable.
        boolean sweep = event.getServer().getTickCount() % CACHE_SWEEP_INTERVAL_TICKS == 0;
        for (ServerLevel level : event.getServer().getAllLevels()) {
            SableCompat.seedSubLevels(level, EngineTickHandler::markDirty);
            // Reclaim cache entries of networks that died with no signal (disassembled
            // contraptions, exploded runs) — nothing ever seeds them again, so only this sweeps.
            if (sweep) GraphCache.sweep(level, level.getGameTime());
        }
        // Momentum frames are keyed per sub-level and centrifuge measurements per cell, both across
        // dimensions, so sweep once — reclaims what a disassembled contraption leaves behind, which
        // nothing ever looks up again. Same for relay-detector samples of handlers that left the
        // network with no break event.
        if (sweep) {
            long swept = event.getServer().overworld().getGameTime();
            MomentumField.sweep(swept);
            CentrifugeField.sweep(swept);
            RelayDetector.sweep(swept);
        }
        if (PipesNPhysicsConfig.DEBUG_SUBLEVEL_SPIN.get()) {
            SublevelSpinProbe.tick(event.getServer());
        }
        if (DIRTY.isEmpty() && URGENT.isEmpty()) return;
        event.getServer().getAllLevels().forEach(EngineTickHandler::tickLevel);
    }

    private static void tickLevel(ServerLevel level) {
        Set<BlockPos> work = DIRTY.remove(level.dimension());
        Set<BlockPos> urgent = URGENT.remove(level.dimension());
        if (!PipesNPhysicsConfig.ENABLE_ENGINE.get()) return;
        if (work == null) work = Set.of();
        if (urgent == null) urgent = Set.of();

        Map<BlockPos, Long> quiet = QUIET.computeIfAbsent(level.dimension(), k -> new HashMap<>());
        long now = level.getGameTime();
        Set<BlockPos> covered = new HashSet<>();

        for (BlockPos pos : urgent) {
            tickNetwork(level, pos, covered, quiet, now, true);
        }
        for (BlockPos pos : work) {
            tickNetwork(level, pos, covered, quiet, now, false);
        }

        if (quiet.size() > 4096) quiet.values().removeIf(until -> until <= now);
    }

    private static void tickNetwork(ServerLevel level, BlockPos pos, Set<BlockPos> covered,
                                    Map<BlockPos, Long> quiet, long now, boolean wake) {
        if (!level.isLoaded(pos)) return;
        // Fast path: every pipe self-marks each tick, and a pipe IS a coverage/quiet cell, so check the
        // RAW pos before resolving findSeed (a chunk BE lookup plus up to six neighbour lookups). Both
        // maps are keyed by every coverage cell, so a hit means this pos is an already-solved or still-
        // sleeping pipe — skip it. Non-pipe marks (a pump face, a tank wall) miss and fall through.
        if (covered.contains(pos)) return;
        if (!wake) {
            Long sleepUntil = quiet.get(pos);
            if (sleepUntil != null && sleepUntil > now) return;
        }
        Graph graph = resolveGraph(level, pos, covered, quiet, now, wake);
        if (graph == null) return;
        covered.addAll(graph.coverage());

        Solution solution = FlowSolver.solve(level, graph);

        // Execute the solved flows as conserved plug flow through the pipes' stored volume:
        // travel time, backpressure, and the idle settle-back are all real fluid movement now,
        // so there is no separate render pass or delivery gate. A still-settling network stays
        // awake until its contents come to rest.
        PipeFlowExecutor.Actuals actuals = FluidEngine.apply(level, graph, solution);
        CentrifugeProcessor.process(level, graph, now);

        boolean busy = solution.active() || actuals.movedAny() || actuals.settling();
        boolean armed = hasRunningPump(level, graph);
        // Busy/armed networks get the fast graph-cache TTL: they are the ones that would route
        // fluid over a silently edited run, so their stale-shape window stays at the armed cadence.
        GraphCache.recordSolve(level, graph, solution, now, busy || armed);
        FlowTrace.record(level, graph, solution, now);

        if (busy) {
            graph.coverage().forEach(quiet::remove);
        } else {
            // Clamped so the wake ending this sleep never re-solves a graph OLDER than the sleep:
            // if the cached entry expires mid-sleep, wake right then and rebuild.
            long until = Math.min(now + recheckTicks(solution, armed), GraphCache.expiry(level, graph));
            for (BlockPos cell : graph.coverage()) quiet.put(cell, until);
        }
    }

    /**
     * The graph this seed's network should solve against: the cached one when fresh, else a
     * resolved-seed cache hit, else a fresh build (which is stored). Null means skip this seed —
     * no network here, already solved or still sleeping under its resolved seed, or a fresh build
     * that overlaps a network which already solved this tick (marked covered so later seeds skip
     * it too).
     */
    private static Graph resolveGraph(ServerLevel level, BlockPos pos, Set<BlockPos> covered,
                                      Map<BlockPos, Long> quiet, long now, boolean wake) {
        // Cached-network fast path: any coverage cell resolves the whole graph, skipping both
        // findSeed's neighbor ring and the BFS rebuild. Topology wakes (markChanged) evicted their
        // networks at mark time, so a hit here is a network nothing reshaped.
        Graph graph = GraphCache.get(level, pos, now);
        if (graph != null) return graph;
        BlockPos seed = GraphBuilder.findSeed(level, pos);
        if (seed == null || covered.contains(seed)) return null;
        if (!wake) {
            Long sleepUntil = quiet.get(seed);
            if (sleepUntil != null && sleepUntil > now) return null;
        }
        graph = GraphCache.get(level, seed, now);
        if (graph != null) return graph;
        graph = GraphBuilder.build(level, seed);
        if (graph.isEmpty()) return null;
        GraphCache.store(level, graph, now);
        // A silently placed pipe (schematicannon — no event, no eviction) can extend or
        // bridge a network that ALREADY solved this tick off its stale cached graph: this
        // fresh build then contains already-solved cells, and solving it too would move up
        // to double the per-endpoint cap in one tick. Store it (next tick's lookup gets the
        // merged shape, displacing the stale halves) but skip this tick's solve, preserving
        // the one-solve-per-network-per-tick rule. Only exclusive cells count as overlap:
        // an open-end space block or per-face handler is legitimately shared between two
        // live networks and must not starve the second one's rebuild.
        if (overlapsSolved(graph, covered)) {
            covered.addAll(graph.coverage());
            return null;
        }
        return graph;
    }

    /** Whether any of the graph's EXCLUSIVE cells (pipes, pumps, junctions) already solved this tick. */
    private static boolean overlapsSolved(Graph graph, Set<BlockPos> covered) {
        if (covered.isEmpty()) return false;
        for (BlockPos cell : graph.coverage()) {
            if (!covered.contains(cell)) continue;
            Node node = graph.nodeAt(cell);
            boolean shared = node != null
                    && (node.isOpenEnd() || (node.isHandler() && node.accessFace() != null));
            if (!shared) return true;
        }
        return false;
    }

    /**
     * How long an idle network may sleep before its next re-check. A network that is merely
     * settled (gravity equalized, pump off, no pump) only changes on a block edit and can sleep
     * the full {@link #IDLE_RECHECK_TICKS} heartbeat. A network holding a RUNNING PUMP — or one
     * showing a dead-headed NO_HEAD edge — is ARMED: it is actively trying to move fluid and is
     * idle only because of a transient (full sink, source below its draw lip) that fires no
     * block event, so it re-checks on the fast {@link #BACKED_UP_RECHECK_TICKS} heartbeat and
     * resumes promptly. (A running pump subsumes the NO_HEAD case, but both are kept explicit.)
     */
    public static int recheckTicks(Solution solution, boolean armedByPump) {
        return armedByPump || !solution.noHeadEdges().isEmpty()
                ? BACKED_UP_RECHECK_TICKS : IDLE_RECHECK_TICKS;
    }

    /** Whether any pump on this network is spun up — i.e. the network is armed (see {@link #recheckTicks}). */
    public static boolean hasRunningPump(ServerLevel level, Graph graph) {
        for (Node pump : graph.pumps()) {
            if (FlowSolver.isPumpRunning(level, pump)) return true;
        }
        return false;
    }
}
