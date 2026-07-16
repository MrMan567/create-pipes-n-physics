package de.devin.pipesnphysics.engine.solve;

import java.util.Arrays;
import java.util.List;

/**
 * Pure hydraulic network solver. Has no Minecraft dependencies so it can be
 * unit-tested directly.
 *
 * The network is modeled as an electrical-circuit analogue. Nodes carry a hydraulic
 * head (fluid surface height, in blocks): nodes with positive capacitance are
 * reservoirs (tanks/basins), where capacitance is the volume in mB needed to raise
 * their head by one block, while nodes with zero capacitance are junctions or
 * pumps — pure Kirchhoff nodes whose head is solved from flow conservation.
 * Branches are pipe runs: a conductance (mB/tick of flow per block of head
 * difference), an optional EMF (a pump's head boost, in blocks, driving a→b when
 * positive), an optional one-way constraint (check valve), and an optional crest
 * gate (the highest cell of the run; flow is cut when the interpolated head at the
 * crest falls more than the suction limit below it — the siphon/cavitation rule).
 *
 * One call advances the network by one tick using an implicit Euler step:
 * the linear system {@code (C/dt + L) h' = (C/dt) h + emf terms} is solved for the
 * end-of-tick heads, and branch flows are read off the solved heads. Implicit Euler
 * is unconditionally stable: reservoir heads converge monotonically toward
 * equilibrium and can never overshoot or oscillate, regardless of conductance,
 * capacitance, or tick length. This is the property that makes tank equalization
 * settle instead of sloshing forever.
 *
 * One-way and crest constraints are enforced with an active-set loop: solve, drop
 * every branch whose solved flow violates a constraint, re-solve. Each iteration
 * only removes branches, so the loop terminates in at most |branches| rounds.
 */
public final class NetworkSolver {
    /** Flows smaller than this (mB/tick) are treated as zero for constraint checks. */
    private static final double FLOW_TOLERANCE = 1.0e-7;

    /** Head overshoot past a box bound (blocks) below which a node is NOT counted saturated. */
    private static final double SATURATION_TOLERANCE = 1.0e-6;

    /** Node count above which the iterative solver replaces Gaussian elimination. */
    private static final int DIRECT_SOLVE_LIMIT = 128;

    /** Fraction of the suction limit over which crest flow tapers to zero (no cliff). */
    private static final double CREST_TAPER_FRACTION = 0.25;

    /** Friction-free potential of a node no supply can reach: far below any real head. */
    private static final double NO_SUPPLY = -1.0e9;

    private NetworkSolver() {}

    /**
     * Surface "head" of a reservoir column. Liquids stack DOWNWARD — head rises with
     * elevation, so a liquid pools in the lowest connected vessel and communicating
     * vessels settle at equal surface lines. Lighter-than-air fluids stack UPWARD — head
     * FALLS with elevation, so a gas pools in the HIGHEST vessel, the exact mirror image.
     *
     * The buoyant mirror is deliberately density-INDEPENDENT: any lighter-than-air fluid
     * inverts as hard as gravity pulls a liquid down, rather than scaling with how light
     * it is. (Scaling the lift by relative density floored buoyancy at ~1% of gravity for
     * ordinary gases, so they equalized by volume like a liquid instead of rising — the
     * regression this restores.)
     */
    public static double surfaceHead(double baseY, double fillHeight, boolean lighterThanAir) {
        return lighterThanAir ? fillHeight - baseY : baseY + fillHeight;
    }

    /**
     * One node of the solver network.
     *
     * A reservoir carries box limits on its end-of-tick head: {@code floor} (the head
     * when empty) and {@code ceiling} (the head when full). The active-set loop treats a
     * capacitor whose solved head would cross a bound as SATURATED and constrains its
     * branches — a full column may only GIVE, an empty one may only RECEIVE — so no
     * fictitious flow is routed into a full tank or out of an empty one. Junctions/pumps
     * (zero capacitance) and boundaries that manage their own one-way rules pass the
     * unbounded {@code (capacitance, head)} constructor, which leaves the box wide open
     * ({@code ±∞}) so the solve is byte-for-byte the plain linear step it was before.
     *
     * @param capacitance mB of volume per block of head; 0 for junctions and pumps
     * @param head        current head in blocks (fluid surface height for reservoirs;
     *                    ignored as input for zero-capacitance nodes)
     * @param floor       head at empty; the lower box bound (may be {@code -∞})
     * @param ceiling     head at full; the upper box bound (may be {@code +∞})
     */
    public record NodeSpec(double capacitance, double head, double floor, double ceiling) {
        /** An unbounded node (junction, pump, or a reservoir whose saturation is handled elsewhere). */
        public NodeSpec(double capacitance, double head) {
            this(capacitance, head, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
        }
    }

    /**
     * One branch of the solver network.
     *
     * @param a            first endpoint node index
     * @param b            second endpoint node index
     * @param conductance  mB/tick of flow per block of head difference; ≤ 0 disables the branch
     * @param emf          pump head in blocks, driving a→b flow when positive
     * @param allowedSign  +1 = only a→b flow allowed, -1 = only b→a, 0 = bidirectional
     * @param crestHeight  highest cell elevation along the run (blocks), or NaN for no gate
     * @param crestPos     fractional position of the crest along the run, 0 (at a) .. 1 (at b)
     * @param crestWet     whether the crest cell actually HOLDS fluid. Suction can hold an
     *                     existing column, never create one: a DRY crest above the reachable
     *                     potential gates the branch instead of letting it self-prime.
     */
    public record BranchSpec(int a, int b, double conductance, double emf, int allowedSign,
                             double crestHeight, double crestPos, boolean crestWet) {
        /** Primed-column convenience (crestWet = true) — the shape every pre-priming test models. */
        public BranchSpec(int a, int b, double conductance, double emf, int allowedSign,
                          double crestHeight, double crestPos) {
            this(a, b, conductance, emf, allowedSign, crestHeight, crestPos, true);
        }

        public static BranchSpec passive(int a, int b, double conductance) {
            return new BranchSpec(a, b, conductance, 0, 0, Double.NaN, 0);
        }
    }

    /**
     * Solver output. One Result is produced per active-set solve and consumed by
     * {@code FlowSolver}, which records the branch flows and plans transfers from it.
     *
     * @param heads           end-of-tick head per node
     * @param flows           flow per branch in mB/tick, positive = a→b
     * @param netInflow       net volume gained per node this tick in mB (negative = drained)
     * @param active          whether each branch survived the constraint gates
     * @param crestBlocked    branches whose liquid column broke at their crest
     * @param backflowBlocked branches deactivated because the net potential opposed
     *                        their one-way direction; on a pump's EMF branch this
     *                        means exactly "the opposing head exceeds the pump head"
     * @param saturation      per node: +1 a full reservoir clamped to give-only, -1 an
     *                        empty one clamped to receive-only, 0 free (junctions/pumps
     *                        and unbounded reservoirs are always 0)
     */
    public record Result(double[] heads, double[] flows, double[] netInflow,
                         boolean[] active, boolean[] crestBlocked, boolean[] backflowBlocked,
                         int[] saturation) {}

    /**
     * Advance the network by one tick of length {@code dt}.
     *
     * @param suctionLimit how far (blocks) the head at a crest may fall below the crest
     *                     before the liquid column breaks and the branch stops flowing
     */
    public static Result solve(List<NodeSpec> nodes, List<BranchSpec> branches,
                               double dt, double suctionLimit) {
        int n = nodes.size();
        int m = branches.size();

        boolean[] active = new boolean[m];
        double[] gateScale = new double[m];
        for (int e = 0; e < m; e++) {
            BranchSpec br = branches.get(e);
            active[e] = br.conductance() > 0 && br.a() != br.b()
                    && br.a() >= 0 && br.a() < n && br.b() >= 0 && br.b() < n;
            gateScale[e] = 1;
        }

        double[] flows = new double[m];
        boolean[] backflowBlocked = new boolean[m];
        int[] saturation = new int[n];
        double[] heads = runActiveSet(nodes, branches, active, gateScale, saturation,
                flows, backflowBlocked, dt);

        // Crest (siphon/cavitation) gating is evaluated exactly ONCE against the
        // ungated solution, then frozen for a final pass. Re-evaluating it against
        // its own output is a positive feedback loop on suction lines (less flow →
        // lower head at the crest → more gating) that spirals working lines to dead.
        boolean[] crestBlocked = new boolean[m];
        boolean gated = false;
        boolean hasCrest = false;
        for (int e = 0; e < m && !hasCrest; e++) {
            hasCrest = active[e] && !Double.isNaN(branches.get(e).crestHeight());
        }
        // The crest gate measures whether a liquid column can EXIST over the run's
        // high point, which depends on the supply elevation and pump lift — NOT on
        // how far a fast flow's friction transiently drags the solved heads down.
        // Evaluating it against the friction-free reachable potential is what stops a
        // strong pump's own suction drawdown (which scales with RPM) from talking a
        // working line into a false cavitation cutoff.
        double[] potentials = hasCrest
                ? frictionFreePotentials(nodes, branches, active, suctionLimit) : heads;
        for (int e = 0; e < m; e++) {
            if (!active[e]) continue;
            double factor = crestFactor(branches.get(e), flows[e], potentials, suctionLimit);
            if (factor <= 0) {
                active[e] = false;
                crestBlocked[e] = true;
                gated = true;
            } else if (factor < 1) {
                gateScale[e] = factor;
                gated = true;
            }
        }
        if (gated) {
            // The pre-crest solve's one-way deactivations were computed against phantom flow through
            // crest-broken branches carrying FULL conductance — a crest-broken feeder's pre-gate
            // pressure can backflow-block a working pump. Once the gate removes that feeder the pump
            // would deliver, so rebuild the active set from scratch (conductance-valid minus the frozen
            // crest-blocked branches) and clear the one-way flags before re-solving. gateScale and
            // crestBlocked stay frozen, so the crest gate remains one-shot; the re-run is still monotone.
            for (int e = 0; e < m; e++) {
                BranchSpec br = branches.get(e);
                active[e] = br.conductance() > 0 && br.a() != br.b()
                        && br.a() >= 0 && br.a() < n && br.b() >= 0 && br.b() < n
                        && !crestBlocked[e];
            }
            Arrays.fill(backflowBlocked, false);
            Arrays.fill(saturation, 0);
            heads = runActiveSet(nodes, branches, active, gateScale, saturation,
                    flows, backflowBlocked, dt);
        }

        double[] netInflow = new double[n];
        for (int e = 0; e < m; e++) {
            if (flows[e] == 0) continue;
            BranchSpec br = branches.get(e);
            netInflow[br.a()] -= flows[e] * dt;
            netInflow[br.b()] += flows[e] * dt;
        }

        return new Result(heads, flows, netInflow, active, crestBlocked, backflowBlocked, saturation);
    }

    /**
     * Solve heads and flows under BOTH the static one-way constraints (check valves,
     * pumps, lips) and the dynamic capacity box: solve, then deactivate any branch whose
     * flow opposes its EFFECTIVE one-way sign (static sign combined with the saturation
     * of its endpoints), clamp any free reservoir whose solved head crossed a box bound —
     * a node over its ceiling becomes {@code +1} (full → give-only), one under its floor
     * {@code -1} (empty → receive-only) — and re-solve until no branch changes.
     * Deactivation is MONOTONE — a branch is only ever dropped, never restored — so the
     * loop terminates in at most {@code |branches|} rounds.
     *
     * Saturation is seeded ONCE from the START-of-tick heads: a reservoir sitting at (or
     * past) a box bound before the step is clamped, one with room is not. The end-of-tick
     * head legitimately overshoots a bound within a single implicit-Euler step (a near-full
     * tank a strong pump fills past 100% this tick); the transfer layer clamps that MAGNITUDE
     * to the real remaining room, so walling on the solved overshoot would freeze a tank
     * short of full forever. Direction is the solver's job, magnitude the transfer layer's.
     *
     * The saturation constraint is a per-BRANCH direction wall, not a head clamp on the
     * node: fixing a full node's head and letting the QP REJECT the surplus inflow would be
     * non-conservative — it lets an upstream tank bleed into a full sink instead of backing
     * up. Blocking the branch is what makes the fluid back up into a reservoir with room.
     * When both endpoints' saturation demand OPPOSITE directions on one branch (two full
     * ends facing each other, or a full end whose only opening rises above its waterline)
     * the branch is a DEAD CONDUIT: zero conductance, no flow either way, but left in the
     * component so it neither circulates fluid nor starves an upstream reservoir.
     */
    private static double[] runActiveSet(List<NodeSpec> nodes, List<BranchSpec> branches,
                                         boolean[] active, double[] gateScale, int[] saturation,
                                         double[] flows, boolean[] backflowBlocked, double dt) {
        int n = nodes.size();
        int m = branches.size();
        double[] heads = new double[n];
        double[] roundScale = new double[m];
        int[] effSign = new int[m];

        for (int i = 0; i < n; i++) {
            NodeSpec node = nodes.get(i);
            if (node.capacitance() <= 0) continue;
            if (node.head() >= node.ceiling() - SATURATION_TOLERANCE) saturation[i] = +1;
            else if (node.head() <= node.floor() + SATURATION_TOLERANCE) saturation[i] = -1;
        }

        for (int round = 0; round <= m; round++) {
            // Fold each endpoint's saturation into an effective one-way sign; a contradiction
            // makes the branch a dead conduit (zero conductance) for this round.
            for (int e = 0; e < m; e++) {
                if (!active[e]) { roundScale[e] = 0; effSign[e] = 0; continue; }
                BranchSpec br = branches.get(e);
                int es = effectiveSign(br.allowedSign(), saturation[br.a()], saturation[br.b()]);
                if (es == Integer.MIN_VALUE) {
                    roundScale[e] = 0;   // dead conduit: carries no flow, stays in the component
                    effSign[e] = 0;
                } else {
                    roundScale[e] = gateScale[e];
                    effSign[e] = es;
                }
            }

            pruneCapacitanceFreeComponents(nodes, branches, active);

            heads = solveHeads(nodes, branches, active, roundScale, dt);

            boolean changed = false;
            for (int e = 0; e < m; e++) {
                if (!active[e] || roundScale[e] == 0) {
                    flows[e] = 0;
                    continue;
                }
                BranchSpec br = branches.get(e);
                double q = roundScale[e] * br.conductance()
                        * (heads[br.a()] - heads[br.b()] + br.emf());
                flows[e] = q;

                if (violatesDirection(effSign[e], q)) {
                    active[e] = false;
                    backflowBlocked[e] = true;
                    flows[e] = 0;
                    changed = true;
                }
            }

            if (!changed) break;
        }
        return heads;
    }

    private static boolean violatesDirection(int allowedSign, double flow) {
        return allowedSign != 0 && flow * allowedSign < -FLOW_TOLERANCE;
    }

    /**
     * A branch's one-way sign after combining its static constraint with the saturation
     * of each endpoint, or {@link Integer#MIN_VALUE} when the two disagree (a dead conduit).
     * A full node ({@code +1}) may only GIVE — flow OUT of it; an empty node ({@code -1})
     * may only RECEIVE — flow INTO it. Flow OUT of endpoint {@code a} is {@code a→b} ({@code +1}),
     * out of {@code b} is {@code b→a} ({@code -1}).
     */
    private static int effectiveSign(int staticSign, int satA, int satB) {
        int sign = combineSign(staticSign, inducedSign(satA, true));
        return combineSign(sign, inducedSign(satB, false));
    }

    private static int inducedSign(int saturation, boolean atA) {
        if (saturation == 0) return 0;
        int outward = atA ? +1 : -1;                  // sign of "flow leaves this endpoint"
        return saturation > 0 ? outward : -outward;   // full gives (outward), empty receives (inward)
    }

    /** Intersect two one-way signs, or {@link Integer#MIN_VALUE} if they contradict. */
    private static int combineSign(int current, int wanted) {
        if (wanted == 0) return current;
        if (current == 0 || current == wanted) return wanted;
        return Integer.MIN_VALUE;
    }

    /**
     * How much of this branch's conductance the crest gate permits: 1 with the crest
     * comfortably below the local potential, tapering linearly to 0 as the suction
     * deficit approaches the limit. A pump's EMF raises the potential profile from the
     * end it drives, so a powered line can cross a rise an unpowered siphon cannot.
     *
     * {@code potentials} are the FRICTION-FREE reachable heads (see
     * {@link #frictionFreePotentials}), not the solved heads: the column's existence
     * is set by elevation and lift, so flow-rate drawdown must not enter here.
     */
    private static double crestFactor(BranchSpec br, double flow, double[] potentials,
                                      double suctionLimit) {
        if (Double.isNaN(br.crestHeight()) || Math.abs(flow) <= FLOW_TOLERANCE) return 1;

        double headA = potentials[br.a()];
        double headB = potentials[br.b()];
        double headAtCrest = br.emf() >= 0
                ? (headA + br.emf()) + (headB - headA - br.emf()) * br.crestPos()
                : headA + (headB - br.emf() - headA) * br.crestPos();

        double deficit = br.crestHeight() - headAtCrest;
        if (deficit <= 0) return 1;
        // Suction can HOLD a column over a crest, never CREATE one: with the crest DRY and above
        // every reachable potential, nothing pushes the fluid up the empty leg — an unprimed
        // siphon must not climb by itself (it used to: the sink drained at the solved trickle
        // while the "flow" just filled the ascending leg). A pump's EMF raises the potential
        // profile, so a powered line still primes over the rise; once fluid tops the crest the
        // normal taper takes over and the siphon sustains pump-less.
        if (!br.crestWet()) return 0;
        double taperBand = Math.max(0.5, suctionLimit * CREST_TAPER_FRACTION);
        return Math.clamp((suctionLimit - deficit) / taperBand, 0, 1);
    }

    /**
     * Friction-free reachable head at every node: each reservoir surface propagated
     * outward along active branches, gaining each pump's boost, taking the maximum —
     * but ONLY across crests the propagated head can itself clear. Conductance, and so
     * flow-rate drawdown, is omitted: the crest gate reflects the static pressure
     * profile a primed line holds, not this tick's transient drawdown. And because a
     * supply that cannot surmount a crest must not leak its head past it, a broken
     * crest stops the friction-free reach exactly as it stops real flow — without
     * this, an isolated reservoir behind an unprimable crest would falsely prime a
     * SECOND crest downstream and drain over a rise nothing can clear.
     *
     * Reservoirs seed from their own surface; every other node starts with NO supply
     * (a low sentinel) and earns a potential only through reachable, primable paths.
     * Solved heads are deliberately NOT used as a floor — they already carry the
     * pre-gate flow across crests that are about to break, which is the very leak.
     */
    private static double[] frictionFreePotentials(List<NodeSpec> nodes, List<BranchSpec> branches,
                                                   boolean[] active, double suctionLimit) {
        int n = nodes.size();
        double[] pot = new double[n];
        for (int i = 0; i < n; i++) {
            pot[i] = nodes.get(i).capacitance() > 0 ? nodes.get(i).head() : NO_SUPPLY;
        }
        for (int round = 0; round < n; round++) {
            boolean changed = false;
            for (int e = 0; e < branches.size(); e++) {
                if (!active[e]) continue;
                BranchSpec br = branches.get(e);
                if (br.allowedSign() >= 0) {
                    double via = pot[br.a()] + Math.max(0, br.emf());
                    if (clearsCrest(br, via, suctionLimit) && via > pot[br.b()] + 1e-9) {
                        pot[br.b()] = via;
                        changed = true;
                    }
                }
                if (br.allowedSign() <= 0) {
                    double via = pot[br.b()] + Math.max(0, -br.emf());
                    if (clearsCrest(br, via, suctionLimit) && via > pot[br.a()] + 1e-9) {
                        pot[br.a()] = via;
                        changed = true;
                    }
                }
            }
            if (!changed) break;
        }
        return pot;
    }

    /**
     * Whether a supply at {@code head} can prime a liquid column over this branch's
     * crest — the crest may sit at most the suction limit above it. A branch with no
     * crest is always clear.
     */
    private static boolean clearsCrest(BranchSpec br, double head, double suctionLimit) {
        return Double.isNaN(br.crestHeight()) || head >= br.crestHeight() - suctionLimit;
    }

    /**
     * Deactivate every branch in a connected component that holds no capacitance.
     * Such a component (a loop or run of bare junctions with no reservoir) has no
     * defined head and can carry no net fluid; removing it keeps the linear system
     * non-singular and guarantees pipe loops can never circulate fluid out of nothing.
     */
    private static void pruneCapacitanceFreeComponents(List<NodeSpec> nodes,
                                                       List<BranchSpec> branches,
                                                       boolean[] active) {
        int n = nodes.size();
        UnionFind uf = new UnionFind(n);

        for (int e = 0; e < branches.size(); e++) {
            if (!active[e]) continue;
            BranchSpec br = branches.get(e);
            uf.union(br.a(), br.b());
        }

        double[] componentCapacitance = new double[n];
        for (int i = 0; i < n; i++) {
            componentCapacitance[uf.find(i)] += nodes.get(i).capacitance();
        }

        for (int e = 0; e < branches.size(); e++) {
            if (!active[e]) continue;
            if (componentCapacitance[uf.find(branches.get(e).a())] <= 0) {
                active[e] = false;
            }
        }
    }

    /**
     * Assemble and solve the implicit-Euler system {@code A h' = rhs} with
     * {@code A = C/dt + L} (L the weighted graph Laplacian over active branches).
     * A is symmetric positive definite on every component that contains capacitance,
     * which the pruning pass guarantees.
     */
    private static double[] solveHeads(List<NodeSpec> nodes, List<BranchSpec> branches,
                                       boolean[] active, double[] gateScale, double dt) {
        int n = nodes.size();
        if (n <= DIRECT_SOLVE_LIMIT) {
            double[][] a = new double[n][n];
            double[] rhs = new double[n];
            for (int i = 0; i < n; i++) {
                a[i][i] = nodes.get(i).capacitance() / dt;
                rhs[i] = a[i][i] * nodes.get(i).head();
            }
            for (int e = 0; e < branches.size(); e++) {
                if (!active[e]) continue;
                BranchSpec br = branches.get(e);
                double c = gateScale[e] * br.conductance();
                a[br.a()][br.a()] += c;
                a[br.b()][br.b()] += c;
                a[br.a()][br.b()] -= c;
                a[br.b()][br.a()] -= c;
                rhs[br.a()] -= c * br.emf();
                rhs[br.b()] += c * br.emf();
            }
            for (int i = 0; i < n; i++) {
                if (a[i][i] == 0) {
                    a[i][i] = 1;
                    rhs[i] = nodes.get(i).head();
                }
            }
            return gaussianSolve(a, rhs);
        }
        return sparseConjugateGradient(nodes, branches, active, gateScale, dt);
    }

    /**
     * Solve {@code (C/dt + L) h = rhs} for a large network without ever materializing the dense
     * matrix. The Laplacian has only ~E nonzero off-diagonals, so a sparse matvec over the active
     * branches plus a diagonal is O(n+E) per iteration instead of the dense O(n²). A Jacobi (diagonal)
     * preconditioner collapses the stiff conditioning that reservoir capacitances (up to 4,000,000/dt
     * against ~conductance junction rows) impose, and the loop stops on a PHYSICAL head tolerance
     * (~1e-6 blocks) rather than machine precision. Scratch arrays are all allocated once. Matches the
     * dense path's system exactly, so it is identical numerically to the direct solve on the same input.
     */
    private static double[] sparseConjugateGradient(List<NodeSpec> nodes, List<BranchSpec> branches,
                                                    boolean[] active, double[] gateScale, double dt) {
        int n = nodes.size();
        int m = branches.size();
        double[] diag = new double[n];
        double[] rhs = new double[n];
        for (int i = 0; i < n; i++) {
            double c = nodes.get(i).capacitance() / dt;
            diag[i] = c;
            rhs[i] = c * nodes.get(i).head();
        }

        // Fold each active branch into the diagonal + rhs, and record its off-diagonal stamp (a,b,c)
        // for the matvec: A·p = diag∘p − Σ c·(p_b·e_a + p_a·e_b).
        int[] edgeNodeA = new int[m];
        int[] edgeNodeB = new int[m];
        double[] edgeConductance = new double[m];
        int edges = 0;
        for (int e = 0; e < m; e++) {
            if (!active[e]) continue;
            BranchSpec br = branches.get(e);
            double c = gateScale[e] * br.conductance();
            diag[br.a()] += c;
            diag[br.b()] += c;
            rhs[br.a()] -= c * br.emf();
            rhs[br.b()] += c * br.emf();
            edgeNodeA[edges] = br.a();
            edgeNodeB[edges] = br.b();
            edgeConductance[edges] = c;
            edges++;
        }
        for (int i = 0; i < n; i++) {
            if (diag[i] == 0) {
                diag[i] = 1;
                rhs[i] = nodes.get(i).head();
            }
        }

        double[] x = new double[n];
        double[] r = Arrays.copyOf(rhs, n); // r = rhs − A·x with x = 0
        double[] z = new double[n];
        double[] p = new double[n];
        double[] ap = new double[n];
        for (int i = 0; i < n; i++) {
            z[i] = r[i] / diag[i];
            p[i] = z[i];
        }
        double rzOld = dot(r, z); // preconditioned residual inner product r·z

        for (int iter = 0; iter < 20 * n && rzOld > 0; iter++) {
            for (int i = 0; i < n; i++) ap[i] = diag[i] * p[i];
            for (int e = 0; e < edges; e++) {
                ap[edgeNodeA[e]] -= edgeConductance[e] * p[edgeNodeB[e]];
                ap[edgeNodeB[e]] -= edgeConductance[e] * p[edgeNodeA[e]];
            }
            double pap = dot(p, ap);
            if (pap <= 0) break;
            double alpha = rzOld / pap;
            double maxStep = 0;
            for (int i = 0; i < n; i++) {
                double step = alpha * p[i];
                x[i] += step;
                r[i] -= alpha * ap[i];
                double abs = Math.abs(step);
                if (abs > maxStep) maxStep = abs;
            }
            if (maxStep < 1.0e-6) break; // heads settled to within 1e-6 blocks
            for (int i = 0; i < n; i++) z[i] = r[i] / diag[i];
            double rzNew = dot(r, z);
            double beta = rzNew / rzOld;
            for (int i = 0; i < n; i++) p[i] = z[i] + beta * p[i];
            rzOld = rzNew;
        }
        return x;
    }

    private static double[] gaussianSolve(double[][] a, double[] rhs) {
        int n = rhs.length;
        double[] x = Arrays.copyOf(rhs, n);

        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int row = col + 1; row < n; row++) {
                if (Math.abs(a[row][col]) > Math.abs(a[pivot][col])) pivot = row;
            }
            if (Math.abs(a[pivot][col]) < 1.0e-12) continue;

            double[] tmpRow = a[col]; a[col] = a[pivot]; a[pivot] = tmpRow;
            double tmpVal = x[col]; x[col] = x[pivot]; x[pivot] = tmpVal;

            for (int row = col + 1; row < n; row++) {
                double factor = a[row][col] / a[col][col];
                if (factor == 0) continue;
                for (int k = col; k < n; k++) a[row][k] -= factor * a[col][k];
                x[row] -= factor * x[col];
            }
        }

        for (int row = n - 1; row >= 0; row--) {
            double sum = x[row];
            for (int k = row + 1; k < n; k++) sum -= a[row][k] * x[k];
            x[row] = Math.abs(a[row][row]) < 1.0e-12 ? 0 : sum / a[row][row];
        }
        return x;
    }

    private static double dot(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) sum += a[i] * b[i];
        return sum;
    }
}
