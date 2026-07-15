package de.devin.pipesnphysics.engine.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.hosePulley.HosePulleyBlockEntity;
import com.simibubi.create.content.fluids.pipes.VanillaFluidTargets;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.engine.BoundaryColumn;
import de.devin.pipesnphysics.engine.Edge;
import de.devin.pipesnphysics.engine.EdgeFlow;
import de.devin.pipesnphysics.engine.FluidEngine;
import de.devin.pipesnphysics.engine.FlowSolver;
import de.devin.pipesnphysics.engine.Graph;
import de.devin.pipesnphysics.engine.GraphCache;
import de.devin.pipesnphysics.engine.HandlerRoles;
import de.devin.pipesnphysics.engine.Node;
import de.devin.pipesnphysics.engine.PipeProbe;
import de.devin.pipesnphysics.engine.RelayDetector;
import de.devin.pipesnphysics.engine.Solution;
import de.devin.pipesnphysics.compat.SableCompat;
import de.devin.pipesnphysics.engine.net.GraphOverlayPayload;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /pipegraph}: build the network at the player's crosshair, run the solver,
 * dump the result as chat lines, and send an in-world overlay to the same player.
 *
 * Intended for inspecting topology and verifying flow direction during development.
 */
public final class PipeGraphCommand {
    /** Read the engine's own solution when it is at most this many ticks old — the goggle's window. */
    private static final int SOLUTION_MAX_AGE_TICKS = 4;

    private PipeGraphCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("pipegraph")
                .requires(s -> s.hasPermission(0))
                .executes(PipeGraphCommand::run));
    }

    private static int run(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Must be run by a player"));
            return 0;
        }

        HitResult hit = player.pick(20.0, 0.0f, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            source.sendFailure(Component.literal("Not looking at a block"));
            return 0;
        }
        BlockPos target = ((BlockHitResult) hit).getBlockPos();
        ServerLevel level = player.serverLevel();

        Graph graph = FluidEngine.buildGraph(level, target);
        if (graph.isEmpty()) {
            // Not a pipe seed — inspect the block itself: what the engine classifies it as and why fluid
            // does (or does not) flow to it, then dump the network it touches, if any.
            sendBlockReport(player, level, target);
            return 1;
        }
        // Read the engine's OWN recent solution (the same source the goggle reads), not an
        // independent single-tick solve — else a bursty flow the goggle shows reads as idle here.
        Resolved resolved = recentSolve(level, target);
        if (resolved == null) return 1; // network vanished between build and solve

        sendText(player, level, resolved.graph(), resolved.solution());
        PacketDistributor.sendToPlayer(player,
                buildPayload(level, resolved.graph(), resolved.solution(), target));
        return 1;
    }

    /**
     * Build a live /pipegraph overlay for a seed — the server side of the {@code GraphOverlayRequest}
     * refresh the client fires while an overlay is on screen. Null when the seed no longer roots a
     * network. Uses the engine's recent solution so the in-world graph tracks a live/bursty flow.
     */
    public static GraphOverlayPayload buildOverlay(ServerLevel level, BlockPos seed) {
        Resolved resolved = recentSolve(level, seed);
        return resolved == null ? null
                : buildPayload(level, resolved.graph(), resolved.solution(), seed);
    }

    /**
     * The engine's cached graph + recent solution for a seed — the SAME data the goggle reads, so a
     * flow the goggle shows appears here too — falling back to a fresh build+solve when nothing is
     * cached. Null when the seed no longer roots a network.
     */
    private static Resolved recentSolve(ServerLevel level, BlockPos seed) {
        long now = level.getGameTime();
        Graph graph = GraphCache.get(level, seed, now);
        Solution solution = graph == null ? null
                : GraphCache.recentSolution(level, graph, now, SOLUTION_MAX_AGE_TICKS);
        if (graph == null) {
            graph = FluidEngine.buildGraph(level, seed);
            if (graph.isEmpty()) return null;
            GraphCache.store(level, graph, now);
        }
        if (solution == null) solution = FlowSolver.solve(level, graph);
        return new Resolved(graph, solution);
    }

    private record Resolved(Graph graph, Solution solution) {}

    /**
     * "What the engine sees" for a block that is NOT a pipe seed — a foreign tank/machine, or an
     * arbitrary block the crosshair landed on. Walks the whole classification chain that decides whether
     * fluid flows TO it: its fluid capability (and on which faces), whether it is a vanilla fluid target
     * routed to an open end, the ROLE the engine assigns and why, the live drain/fill probe, how the
     * boundary column resolves (the wall that can leave a block holding fluid it still won't move), and
     * whether an adjacent pipe actually opens toward it. When it touches a network the normal graph dump
     * follows. This is the compat-authoring counterpart to the player pipe goggle.
     */
    private static void sendBlockReport(ServerPlayer player, ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        send(player, "§e--- Engine view: §f" + state.getBlock().getName().getString()
                + " §7@ " + pos.toShortString() + " §e---");

        // Fluid capability, and on which faces. No handler anywhere and not a vanilla target → the engine
        // can never see it as a tank; a side-specific block serves its own network per face.
        IFluidHandler nullSide = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null);
        List<Direction> faces = new ArrayList<>();
        for (Direction d : Direction.values()) {
            if (level.getCapability(Capabilities.FluidHandler.BLOCK, pos, d) != null) faces.add(d);
        }
        boolean vanilla = VanillaFluidTargets.canProvideFluidWithoutCapability(state);
        if (vanilla) {
            // Even with a NeoForge cap (a cauldron), the engine deliberately skips these in the handler
            // branch and drinks them through an open pipe MOUTH — so its handler role is moot.
            send(player, "§7Fluid cap: §evanilla fluid target §7— drained as an OPEN_END through an open pipe mouth, not a tank node");
        } else if (nullSide != null) {
            send(player, "§7Fluid cap: §aside-agnostic §7(couples every run that touches it)");
        } else if (!faces.isEmpty()) {
            send(player, "§7Fluid cap: §eside-specific §7on faces §f" + faces
                    + " §7(each face is its own tank / network)");
        } else {
            send(player, "§7Fluid cap: §cnone §7— the engine cannot treat this block as a tank");
        }

        // Role + relay-detector state, only meaningful for a real handler node (not a vanilla open-end target).
        if (!vanilla && (nullSide != null || !faces.isEmpty())) {
            send(player, "§7Role: §f" + HandlerRoles.explain(level, pos));
            if (!HandlerRoles.hasExplicitRole(state) && PipesNPhysicsConfig.AUTO_DETECT_RELAY_HANDLERS.get()) {
                int strikes = RelayDetector.strikeCount(state.getBlock());
                if (RelayDetector.isRelay(state.getBlock())) {
                    send(player, "§7Detector: §elearned relay §7this session");
                } else if (strikes > 0) {
                    send(player, "§7Detector: §f" + strikes + " §7spontaneous-gain strike(s) so far");
                }
            }
            sendFaceProbe(player, level, pos, nullSide);
            send(player, "§7Column (what the engine resolves): §f"
                    + columnReport(level, pos, resolveAccessFace(level, pos)));
        }

        // Is an adjacent pipe/pump actually plumbed toward this block? Then dump the network it touches.
        BlockPos seed = reportNeighbors(player, level, pos);
        if (seed != null) {
            Graph g = FluidEngine.buildGraph(level, seed);
            if (!g.isEmpty()) {
                Solution s = FluidEngine.simulate(level, seed);
                send(player, "§7— network it connects to (seed " + seed.toShortString() + ") —");
                sendText(player, level, g, s);
                PacketDistributor.sendToPlayer(player, buildPayload(level, g, s, seed));
            }
        }
    }

    /**
     * The fluid handler PER FACE — the crux of "why won't it flow to this block." A machine can expose its
     * OUTPUT on only one face (a coke oven's CO2 on top) alongside a separate empty/input handler on the
     * {@code null} side and other faces; the engine resolves the {@code null} side FIRST, so it reads the
     * block EMPTY and never drains the face that actually gives. This dumps the {@code null} side and every
     * face's holds/give/take, flagging any face that gives MORE than the {@code null} side — the handler the
     * engine should be reading through.
     */
    private static void sendFaceProbe(ServerPlayer player, ServerLevel level, BlockPos pos, IFluidHandler nullSide) {
        FluidStack probe = probeFluid(level, pos);
        int nullGive = nullSide == null ? -1 : nullSide.drain(Integer.MAX_VALUE, FluidAction.SIMULATE).getAmount();
        send(player, "§7Handler per face §8(probe " + probe.getHoverName().getString() + "):");
        if (nullSide != null) send(player, "§d  null-side: " + oneProbe(nullSide, probe));
        for (Direction d : Direction.values()) {
            IFluidHandler cap = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, d);
            if (cap == null) continue;
            int give = cap.drain(Integer.MAX_VALUE, FluidAction.SIMULATE).getAmount();
            String flag = nullSide != null && cap != nullSide && give > nullGive
                    ? " §e← gives more than null-side; engine reads null → sees this block EMPTY" : "";
            send(player, "§d  " + d + ": " + oneProbe(cap, probe) + flag);
        }
    }

    /** One handler's live HOLDS / GIVE (drain) / TAKE (fill) probe against {@code probe}, with its fluid name. */
    private static String oneProbe(IFluidHandler cap, FluidStack probe) {
        FluidStack held = cap.getTanks() > 0 ? cap.getFluidInTank(0) : FluidStack.EMPTY;
        int give = cap.drain(Integer.MAX_VALUE, FluidAction.SIMULATE).getAmount();
        int take = cap.fill(probe, FluidAction.SIMULATE);
        return String.format("holds=%d give=%d take=%d%s", held.getAmount(), give, take,
                held.isEmpty() ? "" : " (" + held.getHoverName().getString() + ")");
    }

    /**
     * The face the engine resolves this block through, mirroring {@code GraphBuilder}: a connecting
     * (pipe/pump) face whose handler DIFFERS from the null side — the per-face endpoint (a coke oven's CO2
     * top) — else null for a side-agnostic block that resolves through its shared null handler. Lets the
     * "Column" line report the SAME tank the network actually reads, not the null side.
     */
    private static Direction resolveAccessFace(ServerLevel level, BlockPos pos) {
        IFluidHandler nullCap = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null);
        Direction differing = null;
        for (Direction d : Direction.values()) {
            IFluidHandler cap = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, d);
            if (cap == null || cap == nullCap) continue; // side-agnostic on this face → resolve null
            BlockPos neighbor = pos.relative(d);
            if (FluidPropagator.getPipe(level, neighbor) != null
                    || level.getBlockState(neighbor).getBlock() instanceof PumpBlock) {
                return d; // a plumbed face that differs from null — exactly the engine's access face
            }
            if (differing == null) differing = d;
        }
        return differing;
    }

    /** The first fluid any face (or the null side) of this block holds, or water — what to probe fills with. */
    private static FluidStack probeFluid(ServerLevel level, BlockPos pos) {
        IFluidHandler nullCap = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null);
        if (nullCap != null && nullCap.getTanks() > 0 && !nullCap.getFluidInTank(0).isEmpty()) {
            return nullCap.getFluidInTank(0).copyWithAmount(1000);
        }
        for (Direction d : Direction.values()) {
            IFluidHandler cap = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, d);
            if (cap != null && cap.getTanks() > 0 && !cap.getFluidInTank(0).isEmpty()) {
                return cap.getFluidInTank(0).copyWithAmount(1000);
            }
        }
        return new FluidStack(Fluids.WATER, 1000);
    }

    /**
     * How the engine's boundary column resolves this handler — the wall that can leave a block holding
     * fluid it still will not move: a finite reservoir surface-equalizes and lip-gates, an EMPTY one is
     * receive-only, a FULL one is give-only, a relay/pulley is a bottomless one-way endpoint. Builds the
     * same synthetic HANDLER node the solver would (its {@code accessFace} for a side-specific block) and
     * runs the real {@link BoundaryColumn#resolve}.
     */
    private static String columnReport(ServerLevel level, BlockPos pos, Direction accessFace) {
        Node synthetic = new Node(0, pos, Node.Kind.HANDLER, SableCompat.getWorldY(level, pos), null, null, accessFace);
        BoundaryColumn column = BoundaryColumn.resolve(level, synthetic);
        if (column == null) return "does not resolve (no live handler / multiblock mid-assembly)";
        String role = !column.isFiniteReservoir()
                ? (column.isInfiniteSource()
                        ? "bottomless SOURCE — one-way, drain-priority"
                        : "bottomless SINK — one-way, receive-only")
                : column.isEmpty() ? "finite reservoir, EMPTY → receive-only (fills, never drains)"
                : column.contentMb() >= column.capacityMb() ? "finite reservoir, FULL → give-only (drains, never fills)"
                : "finite reservoir — surface-equalized, lip-gated";
        return String.format("%s §7[%d/%d mB, %.0f%%]", role,
                column.contentMb(), column.capacityMb(), column.fillFraction() * 100);
    }

    /**
     * Report, per face, whether an adjacent pipe or pump is actually plumbed toward this block — the
     * silent "no connection" cause (a pipe that does not open back, common on stale Sable assemblies).
     * Returns a seed to dump the touched network, preferring a pipe that genuinely opens toward the
     * block; null when nothing adjacent connects.
     */
    private static BlockPos reportNeighbors(ServerPlayer player, ServerLevel level, BlockPos pos) {
        BlockPos seed = null;
        boolean any = false;
        for (Direction face : Direction.values()) {
            BlockPos neighbor = pos.relative(face);
            if (!level.isLoaded(neighbor)) continue;
            BlockState nState = level.getBlockState(neighbor);
            if (nState.getBlock() instanceof PumpBlock) {
                send(player, "§7  " + face + ": §badjacent pump");
                any = true;
                if (seed == null) seed = neighbor.immutable();
                continue;
            }
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, neighbor);
            if (pipe == null) continue;
            any = true;
            boolean opensBack = pipe.canHaveFlowToward(nState, face.getOpposite());
            send(player, "§7  " + face + ": pipe " + (opensBack
                    ? "§aopens toward it ✓"
                    : "§cpresent but does NOT open back ✗ §7(stale connection / not plumbed this side)"));
            if (opensBack) seed = neighbor.immutable();          // prefer a genuinely-connected pipe
            else if (seed == null) seed = neighbor.immutable();  // still dump the network so the graph shows
        }
        if (!any) send(player, "§7Neighbours: §cno adjacent pipe or pump §7— nothing to connect to");
        return seed;
    }

    private static void sendText(ServerPlayer player, ServerLevel level, Graph g, Solution s) {
        send(player, "§e--- Pipe Graph ---");
        send(player, "§7Nodes: §f" + g.nodes().size() + "  §7Edges: §f" + g.edges().size());
        FluidStack probeFluid = firstPresentFluid(level, g);
        for (Node n : g.nodes()) {
            Double head = s.nodeHeads().get(n.index());
            Double ceiling = s.nodeCeilings().get(n.index());
            String block = blockName(level, n);
            send(player, String.format("  §f%s §7%s §b%s §7y=§f%.1f%s%s%s%s",
                    n.pos().toShortString(), n.kind(), block,
                    n.worldY(),
                    head != null ? String.format(" §7head=§f%.2f", head) : "",
                    ceiling != null ? String.format(" §7ceil=§b%.2f", ceiling) : " §8ceil=∅",
                    n.pumpFacing() != null ? " §7face=§f" + n.pumpFacing() : "",
                    n.isPump() ? String.format(" §7rpm=§f%.0f", pumpSpeed(level, n)) : ""));
            BoundaryColumn column = columnOf(level, n);
            if (column != null && !column.contents().isEmpty() && column.contentMb() > 0) {
                send(player, "      §7" + (n.isOpenEnd()
                        ? "draws §f" + column.contents().getHoverName().getString()
                        : fluidSummary(column)));
            }
            String pulley = pulleyDiagnostic(level, n);
            if (pulley != null) send(player, "      §c" + pulley);
            String probe = handlerProbe(level, n, probeFluid);
            if (probe != null) send(player, "      §d" + probe);
            String dock = dockingDiagnostic(level, n);
            if (dock != null) send(player, "      §6" + dock);
        }
        sendFluidStats(player, level, g);
        send(player, "§e--- Edges ---");
        for (Edge e : g.edges()) {
            EdgeFlow flow = s.edgeFlows().get(e.index());
            int rate = PipeProbe.actualEdgeFlow(g, s, e); // mB actually moved, not the hydraulic flow
            String dir = switch (flow.direction()) {
                case A_TO_B -> "a→b";
                case B_TO_A -> "b→a";
                case NONE -> "idle";
            };
            if (rate == 0) dir = "idle";
            if (s.stalledEdges().contains(e.index())) dir = "§6stalled§7";
            if (s.noHeadEdges().contains(e.index())) dir = "§cno head§7";
            if (s.heldEdges().contains(e.index())) {
                Double h = heldHead(s, e);
                dir = h != null ? String.format("§dheld §7(stored §f%.2f§7)", h) : "§dheld§7";
            }
            Solution.Reason reason = s.edgeReasons().get(e.index());
            Node a = g.node(e.a()), b = g.node(e.b());
            send(player, String.format("  §e%s §f%s §7↔ §f%s §7len=%d §7%s §7solved=%d actual=%d mB/t%s",
                    GraphOverlayPayload.edgeLetter(e.index()),
                    a.pos().toShortString(), b.pos().toShortString(),
                    e.length(), dir, flow.mbPerTick(), rate,
                    reason != null ? " §8[" + reason + "]" : ""));
        }
        if (s.hasTransfer()) {
            for (Solution.Transfer transfer : s.transfers()) {
                send(player, String.format("§a> %d mB %s : %s → %s",
                        transfer.fluid().getAmount(),
                        transfer.fluid().getHoverName().getString(),
                        transfer.from().toShortString(), transfer.to().toShortString()));
            }
        } else {
            send(player, "§7> no transfer this tick");
        }
    }

    private static GraphOverlayPayload buildPayload(ServerLevel level, Graph g, Solution s, BlockPos seed) {
        List<GraphOverlayPayload.NodeEntry> nodes = new ArrayList<>(g.nodes().size());
        for (Node n : g.nodes()) {
            byte kind = switch (n.kind()) {
                case HANDLER -> GraphOverlayPayload.NodeEntry.KIND_HANDLER;
                case PUMP -> GraphOverlayPayload.NodeEntry.KIND_PUMP;
                case JUNCTION, CLOSED_GATE -> GraphOverlayPayload.NodeEntry.KIND_JUNCTION;
                case OPEN_END -> GraphOverlayPayload.NodeEntry.KIND_OPEN_END;
            };
            nodes.add(new GraphOverlayPayload.NodeEntry(
                    n.pos().getX(), n.pos().getY(), n.pos().getZ(), kind,
                    nodeLabel(level, n, s.nodeHeads().get(n.index()))));
        }

        List<GraphOverlayPayload.EdgeEntry> edges = new ArrayList<>(g.edges().size());
        for (Edge e : g.edges()) {
            EdgeFlow flow = s.edgeFlows().get(e.index());
            // The overlay reflects the ACTUAL fluid moved, like the pipe/pump goggle and the chat
            // dump's actual= column — NOT the solver's hydraulic flow. So an edge whose source/sink
            // throttles a solved flow down to nothing draws no arrow instead of a phantom one.
            int actual = PipeProbe.actualEdgeFlow(g, s, e);
            Node a = g.node(e.a()), b = g.node(e.b());

            List<BlockPos> orderedFromA = new ArrayList<>();
            orderedFromA.add(a.pos());
            orderedFromA.addAll(e.pipes());
            orderedFromA.add(b.pos());
            List<Float> pressuresFromA = pointPressures(level, s, e, orderedFromA);

            boolean reversed = flow.direction() == EdgeFlow.Direction.B_TO_A;
            List<BlockPos> ordered = reversed ? reverse(orderedFromA) : orderedFromA;
            List<Float> pressures = reversed ? reverse(pressuresFromA) : pressuresFromA;

            // Arrow only when fluid actually moves; a solved-but-stalled run still shows its rod
            // (no arrow), a held column stays magenta.
            byte dir = s.heldEdges().contains(e.index())
                    ? GraphOverlayPayload.EdgeEntry.DIR_HELD
                    : actual > 0
                    ? GraphOverlayPayload.EdgeEntry.DIR_FORWARD
                    : s.stalledEdges().contains(e.index())
                    ? GraphOverlayPayload.EdgeEntry.DIR_STALLED
                    : GraphOverlayPayload.EdgeEntry.DIR_NONE;

            List<Long> packed = new ArrayList<>(ordered.size());
            for (BlockPos p : ordered) packed.add(p.asLong());
            edges.add(new GraphOverlayPayload.EdgeEntry(packed, actual, dir, pressures));
        }

        return new GraphOverlayPayload(seed.asLong(), nodes, edges);
    }

    /**
     * Gauge pressure at each point of the run: the head interpolated between the
     * solved endpoint heads, minus the point's elevation. Empty when the edge was
     * not part of any solved fluid pass.
     */
    private static List<Float> pointPressures(ServerLevel level, Solution s, Edge e,
                                              List<BlockPos> orderedFromA) {
        Double headA = s.nodeHeads().get(e.a());
        Double headB = s.nodeHeads().get(e.b());
        if (headA == null || headB == null) return List.of();

        int n = orderedFromA.size();
        List<Float> pressures = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double frac = n == 1 ? 0 : (double) i / (n - 1);
            double head = headA + (headB - headA) * frac;
            pressures.add((float) (head - SableCompat.getWorldY(level, orderedFromA.get(i))));
        }
        return pressures;
    }

    private static <T> List<T> reverse(List<T> in) {
        List<T> out = new ArrayList<>(in.size());
        for (int i = in.size() - 1; i >= 0; i--) out.add(in.get(i));
        return out;
    }

    /** Localized name of the block at a node — the tank, basin, pump, cauldron, etc. */
    private static String blockName(ServerLevel level, Node n) {
        return level.getBlockState(n.pos()).getBlock().getName().getString();
    }

    /** A pump node's current rotation speed (RPM), 0 if it is not a kinetic block. */
    private static float pumpSpeed(ServerLevel level, Node n) {
        return level.getBlockEntity(n.pos()) instanceof KineticBlockEntity k ? k.getSpeed() : 0;
    }

    /**
     * Why a hose pulley node is (or is not) supplying the network, or null when the node is not a
     * pulley. A pulley only feeds the engine once its hose is wound down INTO a fluid body and the
     * drainer has searched it — so this reports the missing precondition rather than leaving the
     * player guessing why a plumbed pulley moves nothing (the "won't pull" report). When the pulley
     * IS a source the normal fluid line already shows it, so this returns null.
     */
    private static String pulleyDiagnostic(ServerLevel level, Node n) {
        if (!n.isHandler() || !(level.getBlockEntity(n.pos()) instanceof HosePulleyBlockEntity)) {
            return null;
        }
        IFluidHandler cap = BoundaryColumn.findHandler(level, n.pos());
        if (cap == null) return "pulley: no fluid capability";
        FluidStack drainable = cap.getFluidInTank(0);
        if (drainable.isEmpty()) {
            return "pulley: NOT supplying — no drainable fluid at the hose end "
                    + "(wind the hose DOWN into the fluid with rotation; a large/searching body needs a few ticks)";
        }
        if (cap.drain(drainable.copyWithAmount(1), FluidAction.SIMULATE).isEmpty()) {
            return "pulley: sees " + drainable.getHoverName().getString()
                    + " but can't draw yet (still lowering / settling)";
        }
        return null; // it IS a source — the fluid line above reports it
    }

    /**
     * What the LIVE handler behind a HANDLER node actually reports to the engine's probes, or null for
     * non-handlers: how much it holds ({@code getFluidInTank(0)}), how much it will GIVE
     * ({@code drain} SIMULATE), and how much of {@code probe} it will TAKE ({@code fill} SIMULATE). This
     * is exactly what the solver keys participation on, so a handler that shows {@code give=0 take=0} is
     * why a run past it moves nothing — the case for a paired device (a docking connector) whose
     * capability is gated on its own state, which the fill/fraction summary above cannot reveal.
     */
    private static String handlerProbe(ServerLevel level, Node n, FluidStack probe) {
        if (!n.isHandler()) return null;
        IFluidHandler cap = BoundaryColumn.findHandler(level, n.pos());
        if (cap == null) return "probe: no live fluid capability";
        int holds = cap.getTanks() > 0 ? cap.getFluidInTank(0).getAmount() : 0;
        int give = cap.drain(Integer.MAX_VALUE, FluidAction.SIMULATE).getAmount();
        int take = probe.isEmpty() ? 0 : cap.fill(probe.copyWithAmount(1000), FluidAction.SIMULATE);
        String line = String.format("probe: holds=%d give=%d take=%d%s", holds, give, take,
                probe.isEmpty() ? "" : " (" + probe.getHoverName().getString() + ")");
        // The null-side handler is what our engine actually uses. If a SPECIFIC face accepts/gives more
        // (as Create's face-specific transport would see), the block is sided and our null resolution is
        // the bug — surface the best face so we can point the engine at it.
        String bestGive = "", bestTake = "";
        int maxGive = give, maxTake = take;
        for (Direction side : Direction.values()) {
            IFluidHandler s = level.getCapability(Capabilities.FluidHandler.BLOCK, n.pos(), side);
            if (s == null) continue;
            int g = s.drain(Integer.MAX_VALUE, FluidAction.SIMULATE).getAmount();
            int t = probe.isEmpty() ? 0 : s.fill(probe.copyWithAmount(1000), FluidAction.SIMULATE);
            if (g > maxGive) { maxGive = g; bestGive = side.toString(); }
            if (t > maxTake) { maxTake = t; bestTake = side.toString(); }
        }
        if (!bestGive.isEmpty()) line += " | face give=" + maxGive + "@" + bestGive;
        if (!bestTake.isEmpty()) line += " | face take=" + maxTake + "@" + bestTake;
        return line;
    }

    /**
     * Reflective, mod-optional readout of an aeronautics docking connector's internal fluid state, or
     * null for any other block. It answers WHY the connector's {@code insert()} returns 0: whether it is
     * paired at all ({@code connectedPos}/{@code connectedTank}), whether its own {@code canInteract()}
     * gate passes right now, and whether the PAIRED connector's buffer (on the other ship) is full. Uses
     * reflection so the mod stays an optional dependency; field/method names match the decompiled
     * {@code DockingConnectorTank}.
     */
    private static String dockingDiagnostic(ServerLevel level, Node n) {
        Object be = level.getBlockEntity(n.pos());
        if (be == null || !be.getClass().getSimpleName().equals("DockingConnectorBlockEntity")) return null;
        try {
            Object tank = be.getClass().getField("tank").get(be);
            Object connectedPos = readField(tank, "connectedPos");
            Object connectedTank = readField(tank, "connectedTank");
            java.lang.reflect.Method canInteract = tank.getClass().getDeclaredMethod("canInteract");
            canInteract.setAccessible(true);
            Object interacts = canInteract.invoke(tank);
            String pair = "";
            if (connectedTank != null) {
                Object amt = connectedTank.getClass().getField("amount").get(connectedTank);
                Object cap = connectedTank.getClass().getField("capacity").get(connectedTank);
                pair = " pairBuffer=" + amt + "/" + cap;
            }
            return String.format("dock: connectedPos=%s connectedTank=%s canInteract=%s%s",
                    connectedPos, connectedTank != null, interacts, pair);
        } catch (Throwable t) {
            return "dock: reflect failed (" + t.getClass().getSimpleName() + ")";
        }
    }

    private static Object readField(Object owner, String name) throws Exception {
        java.lang.reflect.Field f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(owner);
    }

    /** The first non-empty fluid found on any column of the network, used to probe what sinks will take. */
    private static FluidStack firstPresentFluid(ServerLevel level, Graph g) {
        for (Node n : g.nodes()) {
            BoundaryColumn column = columnOf(level, n);
            if (column != null && !column.contents().isEmpty() && column.contentMb() > 0) {
                return column.contents();
            }
        }
        return FluidStack.EMPTY;
    }

    /**
     * The fluid column behind a source/sink node, or null for pumps, junctions, and
     * handlers that no longer expose a capability. Open ends report the fluid their
     * mouth would draw in (empty for a plain spill outlet).
     */
    private static BoundaryColumn columnOf(ServerLevel level, Node n) {
        if (n.isHandler()) return BoundaryColumn.resolve(level, n);
        if (n.isOpenEnd()) return BoundaryColumn.forOpenEnd(level, n, false);
        return null;
    }

    /** "4000/8000 mB Water (50%)" — a column's contents, capacity, and fill. */
    private static String fluidSummary(BoundaryColumn column) {
        return String.format("%d/%d mB §f%s §7(%.0f%%)",
                column.contentMb(), column.capacityMb(),
                column.contents().getHoverName().getString(),
                column.fillFraction() * 100);
    }

    /**
     * The floating in-world label for a node, kept to TWO lines to stay legible: the block on line
     * one, and a compact status on line two — its fluid (sources/sinks), RPM+facing (pumps), or
     * "valve shut" — with the stored head merged onto that same line ({@code h4.0}) rather than a
     * third line. Empty for junctions, which the overlay leaves unannotated. The head is the value
     * /pipegraph prints in chat, shown in-world so you can SEE where it sits. Lines are
     * {@code \n}-separated for the client.
     */
    private static String nodeLabel(ServerLevel level, Node n, Double head) {
        String info = switch (n.kind()) {
            case HANDLER -> {
                BoundaryColumn column = columnOf(level, n);
                yield column != null && !column.contents().isEmpty() && column.contentMb() > 0
                        ? String.format("%d mB %s", column.contentMb(), column.contents().getHoverName().getString())
                        : "empty";
            }
            case OPEN_END -> {
                BoundaryColumn column = columnOf(level, n);
                yield column != null && !column.contents().isEmpty()
                        ? "draws " + column.contents().getHoverName().getString()
                        : "open end";
            }
            case PUMP -> {
                float rpm = level.getBlockEntity(n.pos()) instanceof KineticBlockEntity k ? k.getSpeed() : 0;
                yield String.format("%.0f RPM%s", rpm, n.pumpFacing() != null ? " →" + n.pumpFacing() : "");
            }
            case CLOSED_GATE -> "valve shut";
            case JUNCTION -> null;
        };
        if (info == null) return ""; // junction: unannotated to avoid clutter
        // Merge the stored head onto the status line rather than a third line.
        if (head != null) info += String.format("  h%.1f", head);
        return blockName(level, n) + "\n" + info;
    }

    /** The stored head a HELD edge holds: the higher of its two endpoint display heads. */
    private static Double heldHead(Solution s, Edge e) {
        Double a = s.nodeHeads().get(e.a());
        Double b = s.nodeHeads().get(e.b());
        if (a == null) return b;
        if (b == null) return a;
        return Math.max(a, b);
    }

    /**
     * A per-fluid summary of everything held across the network's sources/sinks: total
     * volume plus the physical properties that drive the engine — density (gravity/
     * buoyancy sign), viscosity (flow rate), and temperature. Flags a lighter-than-air
     * fluid, which inverts the gravity model.
     */
    private static void sendFluidStats(ServerPlayer player, ServerLevel level, Graph g) {
        List<FluidStack> totals = new ArrayList<>();
        for (Node n : g.nodes()) {
            BoundaryColumn column = columnOf(level, n);
            if (column == null || column.contents().isEmpty() || column.contentMb() <= 0) continue;
            FluidStack running = null;
            for (FluidStack present : totals) {
                if (FluidStack.isSameFluidSameComponents(present, column.contents())) { running = present; break; }
            }
            if (running != null) running.grow(column.contentMb());
            else totals.add(column.contents().copyWithAmount(column.contentMb()));
        }
        if (totals.isEmpty()) return;

        send(player, "§e--- Fluids ---");
        for (FluidStack fluid : totals) {
            FluidType type = fluid.getFluid().getFluidType();
            send(player, String.format("  §b%s§7: §f%d mB  §7density §f%d §7visc §f%d §7temp §f%dK%s",
                    fluid.getHoverName().getString(), fluid.getAmount(),
                    type.getDensity(), type.getViscosity(), type.getTemperature(),
                    type.isLighterThanAir() ? "  §e(lighter than air ↑)" : ""));
        }
    }

    private static void send(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(message));
    }
}
