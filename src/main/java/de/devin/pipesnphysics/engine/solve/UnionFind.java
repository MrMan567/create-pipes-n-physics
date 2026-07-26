package de.devin.pipesnphysics.engine.solve;

/**
 * Path-halving disjoint-set over {@code n} integer nodes. Minecraft-free, so both the pure solver
 * (capacitance-free component pruning) and transfer planning (hydraulic islands) share one copy.
 */
public final class UnionFind {
    private final int[] parent;

    /** A forest of {@code n} singleton components, each node its own root. */
    public UnionFind(int n) {
        parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
    }

    /** Canonical root of {@code i}'s component, halving the path as it walks. */
    public int find(int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    /** Merge the components of {@code a} and {@code b}. */
    public void union(int a, int b) {
        parent[find(a)] = find(b);
    }

    /** Component root per node, path-compressed — the id array transfer planning keys islands by. */
    public int[] roots() {
        int[] roots = new int[parent.length];
        for (int i = 0; i < parent.length; i++) roots[i] = find(i);
        return roots;
    }
}
