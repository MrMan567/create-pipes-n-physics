package de.devin.pipesnphysics.gametest.blocks;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.PipeConnection;
import com.simibubi.create.content.fluids.pipes.valve.FluidValveBlock;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.api.FluidHandlerApi;
import de.devin.pipesnphysics.api.FluidHandlerRole;
import de.devin.pipesnphysics.client.PipeStatusText;
import de.devin.pipesnphysics.display.PipeDisplayMetric;
import de.devin.pipesnphysics.engine.EdgeFlow;
import de.devin.pipesnphysics.engine.EngineTickHandler;
import de.devin.pipesnphysics.engine.FlowSolver;
import de.devin.pipesnphysics.engine.FluidEngine;
import de.devin.pipesnphysics.engine.PipeFlowExecutor;
import de.devin.pipesnphysics.engine.Solution;
import de.devin.pipesnphysics.engine.boundary.BoundaryColumn;
import de.devin.pipesnphysics.engine.boundary.FluidCaps;
import de.devin.pipesnphysics.engine.boundary.HandlerRoles;
import de.devin.pipesnphysics.engine.boundary.OpenEndPipes;
import de.devin.pipesnphysics.engine.boundary.RelayDetector;
import de.devin.pipesnphysics.engine.flow.FlowNetwork;
import de.devin.pipesnphysics.engine.graph.Edge;
import de.devin.pipesnphysics.engine.graph.Graph;
import de.devin.pipesnphysics.engine.graph.GraphBuilder;
import de.devin.pipesnphysics.engine.graph.Node;
import de.devin.pipesnphysics.engine.net.PipeStatusPayload;
import de.devin.pipesnphysics.engine.probe.PipeProbe;
import de.devin.pipesnphysics.engine.store.PipeStore;
import de.devin.pipesnphysics.engine.valve.ValveCharacteristic;
import de.devin.pipesnphysics.engine.valve.ValveDirectionBehaviour;
import de.devin.pipesnphysics.engine.valve.ValveThrottle;
import de.devin.pipesnphysics.handler.NetworkEditHandler;
import de.devin.pipesnphysics.mixin.FluidTankAccessor;
import de.devin.pipesnphysics.mixin.FluidValveAccessor;
import de.devin.pipesnphysics.mixin.PipeConnectionAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.server.level.ServerLevel;
import net.createmod.catnip.lang.LangNumberFormat;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import de.devin.pipesnphysics.PipesNPhysics;
import de.devin.pipesnphysics.TestSideHandlers;
import static de.devin.pipesnphysics.gametest.GameTestSupport.*;

/**
 * Fluid-valve 0-90 throttle and held-column (closed-valve) behavior.
 */
@GameTestHolder(PipesNPhysics.ID)
@PrefixGameTestTemplate(false)
public class ValveTests {

    /**
     * The fine-grained valve throttle (a 0-90 degree scroll value) must scale a run's solved
     * flow: fully open at 90 degrees passes the full hydraulic flow, halving the angle roughly
     * halves it, and 0 degrees shuts the run (blocked, {@code Reason.VALVE}) exactly as the shaft
     * would. A valve is inserted into the bottom of a communicating-vessels U — no pump, so
     * conductance (not a pump cap) sets the rate — and the solved edge flow is read at each angle.
     * The shaft state is forced open and every solve happens in the SAME tick, before the
     * unpowered valve would chase {@code ENABLED} back to closed.
     */
    @GameTest(template = "common/simple_fluid_leveling", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
    public static void valveThrottleScalesFlow(GameTestHelper helper) {
        helper.runAfterDelay(2, () -> withLinearValveCurve(() -> {
            Level level = helper.getLevel();

            // simple_fluid_leveling: the U-bottom straight-X pipe cell (west+east pipe, nothing else)
            // hosts the valve; any bottom-row cell seeds the graph. Pinned from NBT.
            BlockPos valveRel = new BlockPos(1, 1, 0);
            BlockPos seedRel = new BlockPos(0, 1, 0);

            // Orient the valve so its pipe axis is X (matching the run) and force the shaft open.
            BlockState valve = AllBlocks.FLUID_VALVE.get().defaultBlockState()
                    .setValue(FluidValveBlock.FACING, Direction.UP);
            if (FluidValveBlock.getPipeAxis(valve) != Direction.Axis.X) {
                valve = valve.setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE,
                        !valve.getValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE));
            }
            helper.setBlock(valveRel, valve.setValue(FluidValveBlock.ENABLED, true));

            fill(helper, new BlockPos(0, 3, 0), 8000); // a head gradient across the valve

            BlockPos valveAbs = helper.absolutePos(valveRel);
            Graph g = GraphBuilder.build(level, helper.absolutePos(seedRel));
            Edge edge = null;
            for (Edge e : g.edges()) {
                if (e.pipes().contains(valveAbs)) { edge = e; break; }
            }
            if (edge == null) { helper.fail("valve cell landed on no edge"); return; }

            int full = valveFlow(level, g, edge, valveAbs, 90);
            int half = valveFlow(level, g, edge, valveAbs, 45);
            int fifth = valveFlow(level, g, edge, valveAbs, 18);
            if (full <= 0) { helper.fail("a fully open valve passed no flow (" + full + ")"); return; }
            if (!(full > half && half > fifth && fifth > 0)) {
                helper.fail("throttle did not scale flow monotonically: 90=" + full
                        + " 45=" + half + " 18=" + fifth);
                return;
            }
            // The two tanks contract to a 2-node system with capacitance >> conductance, so the
            // solved flow is near-linear in the angle — assert proportionality, not just monotonicity,
            // to catch a non-linear (sqrt/square/clamped) angle->opening mapping.
            if (half < 0.38 * full || half > 0.62 * full) {
                helper.fail("45 degrees should pass ~half: 90=" + full + " 45=" + half);
                return;
            }
            if (fifth < 0.10 * full || fifth > 0.32 * full) {
                helper.fail("18 degrees should pass ~a fifth: 90=" + full + " 18=" + fifth);
                return;
            }

            setThrottle(level, valveAbs, 0);
            Solution shut = FlowSolver.solve(level, g);
            if (!shut.blockedEdges().contains(edge.index())
                    || shut.edgeReasons().get(edge.index()) != Solution.Reason.VALVE) {
                helper.fail("a 0 degree valve did not shut its run with Reason.VALVE");
                return;
            }
            helper.succeed();
        }));
    }

    /**
     * Regression for "the throttle does nothing on a pumped line": the angle must scale the FINAL
     * conductance, AFTER the pump's internal-conductance cap — otherwise the tiny pump cap masks it
     * and flow stays constant until the valve is nearly shut. Inserts a valve on the running pump's
     * push side and asserts the solved flow drops materially from 90° to 45° to 18°. (Before the fix
     * the three solves tied, because {@code min(edgeG·throttle, pumpInternalG)} pinned at the cap.)
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
    public static void valveThrottleScalesPumpedFlow(GameTestHelper helper) {
        helper.runAfterDelay(10, () -> { // let the kinetics spin the pump up and settle its FACING
            Level level = helper.getLevel();
            BlockPos pumpRel = new BlockPos(2, 1, 1); // piping/single_pump: pump pinned from NBT; FACING is re-read below (Create re-orients it at runtime)
            Direction push = helper.getBlockState(pumpRel).getValue(PumpBlock.FACING);
            BlockPos valveRel = pumpRel.relative(push);
            if (!pipeAt(helper, valveRel)) { helper.fail("pump push side is not a pipe cell"); return; }

            BlockState valve = AllBlocks.FLUID_VALVE.get().defaultBlockState()
                    .setValue(FluidValveBlock.FACING, Direction.UP);
            if (FluidValveBlock.getPipeAxis(valve) != push.getAxis()) {
                valve = valve.setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE,
                        !valve.getValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE));
            }
            helper.setBlock(valveRel, valve.setValue(FluidValveBlock.ENABLED, true));

            fill(helper, new BlockPos(0, 1, 1), 8000); // source full
            drain(helper, new BlockPos(4, 1, 1));      // sink empty -> the pump wants to move fluid

            BlockPos valveAbs = helper.absolutePos(valveRel);
            Graph g = GraphBuilder.build(level, valveAbs);
            Edge edge = null;
            for (Edge e : g.edges()) {
                if (e.pipes().contains(valveAbs)) { edge = e; break; }
            }
            if (edge == null) { helper.fail("valve cell landed on no edge"); return; }

            int full = valveFlow(level, g, edge, valveAbs, 90);
            int half = valveFlow(level, g, edge, valveAbs, 45);
            int fifth = valveFlow(level, g, edge, valveAbs, 18);
            if (full <= 0) { helper.fail("the pump moved no fluid through a fully open valve (" + full + ")"); return; }
            // The throttle must bite on the pumped run, not stay pinned at the pump cap.
            if (!(half < 0.8 * full && fifth < 0.8 * half && fifth > 0)) {
                helper.fail("throttle did not scale the PUMPED flow: 90=" + full
                        + " 45=" + half + " 18=" + fifth + " (it should drop materially each step)");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * The throttle is a THROUGHPUT GOVERNOR: "let through 50%" halves the flow wherever the valve
     * sits — not just when the valve's own run is the binding resistor. Here the valve is on the
     * pump's PULL side, so it is in series with the pump's internal-conductance cap (which dominates
     * the loop). Under the old "valve = pipe resistance" model this barely bit (a real pump read
     * 74→67 mB/t for a 50% valve — the user's bug); the governor must instead drive it to ~half.
     * Asserts true proportionality (45° ≈ 0.5× full, 18° ≈ 0.2×), which the resistor model fails.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void valveGovernsPumpedFlowFromThePullSide(GameTestHelper helper) {
        // let the kinetics spin the pump up and settle its FACING
        helper.runAfterDelay(10, () -> withLinearValveCurve(() -> {
            Level level = helper.getLevel();
            BlockPos pumpRel = new BlockPos(2, 1, 1); // piping/single_pump: pump pinned from NBT; FACING is re-read below (Create re-orients it at runtime)
            Direction push = helper.getBlockState(pumpRel).getValue(PumpBlock.FACING);
            BlockPos valveRel = pumpRel.relative(push.getOpposite()); // PULL side: the pump's intake run
            if (!pipeAt(helper, valveRel)) { helper.fail("pump pull side is not a pipe cell"); return; }

            BlockState valve = AllBlocks.FLUID_VALVE.get().defaultBlockState()
                    .setValue(FluidValveBlock.FACING, Direction.UP);
            if (FluidValveBlock.getPipeAxis(valve) != push.getAxis()) {
                valve = valve.setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE,
                        !valve.getValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE));
            }
            helper.setBlock(valveRel, valve.setValue(FluidValveBlock.ENABLED, true));

            fill(helper, new BlockPos(0, 1, 1), 8000); // source full
            drain(helper, new BlockPos(4, 1, 1));      // sink empty -> the pump wants to move fluid

            BlockPos valveAbs = helper.absolutePos(valveRel);
            Graph g = GraphBuilder.build(level, valveAbs);
            Edge edge = null;
            for (Edge e : g.edges()) {
                if (e.pipes().contains(valveAbs)) { edge = e; break; }
            }
            if (edge == null) { helper.fail("valve cell landed on no edge"); return; }

            int full = Math.abs(valveFlow(level, g, edge, valveAbs, 90));
            int half = Math.abs(valveFlow(level, g, edge, valveAbs, 45));
            int fifth = Math.abs(valveFlow(level, g, edge, valveAbs, 18));
            if (full <= 0) { helper.fail("the pump moved no fluid through a fully open valve (" + full + ")"); return; }
            // The governor makes the throttle a share of the fully-open flow, regardless of the pump
            // being the series bottleneck. Bands mirror valveThrottleScalesFlow; the resistor model
            // (which read ~0.9x here) fails the upper bound outright.
            if (!(half >= 0.38 * full && half <= 0.62 * full)) {
                helper.fail("50% valve on the pull side did not halve the pumped flow (governor): 90="
                        + full + " 45=" + half + " (want ~0.5x — resistor model read ~0.9x)");
                return;
            }
            if (!(fifth >= 0.08 * full && fifth <= 0.34 * full)) {
                helper.fail("20% valve did not throttle to ~a fifth: 90=" + full + " 18=" + fifth);
                return;
            }
            helper.succeed();
        }));
    }

    /**
     * The held-head foundation: a fully-shut valve mid-run becomes a CLOSED_GATE node that the
     * solver treats as a WALL — the run SPLITS there into two edges. A pump feeding the gate
     * HOLDS its pressurized column up to it (the feed edge is flagged held; the head doesn't
     * reset), NO flow crosses, and the far side is free to settle. Generalizes "the head doesn't
     * reset when blocked" to a mid-run valve (the worked example). Build the graph AFTER shutting,
     * since the split is a topology decision made at graph-build time (as it is in-game per tick).
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
    public static void closedValveSplitsRunAndHoldsFeed(GameTestHelper helper) {
        helper.runAfterDelay(10, () -> { // let the kinetics spin the pump up and settle its FACING
            var level = helper.getLevel();
            BlockPos pumpRel = new BlockPos(2, 1, 1); // piping/single_pump: pump pinned from NBT; FACING is re-read below (Create re-orients it at runtime)
            Direction push = helper.getBlockState(pumpRel).getValue(PumpBlock.FACING);
            BlockPos valveRel = pumpRel.relative(push);
            if (!pipeAt(helper, valveRel)) { helper.fail("pump push side is not a pipe cell"); return; }

            BlockState valve = AllBlocks.FLUID_VALVE.get().defaultBlockState()
                    .setValue(FluidValveBlock.FACING, Direction.UP);
            if (FluidValveBlock.getPipeAxis(valve) != push.getAxis()) {
                valve = valve.setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE,
                        !valve.getValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE));
            }
            helper.setBlock(valveRel, valve.setValue(FluidValveBlock.ENABLED, true));
            fill(helper, new BlockPos(0, 1, 1), 8000); // source full
            drain(helper, new BlockPos(4, 1, 1));      // sink empty -> the pump wants to deliver

            BlockPos valveAbs = helper.absolutePos(valveRel);
            setThrottle(level, valveAbs, 0);                  // SHUT, then build so the gate appears
            Graph g = GraphBuilder.build(level, valveAbs);

            var gate = g.nodeAt(valveAbs);
            if (gate == null || !gate.isClosedGate()) {
                helper.fail("shut valve did not become a CLOSED_GATE node: "
                        + (gate == null ? "null" : gate.kind()));
                return;
            }
            List<Edge> incident = g.edgesOf(gate.index());
            if (incident.size() != 2) {
                helper.fail("closed gate did not split the run into 2 edges: " + incident.size());
                return;
            }

            Solution sol = FlowSolver.solve(level, g);
            Edge feed = null;
            for (Edge e : incident) {
                if (g.node(e.a()).isPump() || g.node(e.b()).isPump()) feed = e;
                if (sol.edgeFlows().get(e.index()).mbPerTick() != 0) {
                    helper.fail("flow crossed a shut gate on edge " + e.index());
                    return;
                }
            }
            if (feed == null) { helper.fail("no pump-fed edge at the gate"); return; }
            if (!sol.heldEdges().contains(feed.index())) {
                helper.fail("the pump-fed run dead-heading a shut valve was not flagged held");
                return;
            }
            if (!sol.transfers().isEmpty()) {
                helper.fail("a transfer crossed a shut valve: " + sol.transfers().size());
                return;
            }
            helper.succeed();
        });
    }

    /**
     * A pump dead-heading a shut valve with NO SUPPLY must NOT be flagged held — it develops a
     * head but holds NO water, so rendering a column would be phantom fluid (the symptom of
     * placing a running pump where an open end used to be). Built by draining the pump's suction
     * tank while leaving water on the FAR side of the valve, so the pass still runs but the pump's
     * island has no source.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
    public static void unsuppliedPumpDeadheadingValveNotHeld(GameTestHelper helper) {
        helper.runAfterDelay(10, () -> {
            var level = helper.getLevel();
            BlockPos pumpRel = new BlockPos(2, 1, 1); // piping/single_pump: pump pinned from NBT (FACING re-read below)
            List<BlockPos> tanks = new ArrayList<>(List.of(new BlockPos(0, 1, 1), new BlockPos(4, 1, 1)));
            Direction push = helper.getBlockState(pumpRel).getValue(PumpBlock.FACING);
            BlockPos valveRel = pumpRel.relative(push); // valve on the push side, between pump and the far tank
            if (!pipeAt(helper, valveRel)) { helper.fail("pump push side is not a pipe cell"); return; }

            BlockState valve = AllBlocks.FLUID_VALVE.get().defaultBlockState()
                    .setValue(FluidValveBlock.FACING, Direction.UP);
            if (FluidValveBlock.getPipeAxis(valve) != push.getAxis()) {
                valve = valve.setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE,
                        !valve.getValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE));
            }
            helper.setBlock(valveRel, valve.setValue(FluidValveBlock.ENABLED, true));

            tanks.sort(Comparator.comparingInt(BlockPos::getX));
            BlockPos suction = push == Direction.WEST ? tanks.get(1) : tanks.get(0);
            BlockPos far = push == Direction.WEST ? tanks.get(0) : tanks.get(1);
            drain(helper, suction);                       // the pump has NOTHING to pull
            fillFluid(helper, far, Fluids.WATER, 8000);    // water exists, but on the FAR side of the valve

            BlockPos valveAbs = helper.absolutePos(valveRel);
            setThrottle(level, valveAbs, 0);
            Graph g = GraphBuilder.build(level, valveAbs);
            var gate = g.nodeAt(valveAbs);
            if (gate == null || !gate.isClosedGate()) { helper.fail("valve is not a CLOSED_GATE"); return; }
            Edge feed = null;
            for (Edge e : g.edgesOf(gate.index())) {
                if (g.node(e.a()).isPump() || g.node(e.b()).isPump()) feed = e;
            }
            if (feed == null) { helper.fail("no pump-fed edge at the gate"); return; }
            Solution sol = FlowSolver.solve(level, g);
            if (sol.heldEdges().contains(feed.index())) {
                helper.fail("a pump with no supply dead-heading a shut valve was flagged held "
                        + "(would render phantom water)");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * The held column is legible and RESUMES: a pump pushes down a run with a valve a couple cells
     * past it; shutting the valve must report the FEED cell as "holding pressure" (DETAIL_HELD,
     * fluid present — not "dry" nor idly "settled"), and reopening must let flow resume across the
     * rejoined run. Exercises the goggle wording and the close→open round trip end to end.
     */
    @GameTest(template = "common/long_pipe", templateNamespace = PipesNPhysics.ID, timeoutTicks = 300)
    public static void heldValveReportsHeldAndResumes(GameTestHelper helper) {
        helper.runAfterDelay(10, () -> { // let the kinetics spin the pump up and settle its FACING
            var level = helper.getLevel();
            BlockPos pump = new BlockPos(6, 1, 0); // piping/long_pipe: pump pinned from NBT (FACING re-read below)
            List<BlockPos> tanks = new ArrayList<>(List.of(new BlockPos(0, 1, 0), new BlockPos(7, 1, 0)));
            Direction push = helper.getBlockState(pump).getValue(PumpBlock.FACING);
            // Walk the push-side run for two consecutive pipe cells: the first is the FEED cell
            // (between pump and valve), the second hosts the valve. Falls out if the run is shorter.
            BlockPos feedCell = pump.relative(push);
            BlockPos valveRel = pump.relative(push, 2);
            if (!pipeAt(helper, feedCell) || !pipeAt(helper, valveRel)) {
                helper.fail("need two consecutive pipes off the pump push side (feed cell + valve), got feed="
                        + pipeAt(helper, feedCell) + " valve=" + pipeAt(helper, valveRel));
                return;
            }
            BlockState valve = AllBlocks.FLUID_VALVE.get().defaultBlockState()
                    .setValue(FluidValveBlock.FACING, Direction.UP);
            if (FluidValveBlock.getPipeAxis(valve) != push.getAxis()) {
                valve = valve.setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE,
                        !valve.getValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE));
            }
            helper.setBlock(valveRel, valve.setValue(FluidValveBlock.ENABLED, true));

            tanks.sort(Comparator.comparingInt(BlockPos::getX));
            BlockPos suction = push == Direction.WEST ? tanks.get(1) : tanks.get(0);
            BlockPos discharge = push == Direction.WEST ? tanks.get(0) : tanks.get(1);
            drain(helper, suction);
            fillFluid(helper, suction, Fluids.WATER, 8000);
            drain(helper, discharge);
            fillFluid(helper, discharge, Fluids.WATER, 4000); // partial: downstream settles full, with room to resume

            BlockPos valveAbs = helper.absolutePos(valveRel);
            BlockPos feedAbs = helper.absolutePos(feedCell);
            BlockPos downstreamCell = pump.relative(push, 3); // a cell on the far side of the valve
            if (!pipeAt(helper, downstreamCell)) { helper.fail("no downstream pipe cell past the valve"); return; }
            BlockPos downstreamAbs = helper.absolutePos(downstreamCell);

            setThrottle(level, valveAbs, 0); // SHUT
            // Give the pump time to PACK the held feed with real fluid (prime) and the settle
            // pass time to fill the downstream section from the half-full discharge tank.
            helper.runAfterDelay(80, () -> {
            PipeStatusPayload held = PipeProbe.probe(level, feedAbs);
            if (held.statusDetail() != PipeStatusPayload.DETAIL_HELD) {
                helper.fail("feed cell before a shut valve not reported HELD: detail="
                        + held.statusDetail() + " status=" + held.status());
                return;
            }
            if (held.fluid().isEmpty()) {
                helper.fail("a held feed cell reports no fluid (goggle would call it dry)");
                return;
            }
            // The settled section PAST the valve must report its fluid, not read dry (the gate
            // endpoint has no head of its own — PipeProbe must substitute it like the renderer does).
            PipeStatusPayload downstream = PipeProbe.probe(level, downstreamAbs);
            if (downstream.fluid().isEmpty()) {
                helper.fail("a settled cell downstream of a shut valve reads dry — goggle disagrees "
                        + "with the renderer (gate-head substitution missing)");
                return;
            }

            setThrottle(level, valveAbs, 90); // REOPEN
            // Create's valve flips its ENABLED blockstate on its own tick once the angle opens
            // (the mixin gates it on angle > 0) — give it a few ticks before checking the rejoin.
            helper.runAfterDelay(10, () -> {
            Graph g = GraphBuilder.build(level, feedAbs);
            // The run must actually REJOIN — the valve is a pipe cell again, not a CLOSED_GATE wall.
            var reopened = g.nodeAt(valveAbs);
            if (reopened != null && reopened.isClosedGate()) {
                helper.fail("valve still a CLOSED_GATE after reopening — the run did not rejoin");
                return;
            }
            Solution sol = FlowSolver.solve(level, g);
            boolean resumed = sol.edgeFlows().stream().anyMatch(f -> f.mbPerTick() > 0);
            if (!resumed) {
                helper.fail("flow did not resume after the valve reopened" + dump(helper, feedCell));
                return;
            }
            helper.succeed();
            });
            });
        });
    }

    /**
     * The DOWNSTREAM of a shut valve must stay DRY when it leads to an open end (air), not paint
     * phantom "settled" water: there is no reservoir on that side, so nothing fills it. (The bug:
     * the gate-head substitution took the OPEN END's head — its mouth, a spill threshold, not a
     * water surface — which read as a full waterline. Fixed by substituting a gate head only from a
     * real reservoir.) Builds tank → pump → valve → open-end by turning the discharge tank to air.
     */
    @GameTest(template = "common/long_pipe", templateNamespace = PipesNPhysics.ID, timeoutTicks = 300)
    public static void shutValveToOpenEndLeavesDownstreamDry(GameTestHelper helper) {
        helper.runAfterDelay(10, () -> {
            var level = helper.getLevel();
            BlockPos pump = new BlockPos(6, 1, 0); // piping/long_pipe: pump pinned from NBT (FACING re-read below)
            List<BlockPos> tanks = new ArrayList<>(List.of(new BlockPos(0, 1, 0), new BlockPos(7, 1, 0)));
            Direction push = helper.getBlockState(pump).getValue(PumpBlock.FACING);
            BlockPos valveRel = pump.relative(push, 3);
            BlockPos downstreamCell = pump.relative(push, 4); // between the valve and the open end
            if (!pipeAt(helper, valveRel) || !pipeAt(helper, downstreamCell)) {
                helper.fail("template lacks a long enough push-side run for valve+downstream");
                return;
            }
            tanks.sort(Comparator.comparingInt(BlockPos::getX));
            BlockPos suction = push == Direction.WEST ? tanks.get(1) : tanks.get(0);
            BlockPos discharge = push == Direction.WEST ? tanks.get(0) : tanks.get(1);
            helper.setBlock(discharge, Blocks.AIR.defaultBlockState()); // run now opens into AIR

            BlockState valve = AllBlocks.FLUID_VALVE.get().defaultBlockState()
                    .setValue(FluidValveBlock.FACING, Direction.UP);
            if (FluidValveBlock.getPipeAxis(valve) != push.getAxis()) {
                valve = valve.setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE,
                        !valve.getValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE));
            }
            helper.setBlock(valveRel, valve.setValue(FluidValveBlock.ENABLED, true));
            fillFluid(helper, suction, Fluids.WATER, 8000);

            setThrottle(level, helper.absolutePos(valveRel), 0); // SHUT
            PipeStatusPayload downstream = PipeProbe.probe(level, helper.absolutePos(downstreamCell));
            if (!downstream.fluid().isEmpty()) {
                helper.fail("downstream of a shut valve facing an open end reports fluid — should be "
                        + "dry (no reservoir on that side): " + downstream.fluid().getAmount());
                return;
            }
            helper.succeed();
        });
    }

    /**
     * The same invariant for an EMPTY TANK downstream of a shut valve (not an open end): the tank
     * IS a reservoir but holds no water, so its side has no SOURCE and must render dry. (An empty
     * tank's head sits at its base, half a block above the connecting pipe's bottom, so the cell
     * looked submerged — the bug is fixed by the island-has-a-source gate on restFluids, the single
     * invariant behind all the "shut valve shows water on the far side" reports.)
     */
    @GameTest(template = "common/long_pipe", templateNamespace = PipesNPhysics.ID, timeoutTicks = 300)
    public static void shutValveToEmptyTankLeavesDownstreamDry(GameTestHelper helper) {
        helper.runAfterDelay(10, () -> {
            var level = helper.getLevel();
            BlockPos pump = new BlockPos(6, 1, 0); // piping/long_pipe: pump pinned from NBT (FACING re-read below)
            List<BlockPos> tanks = new ArrayList<>(List.of(new BlockPos(0, 1, 0), new BlockPos(7, 1, 0)));
            Direction push = helper.getBlockState(pump).getValue(PumpBlock.FACING);
            BlockPos valveRel = pump.relative(push, 3);
            BlockPos downstreamCell = pump.relative(push, 4); // between the valve and the empty tank
            if (!pipeAt(helper, valveRel) || !pipeAt(helper, downstreamCell)) {
                helper.fail("template lacks a long enough push-side run for valve+downstream");
                return;
            }
            tanks.sort(Comparator.comparingInt(BlockPos::getX));
            BlockPos suction = push == Direction.WEST ? tanks.get(1) : tanks.get(0);
            BlockPos discharge = push == Direction.WEST ? tanks.get(0) : tanks.get(1);

            BlockState valve = AllBlocks.FLUID_VALVE.get().defaultBlockState()
                    .setValue(FluidValveBlock.FACING, Direction.UP);
            if (FluidValveBlock.getPipeAxis(valve) != push.getAxis()) {
                valve = valve.setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE,
                        !valve.getValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE));
            }
            helper.setBlock(valveRel, valve.setValue(FluidValveBlock.ENABLED, true));
            drain(helper, discharge);                      // an EMPTY tank downstream — no water there
            fillFluid(helper, suction, Fluids.WATER, 8000); // all the water is on the FEED side

            setThrottle(level, helper.absolutePos(valveRel), 0); // SHUT
            PipeStatusPayload downstream = PipeProbe.probe(level, helper.absolutePos(downstreamCell));
            if (!downstream.fluid().isEmpty()) {
                helper.fail("downstream of a shut valve facing an EMPTY tank reports fluid — should "
                        + "be dry (the tank holds no water): " + downstream.fluid().getAmount());
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Regression for the world-migration shut-valve bug: a valve saved BEFORE this feature has
     * no "ScrollValue" tag, and Create's {@code ScrollValueBehaviour.read} reads an absent key as
     * 0 — which would load every existing valve fully shut. The mixin re-asserts the open default
     * on a keyless read; verify a valve reloaded WITHOUT the tag comes up at 90° (fully open), not 0.
     */
    @GameTest(template = "common/simple_fluid_leveling", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
    public static void valveDefaultsOpenWhenLoadedWithoutThrottleNbt(GameTestHelper helper) {
        helper.runAfterDelay(2, () -> {
            Level level = helper.getLevel();
            BlockPos rel = new BlockPos(0, 1, 0); // simple_fluid_leveling: U-bottom-left pipe cell hosts the valve
            helper.setBlock(rel, AllBlocks.FLUID_VALVE.get().defaultBlockState());

            BlockPos abs = helper.absolutePos(rel);
            var registries = level.registryAccess();
            BlockEntity be = level.getBlockEntity(abs);
            if (be == null) { helper.fail("valve has no block entity"); return; }

            // Simulate an old-world save: serialize, drop the throttle key, reload through read().
            CompoundTag saved = be.saveWithoutMetadata(registries);
            saved.remove("ScrollValue");
            be.loadWithComponents(saved, registries);

            ScrollValueBehaviour throttle = BlockEntityBehaviour.get(level, abs, ScrollValueBehaviour.TYPE);
            if (throttle == null) { helper.fail("valve lost its throttle behaviour"); return; }
            if (throttle.getValue() != 90) {
                helper.fail("a valve reloaded without a throttle tag came up at " + throttle.getValue()
                        + "°, expected 90 (fully open) — pre-feature valves would shut");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * A cranked-open valve must HOLD open while its shaft idles — the open angle is a stored
     * position, so stopping the shaft (or having none) leaves it where it was set. An early
     * version gated ENABLED on live shaft speed and slammed the valve shut the moment rotation
     * stopped. Open a valve, read it once (as on a chunk reload), idle with no shaft — stays open.
     */
    @GameTest(template = "common/simple_fluid_leveling", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
    public static void valveStaysOpenWhileShaftIdles(GameTestHelper helper) {
        helper.runAfterDelay(2, () -> {
            Level level = helper.getLevel();
            BlockPos rel = new BlockPos(0, 1, 0); // simple_fluid_leveling: U-bottom-left pipe cell hosts the valve
            BlockPos cell = rel;
            helper.setBlock(cell, AllBlocks.FLUID_VALVE.get().defaultBlockState()
                    .setValue(FluidValveBlock.FACING, Direction.UP)
                    .setValue(FluidValveBlock.ENABLED, true));

            var registries = level.registryAccess();
            BlockEntity be = level.getBlockEntity(helper.absolutePos(cell));
            if (be == null) { helper.fail("valve has no block entity"); return; }
            // Read once so the open latch initializes from ENABLED, like a chunk reload does.
            be.loadWithComponents(be.saveWithoutMetadata(registries), registries);

            helper.runAfterDelay(30, () -> { // idle, no shaft attached
                if (!helper.getBlockState(cell).getValue(FluidValveBlock.ENABLED)) {
                    helper.fail("an opened valve snapped shut while its shaft idled — the latch was lost");
                    return;
                }
                helper.succeed();
            });
        });
    }

    /**
     * The handle a player SEES must say what the valve actually passes. A valve comes into the
     * world fully open (90 degrees), but Create only ever re-aims its pointer on a SPEED change,
     * and starts it at 0 — so a freshly placed valve read shut on its face while flowing
     * everything through ("the dial does not match the actual pass through rate"). The needle is
     * now pointed at the opening every tick, snapping the first time rather than winding up from
     * shut. The server ticks the chaser too, so the position is readable here.
     */
    @GameTest(template = "common/simple_fluid_leveling", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
    public static void freshValveHandleMatchesItsOpening(GameTestHelper helper) {
        BlockPos valve = new BlockPos(0, 1, 0); // simple_fluid_leveling: the U-bottom-left pipe cell
        helper.runAfterDelay(2, () -> helper.setBlock(valve, AllBlocks.FLUID_VALVE.get().defaultBlockState()
                .setValue(FluidValveBlock.FACING, Direction.UP)));

        // One tick is all it may take: the needle SNAPS on first sight, it does not wind open.
        helper.runAfterDelay(4, () -> {
            Level level = helper.getLevel();
            BlockPos abs = helper.absolutePos(valve);
            int angle = BlockEntityBehaviour.get(level, abs, ScrollValueBehaviour.TYPE).getValue();
            if (angle != 90) {
                helper.fail("a placed valve should come up fully open, got " + angle);
                return;
            }
            if (!valveHandleReads(helper, abs, 1f)) return;

            setThrottle(level, abs, 45);
            helper.runAfterDelay(30, () -> { // no shaft, so it eases over at the idle chase speed
                if (!valveHandleReads(helper, abs, 0.5f)) return;
                helper.succeed();
            });
        });
    }

    /** Whether the valve's needle sits where its opening says it should. */
    private static boolean valveHandleReads(GameTestHelper helper, BlockPos valveAbs, float expected) {
        if (!(helper.getLevel().getBlockEntity(valveAbs) instanceof FluidValveAccessor valve)) {
            helper.fail("no valve block entity at " + valveAbs);
            return false;
        }
        float actual = valve.pipesnphysics$pointer().getValue();
        if (Math.abs(actual - expected) > 0.01f) {
            helper.fail("the valve's handle reads " + actual + " while its opening is " + expected);
            return false;
        }
        return true;
    }

    /**
     * The valve-side of the crank: a Valve Handle adds its set angle to connected valves via
     * {@code adjustThrottle}, which must step the opening by that many degrees and clamp 0–90.
     * (The handle applies its INTENT directly because its actual shaft rotation overshoots a small
     * set angle — 1° turns the shaft ~17°.) Drive a few steps and a clamp at each end.
     */
    @GameTest(template = "common/simple_fluid_leveling", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
    public static void valveHandleStepsAndClampsTheThrottle(GameTestHelper helper) {
        helper.runAfterDelay(2, () -> {
            Level level = helper.getLevel();
            BlockPos rel = new BlockPos(0, 1, 0); // simple_fluid_leveling: U-bottom-left pipe cell hosts the valve
            helper.setBlock(rel, AllBlocks.FLUID_VALVE.get().defaultBlockState()
                    .setValue(FluidValveBlock.FACING, Direction.UP));

            BlockPos abs = helper.absolutePos(rel);
            if (!(level.getBlockEntity(abs) instanceof ValveThrottle valve)) {
                helper.fail("valve BE is not a ValveThrottle"); return;
            }
            ScrollValueBehaviour t = BlockEntityBehaviour.get(level, abs, ScrollValueBehaviour.TYPE);
            t.setValue(40);
            valve.pipesnphysics$adjustThrottle(10);   // 40 -> 50
            if (t.getValue() != 50) { helper.fail("+10 from 40 gave " + t.getValue()); return; }
            valve.pipesnphysics$adjustThrottle(-30);  // 50 -> 20
            if (t.getValue() != 20) { helper.fail("-30 from 50 gave " + t.getValue()); return; }
            valve.pipesnphysics$adjustThrottle(-90);  // clamp to 0
            if (t.getValue() != 0) { helper.fail("-90 from 20 should clamp to 0, got " + t.getValue()); return; }
            valve.pipesnphysics$adjustThrottle(200);  // clamp to 90
            if (t.getValue() != 90) { helper.fail("+200 from 0 should clamp to 90, got " + t.getValue()); return; }
            helper.succeed();
        });
    }

    /**
     * A ONE-WAY valve blocks reverse flow in the SOLVE and at REST. The U rig's valve arrow
     * points EAST at the full right tank, whose pressure pushes WEST — against the arrow — so
     * it must move NOTHING: the valve becomes a conducting gate NODE whose pressed branch the
     * solver backflow-blocks with {@code Reason.CHECK_VALVE} (the goggle reads "flow is against
     * the one-way valve"), and after 100 live ticks of settling neither the far tank nor the far
     * pipes nor the valve's own slot hold a drop (the settle's only cross-node path, the slot
     * exchange, honors the direction — without that guard the slot slowly ferries the levels
     * across at rest).
     */
    @GameTest(template = "common/simple_fluid_leveling", templateNamespace = PipesNPhysics.ID, timeoutTicks = 300)
    public static void oneWayValveBlocksReverseFlowAndSettleLeak(GameTestHelper helper) {
        helper.runAfterDelay(2, () -> {
            ServerLevel level = helper.getLevel();
            BlockPos valveRel = new BlockPos(1, 1, 0);  // simple_fluid_leveling: the U-bottom cell
            BlockPos leftTank = new BlockPos(0, 3, 0);  // stays EMPTY — behind the arrow
            BlockPos rightTank = new BlockPos(2, 3, 0); // pressurized — presses against the arrow
            drain(helper, leftTank);
            drain(helper, rightTank);

            BlockState valve = AllBlocks.FLUID_VALVE.get().defaultBlockState()
                    .setValue(FluidValveBlock.FACING, Direction.UP);
            if (FluidValveBlock.getPipeAxis(valve) != Direction.Axis.X) {
                valve = valve.setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE,
                        !valve.getValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE));
            }
            helper.setBlock(valveRel, valve.setValue(FluidValveBlock.ENABLED, true));
            BlockPos valveAbs = helper.absolutePos(valveRel);
            setOneWay(level, valveAbs, Direction.EAST); // arrow → East (at the pressurized right tank)
            // PARTIAL fill: a brimming tank is box-clamped give-only (a dead conduit, SINK_FULL)
            // before the direction wall can even fire — the pure check-valve story needs headroom.
            fill(helper, rightTank, 6000);              // its pressure pushes WEST — against the arrow

            Graph g = GraphBuilder.build(level, valveAbs);
            var gate = g.nodeAt(valveAbs);
            if (gate == null || !gate.isOneWayGate()) {
                helper.fail("one-way valve did not become a gate node: "
                        + (gate == null ? "null" : gate.kind() + " gateFlow=" + gate.gateFlow()));
                return;
            }
            if (g.edgesOf(gate.index()).size() != 2) {
                helper.fail("one-way gate did not split the run into 2 edges");
                return;
            }
            Solution sol = FlowSolver.solve(level, g);
            boolean checkValveFlagged = false;
            for (Edge e : g.edgesOf(gate.index())) {
                if (sol.edgeFlows().get(e.index()).mbPerTick() != 0) {
                    helper.fail("flow crossed the one-way valve against its direction");
                    return;
                }
                checkValveFlagged |= sol.blockedEdges().contains(e.index())
                        && sol.edgeReasons().get(e.index()) == Solution.Reason.CHECK_VALVE;
            }
            if (!checkValveFlagged) {
                helper.fail("the pressed reverse branch was not flagged Reason.CHECK_VALVE: "
                        + sol.edgeReasons());
                return;
            }

            // Let the live engine tick: settle may fill the right tank's OWN pipes, but nothing
            // may cross the valve — not into its slot, the far pipes, or the far tank.
            helper.runAfterDelay(100, () -> {
                if (amount(helper, leftTank) != 0) {
                    helper.fail("the check valve leaked at rest: far tank holds "
                            + amount(helper, leftTank) + " mB" + dump(helper, valveRel));
                    return;
                }
                if (cellMb(level, valveAbs) != 0
                        || pipeAmount(helper, new BlockPos(0, 1, 0)) != 0
                        || pipeAmount(helper, new BlockPos(0, 2, 0)) != 0) {
                    helper.fail("fluid crossed into the one-way valve's slot or far pipes at rest: slot="
                            + cellMb(level, valveAbs)
                            + " farBottom=" + pipeAmount(helper, new BlockPos(0, 1, 0))
                            + " farRiser=" + pipeAmount(helper, new BlockPos(0, 2, 0)));
                    return;
                }
                // The goggle story on the DRY side the valve refuses to fill: BLOCKED,
                // "flow is against the one-way valve" — not a mere "pipe is dry".
                PipeStatusPayload dry = PipeProbe.probe(level, helper.absolutePos(new BlockPos(0, 1, 0)));
                if (dry.statusDetail() != PipeStatusPayload.DETAIL_CHECK_VALVE) {
                    helper.fail("the walled dry side does not read DETAIL_CHECK_VALVE: status="
                            + dry.status() + " detail=" + dry.statusDetail());
                    return;
                }
                // With fluid on BOTH sides (still higher on the pressed one), the reverse branch
                // assembles and the active set deactivates it — the backflow-blocked path must
                // flag CHECK_VALVE too, and still move nothing.
                fill(helper, leftTank, 2000);
                Graph pressed = GraphBuilder.build(level, valveAbs);
                Solution pressedSol = FlowSolver.solve(level, pressed);
                var pressedGate = pressed.nodeAt(valveAbs);
                boolean backflowFlagged = false;
                for (Edge e : pressed.edgesOf(pressedGate.index())) {
                    if (pressedSol.edgeFlows().get(e.index()).mbPerTick() != 0) {
                        helper.fail("unequal levels drove flow backward through the one-way valve");
                        return;
                    }
                    backflowFlagged |= pressedSol.blockedEdges().contains(e.index())
                            && pressedSol.edgeReasons().get(e.index()) == Solution.Reason.CHECK_VALVE;
                }
                if (!backflowFlagged) {
                    helper.fail("the deactivated reverse branch was not flagged Reason.CHECK_VALVE: "
                            + pressedSol.edgeReasons());
                    return;
                }
                // Conservation across the valve with fluid on BOTH sides: the pressed east side
                // may only exchange with its own pipes, so its inventory stays exactly 6000 —
                // any deviation is fluid ferried through the gate slot at rest.
                helper.runAfterDelay(100, () -> {
                    int east = amount(helper, rightTank)
                            + pipeAmount(helper, new BlockPos(2, 1, 0))
                            + pipeAmount(helper, new BlockPos(2, 2, 0));
                    if (east != 6000) {
                        helper.fail("the pressed side's inventory changed across the check valve at rest: "
                                + east + " mB (expected 6000)" + dump(helper, valveRel));
                        return;
                    }
                    helper.succeed();
                });
            });
        });
    }

    /**
     * The same rig flowing WITH the arrow: pressure from behind the one-way valve passes — the
     * gate conducts, the brigade threads its slot, and the two tanks equalize through it. Guards
     * against the wall over-rotating into "one-way valves never conduct".
     */
    @GameTest(template = "common/simple_fluid_leveling", templateNamespace = PipesNPhysics.ID, timeoutTicks = 400)
    public static void oneWayValveStillFlowsForward(GameTestHelper helper) {
        helper.runAfterDelay(2, () -> {
            Level level = helper.getLevel();
            BlockPos valveRel = new BlockPos(1, 1, 0);  // simple_fluid_leveling: the U-bottom cell
            BlockPos leftTank = new BlockPos(0, 3, 0);  // pressurized — pushes WITH the arrow
            BlockPos rightTank = new BlockPos(2, 3, 0); // receives through the valve
            drain(helper, leftTank);
            drain(helper, rightTank);

            BlockState valve = AllBlocks.FLUID_VALVE.get().defaultBlockState()
                    .setValue(FluidValveBlock.FACING, Direction.UP);
            if (FluidValveBlock.getPipeAxis(valve) != Direction.Axis.X) {
                valve = valve.setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE,
                        !valve.getValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE));
            }
            helper.setBlock(valveRel, valve.setValue(FluidValveBlock.ENABLED, true));
            BlockPos valveAbs = helper.absolutePos(valveRel);
            setOneWay(level, valveAbs, Direction.EAST); // arrow → East (toward the empty right tank)
            fill(helper, leftTank, 8000);               // pressure from the WEST — the allowed side

            Solution sol = FlowSolver.solve(level, GraphBuilder.build(level, valveAbs));
            boolean flows = sol.edgeFlows().stream().anyMatch(f -> f.mbPerTick() > 0);
            if (!flows) {
                helper.fail("a one-way valve refused flow ALONG its direction" + dump(helper, valveRel));
                return;
            }
            helper.runAfterDelay(200, () -> {
                int received = amount(helper, rightTank);
                if (received < 1000) {
                    helper.fail("forward flow through the one-way valve moved too little: "
                            + received + " mB" + dump(helper, valveRel));
                    return;
                }
                helper.succeed();
            });
        });
    }

    /**
     * A pump pressing a check valve the WRONG way is refused at branch assembly — the run is
     * BLOCKED with {@code Reason.CHECK_VALVE} (not a phantom held column, not a generic block),
     * so the goggle names the valve rather than leaving the player to suspect the pump.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
    public static void pumpAgainstCheckValveReadsCheckValve(GameTestHelper helper) {
        helper.runAfterDelay(10, () -> { // let the kinetics spin the pump up and settle its FACING
            Level level = helper.getLevel();
            BlockPos pumpRel = new BlockPos(2, 1, 1); // piping/single_pump: pump pinned from NBT
            Direction push = helper.getBlockState(pumpRel).getValue(PumpBlock.FACING);
            BlockPos valveRel = pumpRel.relative(push);
            if (!pipeAt(helper, valveRel)) { helper.fail("pump push side is not a pipe cell"); return; }

            BlockState valve = AllBlocks.FLUID_VALVE.get().defaultBlockState()
                    .setValue(FluidValveBlock.FACING, Direction.UP);
            if (FluidValveBlock.getPipeAxis(valve) != push.getAxis()) {
                valve = valve.setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE,
                        !valve.getValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE));
            }
            helper.setBlock(valveRel, valve.setValue(FluidValveBlock.ENABLED, true));
            BlockPos valveAbs = helper.absolutePos(valveRel);
            setOneWay(level, valveAbs, push.getOpposite()); // arrow points BACK at the pump

            fill(helper, new BlockPos(0, 1, 1), 8000); // source full
            drain(helper, new BlockPos(4, 1, 1));      // sink empty -> the pump wants to deliver

            Graph g = GraphBuilder.build(level, valveAbs);
            var gate = g.nodeAt(valveAbs);
            if (gate == null || !gate.isOneWayGate()) { helper.fail("valve is not a one-way gate"); return; }
            Solution sol = FlowSolver.solve(level, g);
            for (EdgeFlow f : sol.edgeFlows()) {
                if (f.mbPerTick() != 0) {
                    helper.fail("the pump drove flow through a check valve pointing back at it");
                    return;
                }
            }
            Edge pumpEdge = null;
            for (Edge e : g.edgesOf(gate.index())) {
                if (g.node(e.a()).isPump() || g.node(e.b()).isPump()) pumpEdge = e;
            }
            if (pumpEdge == null) { helper.fail("no pump edge at the gate"); return; }
            if (!sol.blockedEdges().contains(pumpEdge.index())
                    || sol.edgeReasons().get(pumpEdge.index()) != Solution.Reason.CHECK_VALVE) {
                helper.fail("pump vs check valve not flagged Reason.CHECK_VALVE: "
                        + sol.edgeReasons().get(pumpEdge.index()));
                return;
            }
            helper.succeed();
        });
    }

    /**
     * The board's selection packet routes by {@code netId} (every behaviour defaults to 0), so the
     * direction dial and the throttle on one valve MUST carry distinct ids — with equal ids the
     * dial's selection landed on the throttle (first match), cranking it to 0-2° while the
     * direction never changed ("the selection does not really work"). Also drives the dial through
     * the same {@code setValueSettings} entry the packet uses and asserts the right behaviour moved.
     */
    @GameTest(template = "common/simple_fluid_leveling", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
    public static void valveDialAndThrottleRouteDistinctly(GameTestHelper helper) {
        helper.runAfterDelay(2, () -> {
            Level level = helper.getLevel();
            BlockPos rel = new BlockPos(0, 1, 0); // simple_fluid_leveling: U-bottom-left pipe cell hosts the valve
            helper.setBlock(rel, AllBlocks.FLUID_VALVE.get().defaultBlockState()
                    .setValue(FluidValveBlock.FACING, Direction.UP));

            BlockPos abs = helper.absolutePos(rel);
            ScrollValueBehaviour throttle = BlockEntityBehaviour.get(level, abs, ScrollValueBehaviour.TYPE);
            ValveDirectionBehaviour dial = BlockEntityBehaviour.get(level, abs, ValveDirectionBehaviour.TYPE);
            if (throttle == null || dial == null) { helper.fail("valve lost a behaviour"); return; }
            if (dial.netId() == throttle.netId()) {
                helper.fail("dial and throttle share netId " + dial.netId()
                        + " — ValueSettingsPacket would deliver the dial's selection to the throttle");
                return;
            }
            dial.setValueSettings(helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL),
                    new com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBehaviour
                            .ValueSettings(0, 2), false);
            if (dial.getValue() != 2) {
                helper.fail("the dial did not accept its board selection: " + dial.getValue());
                return;
            }
            if (throttle.getValue() != 90) {
                helper.fail("selecting a direction moved the THROTTLE to " + throttle.getValue()
                        + "° — the selection was routed to the wrong behaviour");
                return;
            }
            helper.succeed();
        });
    }

    /** Dial the valve's one-way direction (a world direction on its pipe axis) via its behaviour. */
    private static void setOneWay(Level level, BlockPos valveAbs, Direction direction) {
        ValveDirectionBehaviour dial = BlockEntityBehaviour.get(level, valveAbs, ValveDirectionBehaviour.TYPE);
        BlockState state = level.getBlockState(valveAbs);
        int value = ValveDirectionBehaviour.directionFor(state, 1) == direction ? 1 : 2;
        dial.setValue(value);
    }

    /**
     * A valve follows the rotation REACHING IT, exactly as Create's own does
     * ({@code pointer.chase(speed > 0 …)}). So anything in the drivetrain that reverses that
     * rotation reverses the valve: a gearshift flipping, a gearbox output, a crank turned back.
     *
     * The rig is a creative motor into a gearbox with a valve on each of two outputs whose signs
     * Create deliberately opposes: one EAST of the box on an X shaft, one SOUTH of it on a Z shaft.
     * The opposition is asserted first, so the rig can never go vacuous. Both valves start half
     * open, and the one being turned FORWARD must climb to fully open while the one being turned
     * BACK must close, each read off its own speed rather than hardcoded. Taking the direction at
     * the network's SOURCE instead (as an earlier version did) makes both climb together and leaves
     * every gearshift in the drivetrain inert, which is what this pins against. The Valve Handle
     * path multiplies the same sign but cannot be driven here, since {@code activate} refuses to
     * crank a shaft the motor already turns.
     */
    @GameTest(template = "blocks/valve_crank_pair", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void reversedDrivetrainCranksItsValveTheOtherWay(GameTestHelper helper) {
        BlockPos eastValve = new BlockPos(3, 1, 0);  // on the gearbox's X output
        BlockPos southValve = new BlockPos(2, 1, 1); // on its Z output, reversed by the gearbox

        helper.runAfterDelay(5, () -> {
            Level level = helper.getLevel();
            BlockPos eastAbs = helper.absolutePos(eastValve);
            BlockPos southAbs = helper.absolutePos(southValve);

            if (!(level.getBlockEntity(eastAbs) instanceof KineticBlockEntity east)
                    || !(level.getBlockEntity(southAbs) instanceof KineticBlockEntity south)) {
                helper.fail("valve block entities missing"); return;
            }
            if (east.getSpeed() == 0 || south.getSpeed() == 0) {
                helper.fail("the motor is not driving both valves: east=" + east.getSpeed()
                        + " south=" + south.getSpeed());
                return;
            }
            if (Math.signum(east.getSpeed()) == Math.signum(south.getSpeed())) {
                helper.fail("rig is vacuous: the gearbox no longer opposes the two outputs, east="
                        + east.getSpeed() + " south=" + south.getSpeed());
                return;
            }
            if (((ValveThrottle) east).pipesnphysics$openingSign()
                    == ((ValveThrottle) south).pipesnphysics$openingSign()) {
                helper.fail("a reversed output must crank its valve the other way, but both read "
                        + ((ValveThrottle) east).pipesnphysics$openingSign());
                return;
            }
            setThrottle(level, eastAbs, 45);
            setThrottle(level, southAbs, 45);
        });

        // 4.5 degrees of travel per tick at the motor's 16 RPM, so half open runs out in ten either way.
        helper.runAfterDelay(40, () -> {
            Level level = helper.getLevel();
            assertCrankedTo(helper, level, eastValve, "east");
            assertCrankedTo(helper, level, southValve, "south");
            helper.succeed();
        });
    }

    /** A valve turned forward must have reached fully open, one turned back must have shut. */
    private static void assertCrankedTo(GameTestHelper helper, Level level, BlockPos valve, String name) {
        BlockPos abs = helper.absolutePos(valve);
        float speed = level.getBlockEntity(abs) instanceof KineticBlockEntity kinetic ? kinetic.getSpeed() : 0;
        int expected = speed > 0 ? 90 : 0;
        int actual = BlockEntityBehaviour.get(level, abs, ScrollValueBehaviour.TYPE).getValue();
        if (actual != expected) {
            helper.fail("the " + name + " valve turns at " + speed + " so it should have cranked to "
                    + expected + ", got " + actual);
        }
    }

    /**
     * Runs {@code body} with the valve characteristic pinned to LINEAR, restoring the configured
     * curve after. The proportionality tests assert the LINEAR angle-to-flow bands (45 degrees
     * passes ~half); the gameTestServer reads the persisted dev run config, which may carry any
     * curve dialed in for play (EQUAL_PERCENTAGE passes ~12% at 45 degrees and rounds 18 degrees
     * to zero flow), so the tests must pin what they assert.
     */
    private static void withLinearValveCurve(Runnable body) {
        ValveCharacteristic curve = PipesNPhysicsConfig.VALVE_CHARACTERISTIC.get();
        PipesNPhysicsConfig.VALVE_CHARACTERISTIC.set(ValveCharacteristic.LINEAR);
        try {
            body.run();
        } finally {
            PipesNPhysicsConfig.VALVE_CHARACTERISTIC.set(curve);
        }
    }

    /** The solved hydraulic flow on the valve's edge after dialing the throttle to {@code angle}. */
    private static int valveFlow(Level level, Graph g, Edge edge, BlockPos valveAbs, int angle) {
        setThrottle(level, valveAbs, angle);
        Solution sol = FlowSolver.solve(level, g);
        for (EdgeFlow f : sol.edgeFlows()) {
            if (f.edgeIndex() == edge.index()) return f.mbPerTick();
        }
        return 0;
    }

    private static void setThrottle(Level level, BlockPos valveAbs, int angle) {
        ScrollValueBehaviour throttle = BlockEntityBehaviour.get(level, valveAbs, ScrollValueBehaviour.TYPE);
        if (throttle != null) throttle.setValue(angle);
    }
}
