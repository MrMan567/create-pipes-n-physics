package de.devin.pipesnphysics.engine.graph;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A contracted pipe network.
 *
 * A Graph is the pure, Minecraft-independent shape of one connected fluid network.
 * It is built by {@link GraphBuilder#build} from the world and then consumed
 * by {@link FlowSolver} to decide where fluid moves.
 *
 * Invariants:
 *   - Node indices match their position in nodes (node.index() == nodes.indexOf(node)).
 *   - Edge endpoints (a, b) are valid node indices.
 *   - The graph is connected (single connected component).
 *   - coverage contains EVERY world position the discovery walk touched (all pipes,
 *     pumps, and handlers), including cells that did not survive contraction such as
 *     self-loops. {@link EngineTickHandler} uses it to tick each network exactly once.
 *
 * A graph is immutable — to reflect topology changes, build a new one — which is what lets
 * {@link GraphCache} reuse it across ticks, and why incidence, position, and kind lookups are
 * precomputed here once instead of being linear scans on every per-tick call.
 */
public final class Graph {
    private final List<Node> nodes;
    private final List<Edge> edges;
    private final Set<BlockPos> coverage;
    private final List<List<Edge>> incident;
    private final Map<BlockPos, Node> byPos;
    private final List<Node> handlers;
    private final List<Node> pumps;

    /**
     * Builds the graph and precomputes its lookup indices (edge incidence per node, node by
     * position, handler and pump lists) exactly once. The given lists become the graph's own
     * state and must not be mutated afterwards.
     */
    public Graph(List<Node> nodes, List<Edge> edges, Set<BlockPos> coverage) {
        this.nodes = nodes;
        this.edges = edges;
        this.coverage = coverage;

        List<List<Edge>> incident = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) incident.add(new ArrayList<>());
        for (Edge e : edges) {
            incident.get(e.a()).add(e);
            incident.get(e.b()).add(e);
        }
        incident.replaceAll(Collections::unmodifiableList);
        this.incident = incident;

        Map<BlockPos, Node> byPos = new HashMap<>();
        List<Node> handlers = new ArrayList<>();
        List<Node> pumps = new ArrayList<>();
        for (Node n : nodes) {
            byPos.put(n.pos(), n);
            if (n.isHandler()) handlers.add(n);
            if (n.isPump()) pumps.add(n);
        }
        this.byPos = byPos;
        this.handlers = Collections.unmodifiableList(handlers);
        this.pumps = Collections.unmodifiableList(pumps);
    }

    public List<Node> nodes() { return nodes; }

    public List<Edge> edges() { return edges; }

    public Set<BlockPos> coverage() { return coverage; }

    public Node node(int index) { return nodes.get(index); }

    public Edge edge(int index) { return edges.get(index); }

    /** Edges incident to the given node, in no particular order. The returned list is unmodifiable. */
    public List<Edge> edgesOf(int nodeIndex) { return incident.get(nodeIndex); }

    /** All HANDLER nodes (tanks, basins, drains, etc.). The returned list is unmodifiable. */
    public List<Node> handlers() { return handlers; }

    /** All PUMP nodes. The returned list is unmodifiable. */
    public List<Node> pumps() { return pumps; }

    /** Find a node by world position, or null if not in this graph. */
    public Node nodeAt(BlockPos pos) { return byPos.get(pos); }

    public boolean isEmpty() { return nodes.isEmpty(); }
}
