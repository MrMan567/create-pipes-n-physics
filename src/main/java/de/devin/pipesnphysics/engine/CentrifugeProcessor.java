package de.devin.pipesnphysics.engine;

import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.api.CentrifugeApi;
import de.devin.pipesnphysics.api.CentrifugeRecipe;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reverse-mix processor. A tank on a spinning contraption holding a fluid a centrifuging recipe
 * accepts is drained and its component fluids are pushed into the tanks on the network, ranked by
 * DENSITY against radius: the denser a product, the further OUT (faster-moving tank) it goes; the
 * lighter, the further IN — exactly a centrifuge stratifying a mixture. Radius needs no geometry:
 * for rigid rotation {@code v = ω·r}, so a tank's orbital speed IS its radius. The reactor is itself
 * a candidate destination, so the densest product can stay in an outermost reactor.
 *
 * <p>Runs per network per tick after the hydraulic solve, on the server thread. Placement is simulated
 * in memory first (single-fluid tanks claim one output each) and only as many whole operations as fully
 * fit are run, so nothing is lost and the input backs up when the outlets fill.
 */
public final class CentrifugeProcessor {
    private CentrifugeProcessor() {}

    public static void process(ServerLevel level, Graph graph, long gameTime) {
        if (!PipesNPhysicsConfig.ENABLE_CENTRIFUGE.get()
                || !PipesNPhysicsConfig.ENABLE_CENTRIFUGE_UNMIX.get()
                || !CentrifugeApi.hasAny()) return;

        // Gate on the whole contraption spinning (the fastest-moving tank), not the reactor's own speed —
        // so a tank at the axis un-mixes too and its products fling out to the off-axis outlets.
        double networkSpeed = 0;
        for (Node node : graph.handlers()) {
            networkSpeed = Math.max(networkSpeed, CentrifugeField.orbitalSpeed(level, node.pos(), gameTime));
        }
        if (networkSpeed < PipesNPhysicsConfig.CENTRIFUGE_UNMIX_MIN_SPEED.get()) return;

        for (Node reactor : graph.handlers()) {
            IFluidHandler cap = BoundaryColumn.findHandler(level, reactor.pos(), reactor.accessFace());
            if (cap == null) continue;
            FluidStack held = primaryDrainable(cap);
            if (held.isEmpty()) continue;
            CentrifugeRecipe recipe = CentrifugeApi.find(held);
            if (recipe != null) unmix(level, graph, reactor, cap, recipe, gameTime);
        }
    }

    private static void unmix(ServerLevel level, Graph graph, Node reactor, IFluidHandler reactorCap,
                              CentrifugeRecipe recipe, long gameTime) {
        int rawInput = recipe.input().getAmount();
        if (rawInput <= 0) return;

        // Reduce the recipe to its smallest integer ratio so a large-mB recipe still runs within the
        // per-tick rate (ops = rate / inputPerOp) instead of rounding to zero. The ratio — and thus
        // conservation across operations — is preserved exactly.
        int divisor = rawInput;
        for (FluidStack output : recipe.outputs()) divisor = gcd(divisor, output.getAmount());
        if (divisor <= 0) divisor = 1;
        int inputPerOp = rawInput / divisor;

        int rate = PipesNPhysicsConfig.CENTRIFUGE_UNMIX_RATE.get();
        FluidStack drainable = BoundaryColumn.drainMatching(reactorCap,
                recipe.input().copyWithAmount(rate), FluidAction.SIMULATE);
        int opsByInput = Math.min(rate, drainable.getAmount()) / inputPerOp;
        if (opsByInput <= 0) return;

        // Candidate tanks (reactor included, so the densest product can stay in an outermost reactor),
        // ranked by orbital speed = radius, OUTERMOST first.
        List<Sink> sinks = new ArrayList<>();
        for (Node node : graph.handlers()) {
            IFluidHandler cap = node.pos().equals(reactor.pos()) ? reactorCap
                    : BoundaryColumn.findHandler(level, node.pos(), node.accessFace());
            if (cap != null) sinks.add(new Sink(cap, CentrifugeField.orbitalSpeed(level, node.pos(), gameTime)));
        }
        sinks.sort(Comparator.comparingDouble((Sink s) -> s.speed).reversed());

        // Outputs at their per-operation (reduced) amount, DENSEST first; the sort is stable, so equal
        // densities keep the recipe's declared order.
        List<FluidStack> outputs = new ArrayList<>();
        for (FluidStack output : recipe.outputs()) outputs.add(output.copyWithAmount(output.getAmount() / divisor));
        outputs.sort(Comparator.comparingInt(CentrifugeProcessor::density).reversed());

        int ops = maxFittingOps(sinks, outputs, opsByInput);
        if (ops <= 0) return;

        FluidStack drained = BoundaryColumn.drainMatching(reactorCap,
                recipe.input().copyWithAmount(ops * inputPerOp), FluidAction.EXECUTE);
        int done = drained.getAmount() / inputPerOp;
        if (done <= 0) return;
        for (FluidStack output : outputs) {
            depositRanked(sinks, output.copyWithAmount(done * output.getAmount()));
        }
    }

    /** Largest operation count whose every output fully places into the ranked tanks (loss-free). */
    private static int maxFittingOps(List<Sink> sinks, List<FluidStack> outputs, int maxOps) {
        FluidStack[] held = new FluidStack[sinks.size()];
        int[] free = new int[sinks.size()];
        for (int i = 0; i < sinks.size(); i++) {
            held[i] = heldFluid(sinks.get(i).handler);
            free[i] = freeSpace(sinks.get(i).handler);
        }
        // Monotonic in ops (more product needs more room), so the largest that fits is first-fit downward.
        for (int ops = maxOps; ops >= 1; ops--) {
            if (fits(held.clone(), free.clone(), outputs, ops)) return ops;
        }
        return 0;
    }

    /** Simulate placing every output (densest first) into the tanks (outermost first) with claiming. */
    private static boolean fits(FluidStack[] held, int[] free, List<FluidStack> outputs, int ops) {
        for (FluidStack output : outputs) {
            int need = ops * output.getAmount();
            for (int i = 0; i < held.length && need > 0; i++) {
                boolean accepts = free[i] > 0
                        && (held[i].isEmpty() || FluidStack.isSameFluidSameComponents(held[i], output));
                if (!accepts) continue;
                int take = Math.min(need, free[i]);
                if (held[i].isEmpty()) held[i] = output; // claim this empty tank for this fluid
                free[i] -= take;
                need -= take;
            }
            if (need > 0) return false;
        }
        return true;
    }

    /** Spread a produced fluid across the ranked tanks; wrong-fluid or full tanks simply take none. */
    private static void depositRanked(List<Sink> sinks, FluidStack stack) {
        FluidStack remaining = stack.copy();
        for (Sink sink : sinks) {
            if (remaining.isEmpty()) return;
            int filled = sink.handler.fill(remaining, FluidAction.EXECUTE);
            if (filled > 0) remaining.shrink(filled);
        }
    }

    private static FluidStack primaryDrainable(IFluidHandler cap) {
        for (int i = 0; i < cap.getTanks(); i++) {
            FluidStack fluid = cap.getFluidInTank(i);
            if (!fluid.isEmpty() && !cap.drain(fluid.copyWithAmount(1), FluidAction.SIMULATE).isEmpty()) {
                return fluid.copy();
            }
        }
        return FluidStack.EMPTY;
    }

    private static FluidStack heldFluid(IFluidHandler handler) {
        for (int i = 0; i < handler.getTanks(); i++) {
            FluidStack fluid = handler.getFluidInTank(i);
            if (!fluid.isEmpty()) return fluid;
        }
        return FluidStack.EMPTY;
    }

    private static int freeSpace(IFluidHandler handler) {
        int free = 0;
        for (int i = 0; i < handler.getTanks(); i++) {
            free += handler.getTankCapacity(i) - handler.getFluidInTank(i).getAmount();
        }
        return free;
    }

    private static int density(FluidStack stack) {
        return stack.getFluidType().getDensity(stack);
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = b;
            b = a % b;
            a = t;
        }
        return Math.abs(a);
    }

    /** A candidate destination tank paired with its orbital speed (the radius proxy for ranking). */
    private record Sink(IFluidHandler handler, double speed) {}
}
