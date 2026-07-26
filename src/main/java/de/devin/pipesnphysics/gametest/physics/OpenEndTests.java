package de.devin.pipesnphysics.gametest.physics;

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
import de.devin.pipesnphysics.engine.valve.ValveThrottle;
import de.devin.pipesnphysics.handler.NetworkEditHandler;
import de.devin.pipesnphysics.mixin.FluidTankAccessor;
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
 * Open-end spill & vacuum intake, cauldrons, hose bodies, fluid conduits.
 */
@GameTestHolder(PipesNPhysics.ID)
@PrefixGameTestTemplate(false)
public class OpenEndTests {

    /**
     * A dying run's last dregs must LEAVE through an open mouth at its level even when they sit
     * a cell short of it: the anti-slosh spread gate refuses to push the final DREGS_MB across a
     * level pair, so the mouth pour walks in across empty cells instead. And an EMPTY elevated
     * tank must not anchor the resting line at its own floor — that painted fill-targets above
     * the whole run, so nothing ever counted as excess and the dreg froze in place ("the flagged
     * pipe holds 4 mB, but that should flow out to the open end" report).
     */
    @GameTest(template = "physics/dreg_drop", templateNamespace = PipesNPhysics.ID, timeoutTicks = 120)
    public static void dregsPourOutTheMouthDespiteAnEmptyTankAbove(GameTestHelper helper) {
        // dreg_drop: an EMPTY tank(1,3,1) over an L-run dropping to a flat run that ends in an open
        // mouth at (4,1,1) (its EAST face opens to air).
        BlockPos dregCell = new BlockPos(3, 1, 1); // one short of the mouth cell at (4,1,1)

        helper.runAfterDelay(5, () -> {
            PipeStore.Store cell = PipeStore.at(helper.getLevel(), helper.absolutePos(dregCell));
            if (cell == null) {
                helper.fail("no pipe store at " + dregCell.toShortString());
                return;
            }
            cell.insert(new FluidStack(Fluids.WATER, 4), 4);
            cell.flush();
            EngineTickHandler.markChanged(helper.getLevel(), helper.absolutePos(dregCell));
        });
        helper.runAfterDelay(100, () -> {
            int stuck = pipeAmount(helper, dregCell) + pipeAmount(helper, new BlockPos(4, 1, 1));
            if (stuck > 0) {
                helper.fail(stuck + " mB of dregs frozen in the run — they must pour out the mouth");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Tank above an open-ended pipe pointing down: the fluid must spill out into
     * the world (the tank drains and a water block appears below the opening).
     */
    @GameTest(template = "openend/open_pipe", templateNamespace = PipesNPhysics.ID, timeoutTicks = 600)
    public static void openEndSpillsDownward(GameTestHelper helper) {
        BlockPos tank = new BlockPos(0, 3, 0);
        BlockPos space = new BlockPos(0, 1, 0);
        fill(helper, tank, 8000);

        helper.succeedWhen(() -> {
            if (amount(helper, tank) >= 8000) helper.fail("tank has not started draining");
            if (!helper.getLevel().getFluidState(helper.absolutePos(space)).isSource()) {
                helper.fail("no fluid placed below the open end");
            }
        });
    }

    /** A powered pump must push tank contents out of an open pipe end on its face. */
    @GameTest(template = "openend/open_end", templateNamespace = PipesNPhysics.ID, timeoutTicks = 600)
    public static void pumpPushesOutOfOpenEnd(GameTestHelper helper) {
        BlockPos tank = new BlockPos(2, 1, 0);
        BlockPos space = new BlockPos(0, 1, 0);
        fill(helper, tank, 8000);

        helper.succeedWhen(() -> {
            if (amount(helper, tank) >= 8000) helper.fail("tank has not started draining");
            if (!helper.getLevel().getFluidState(helper.absolutePos(space)).isSource()) {
                helper.fail("no fluid placed at the open end");
            }
        });
    }

    /**
     * A pump must spill a source whose surface sits BELOW the open-end mouth: it lifts the
     * fluid out, and once a full source's worth (1000 mB) has accumulated in the open end's
     * buffer a water block appears. Drains a low, intermittently-topped tank — the buffer must
     * ACCUMULATE across drains (not leak between them) and eventually place a block.
     */
    @GameTest(template = "openend/open_end", templateNamespace = PipesNPhysics.ID, timeoutTicks = 600)
    public static void pumpSpillsLowSourceOncePastBlockThreshold(GameTestHelper helper) {
        BlockPos tank = new BlockPos(2, 1, 0);
        BlockPos space = new BlockPos(0, 1, 0);
        fill(helper, tank, 600);
        helper.runAfterDelay(60, () -> fill(helper, tank, 600)); // > 1000 mB total over two drains
        helper.succeedWhen(() -> {
            if (!helper.getLevel().getFluidState(helper.absolutePos(space)).isSource()) {
                helper.fail("low source never spilled a block despite >1000 mB drained "
                        + "(buffer not accumulating across drains?)");
            }
        });
    }

    /**
     * NOTHING may leak into Create's own transport around a pump: with the engine enabled, no
     * Create-side {@code Flow} may ever form on the pump's connections, and every mB the tank
     * gives must be in the mouth's accumulation buffer — the sum conserved exactly. This is the
     * mechanism guard behind {@code pumpSpillsLowSourceOncePastBlockThreshold}: the pump
     * behaviour's SUBCLASS tick re-pressurizes its connections after the (cancelled) super call,
     * and when a peer addon's HEAD-hijack of the base tick won the injector race (CROWNS's
     * reimplementation — the same race the heartbeat was moved for), Create managed the pump's
     * flows against that pressure and its {@code FluidNetwork} ran a PARALLEL ~|speed|/2 mB/t
     * transfer into Create's own endpoint buffers — fluid vanishing from the engine's books, the
     * spill buffer stalling 89 mB short of its block threshold. {@code PumpTransferTickMixin}
     * cancels the subclass tick itself (no peer targets it), which removes the pressure source
     * and makes any reimplemented pipe transport inert.
     */
    @GameTest(template = "openend/open_end", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
    public static void pumpSpillLeaksNothingToCreateTransport(GameTestHelper helper) {
        BlockPos tank = new BlockPos(2, 1, 0);
        BlockPos space = new BlockPos(0, 1, 0);
        BlockPos pump = new BlockPos(1, 1, 0);
        fill(helper, tank, 600);
        // Sampled well past Create's flow spin-up (~17 ticks) and before the tank runs dry.
        helper.runAfterDelay(30, () -> {
            var transport = BlockEntityBehaviour.get(
                    helper.getLevel(), helper.absolutePos(pump), FluidTransportBehaviour.TYPE);
            if (transport != null && (transport.getFlow(Direction.EAST) != null
                    || transport.getFlow(Direction.WEST) != null)) {
                helper.fail("Create-side Flow formed on the pump — its transport tick is not "
                        + "suppressed (a peer addon won the base-tick race?)");
                return;
            }
            IFluidHandler mouth = OpenEndPipes.existing(helper.getLevel(), helper.absolutePos(space));
            int buffered = mouth == null ? 0 : mouth.getFluidInTank(0).getAmount();
            int held = amount(helper, tank);
            if (held + buffered != 600) {
                helper.fail("fluid vanished in transit: tank " + held + " + mouth buffer "
                        + buffered + " != 600 — a parallel Create transfer is draining the tank");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Conservation: a spill must never MINT a block. With only 500 mB of network fluid — less than
     * one source's 1000 mB — the open end's buffer can hold it but must NOT place a source block, or
     * fluid is created from nothing (the user's "placed a block but only took ~500 mB" duplication).
     */
    @GameTest(template = "openend/open_end", templateNamespace = PipesNPhysics.ID, timeoutTicks = 300)
    public static void spillDoesNotMintABlockFromTooLittleFluid(GameTestHelper helper) {
        BlockPos tank = new BlockPos(2, 1, 0);
        BlockPos space = new BlockPos(0, 1, 0);
        fill(helper, tank, 500); // less than one source block (1000 mB)
        helper.runAfterDelay(120, () -> {
            if (helper.getLevel().getFluidState(helper.absolutePos(space)).isSource()) {
                helper.fail("a 1000 mB source block appeared from only 500 mB of network fluid — duplication");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Re-enabled open-pipe INTAKE: a full water cauldron sits at an open pipe mouth that
     * drops to a tank below it, pulling the network head under the mouth (a "vacuum"). The
     * mouth must draw the cauldron's water IN — proving an open pipe sucks fluid from the
     * world again — and the cauldron drains to empty. Draining a cauldron leaves a clean
     * empty cauldron (nothing to re-spill), so nothing flickers. (A self-regenerating
     * lake is the other intake-eligible body; it drains the same way but is left intact.)
     */
    @GameTest(template = "openend/suck_from_cauldron", templateNamespace = PipesNPhysics.ID, timeoutTicks = 400)
    public static void openEndSucksFromCauldronUnderVacuum(GameTestHelper helper) {
        BlockPos cauldron = new BlockPos(0, 3, 0); // the open mouth slot: a riser pipe opens up into it
        helper.runAfterDelay(3, () -> helper.setBlock(cauldron,
                Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3)));

        helper.succeedWhen(() -> {
            if (helper.getBlockState(cauldron).is(Blocks.WATER_CAULDRON)) {
                helper.fail("cauldron not drained — open end did not suck the water in");
            }
        });
    }

    /**
     * A water cauldron beside a pipe must join the graph as an OPEN_END (Create's
     * VanillaFluidTargets path), NOT a HANDLER — even though NeoForge registers a
     * fluid-handler capability for cauldrons. Its CauldronWrapper only drains in whole
     * 1000 mB increments, far above MAX_FLOW_PER_ENDPOINT, so the generic handler path
     * reads it as empty and a pump beside it never pulls (the "won't suck from a cauldron"
     * bug). Routing it to the open end (atomic drain + buffered intake) is the fix.
     */
    @GameTest(template = "openend/suck_from_cauldron", templateNamespace = PipesNPhysics.ID, timeoutTicks = 400)
    public static void cauldronJoinsAsOpenEndNotHandler(GameTestHelper helper) {
        BlockPos cauldron = new BlockPos(0, 3, 0);
        BlockPos seed = new BlockPos(1, 1, 0);
        helper.setBlock(cauldron,
                Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3));

        helper.runAfterDelay(3, () -> {
            Graph graph = GraphBuilder.build(helper.getLevel(), helper.absolutePos(seed));
            var node = graph.nodes().stream()
                    .filter(n -> n.pos().equals(helper.absolutePos(cauldron)))
                    .findFirst().orElse(null);
            if (node == null) {
                helper.fail("cauldron is not in the graph");
                return;
            }
            if (!node.isOpenEnd()) {
                helper.fail("cauldron joined as " + node.kind() + ", expected OPEN_END");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * The cauldron's water must actually be DELIVERED to the sink, not merely vanish from
     * the cauldron. NeoForge's CauldronWrapper refuses every sub-1000 mB drain, so if
     * {@code apply} resolves the cauldron through that capability instead of the open-end
     * pipe, the solver shows flow while nothing moves — and the cauldron can still empty
     * via Create's manageSource side effect, leaving the tank dry ("shows a flow but moves
     * no fluid"). This asserts the creative tank at the run's end actually fills with water.
     */
    @GameTest(template = "openend/suck_from_cauldron", templateNamespace = PipesNPhysics.ID, timeoutTicks = 400)
    public static void cauldronIntakeActuallyFillsTheTank(GameTestHelper helper) {
        BlockPos cauldron = new BlockPos(0, 3, 0);
        BlockPos tank = new BlockPos(2, 1, 0);
        // The template's sink is a CREATIVE tank, which voids what it receives — useless as a
        // delivery probe. Swap in a real tank so "did the water actually arrive?" is observable.
        helper.setBlock(tank, AllBlocks.FLUID_TANK.get().defaultBlockState());
        helper.runAfterDelay(5, () -> helper.setBlock(cauldron,
                Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3)));

        helper.succeedWhen(() -> {
            IFluidHandler sink = helper.getLevel().getCapability(
                    Capabilities.FluidHandler.BLOCK, helper.absolutePos(tank), null);
            FluidStack held = sink == null ? FluidStack.EMPTY : sink.getFluidInTank(0);
            if (held.isEmpty() || !held.getFluid().isSame(Fluids.WATER)) {
                helper.fail("cauldron water did not reach the tank (held=" + held + ")");
            }
        });
    }

    /**
     * Intake of a body whose per-tick yield is BELOW the transfer cap (a full beehive
     * gives 250 mB &lt; MAX_FLOW 256): the mouth must draw it in (honey_level falls to 0)
     * and the engine must never request more than the world holds — Create's drain
     * over-reports a partial body, which would mint a few mB of honey from nothing. The
     * intake column's contentMb carries the real 250 mB yield and caps the request.
     */
    @GameTest(template = "openend/suck_from_cauldron", templateNamespace = PipesNPhysics.ID, timeoutTicks = 400)
    public static void openEndIntakeRespectsSubCapYield(GameTestHelper helper) {
        BlockPos hive = new BlockPos(0, 3, 0);
        BlockPos seed = new BlockPos(1, 1, 0);
        helper.runAfterDelay(3, () -> helper.setBlock(hive, Blocks.BEEHIVE.defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LEVEL_HONEY, 5)));

        // Solve while the hive is still full (before any natural intake drains it): the
        // mouth must plan to draw honey IN, and never request more than the body's 250 mB.
        helper.runAfterDelay(5, () -> {
            Graph graph = GraphBuilder.build(helper.getLevel(), helper.absolutePos(seed));
            Solution sol = FlowSolver.solve(helper.getLevel(), graph);
            Solution.Transfer fromHive = sol.transfers().stream()
                    .filter(t -> t.from().equals(helper.absolutePos(hive))).findFirst().orElse(null);
            if (fromHive == null) {
                helper.fail("open end did not draw honey from the beehive under vacuum");
                return;
            }
            if (fromHive.fluid().getAmount() > 250) {
                helper.fail("intake requested " + fromHive.fluid().getAmount()
                        + " mB from a 250 mB body — would duplicate fluid");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Cross-mod compat: a block tagged {@code pipesnphysics:fluid_conduits} (createpropulsion's
     * chainable liquid burner) is threaded into the network so a row of them shares fluid.
     * The engine cancels Create's transport that used to drive the burner's neighbour-
     * passthrough, so without this the directly-fed burner would fill alone. We build the
     * graph from a pipe feeding a row of three burners and assert all three are linked nodes.
     * Skips when createpropulsion is not loaded.
     */
    @GameTest(template = "common/long_equalization", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
    public static void fluidConduitChainsIntoOneNetwork(GameTestHelper helper) {
        Block burner = BuiltInRegistries.BLOCK
                .getOptional(ResourceLocation.parse("createpropulsion:liquid_burner")).orElse(null);
        if (burner == null) {
            helper.succeed(); // mod not present in this runtime — nothing to verify
            return;
        }

        BlockPos pipe = new BlockPos(0, 3, 0); // in the air above the 1-tall run
        BlockPos b0 = new BlockPos(0, 3, 1), b1 = new BlockPos(0, 3, 2), b2 = new BlockPos(0, 3, 3);
        helper.runAfterDelay(2, () -> {
            for (BlockPos p : new BlockPos[]{pipe, b0, b1, b2}) helper.setBlock(p, Blocks.AIR);
            helper.setBlock(b0, burner.defaultBlockState());
            helper.setBlock(b1, burner.defaultBlockState());
            helper.setBlock(b2, burner.defaultBlockState());
            helper.setBlock(pipe, pipeState(AllBlocks.FLUID_PIPE.get(), Direction.SOUTH)); // toward b0 (+z)
        });

        helper.runAfterDelay(6, () -> {
            Graph graph = GraphBuilder.build(helper.getLevel(), helper.absolutePos(pipe));
            long burnerNodes = graph.nodes().stream()
                    .filter(n -> helper.getLevel().getBlockState(n.pos()).is(burner))
                    .count();
            if (burnerNodes < 3) {
                helper.fail("conduit burners not all linked into the network: "
                        + burnerNodes + "/3 (nodes=" + graph.nodes().size()
                        + ", edges=" + graph.edges().size() + ")");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * The headline request: a hand-placed water block in front of an open mouth, with a
     * tank below pulling a vacuum, must be sucked IN. The network never spilled, so the
     * finite-source gate is open and the engine plans to draw from the mouth.
     */
    @GameTest(template = "openend/suck_from_cauldron", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void openEndDrinksHandPlacedSource(GameTestHelper helper) {
        BlockPos source = new BlockPos(0, 3, 0); // the open mouth slot
        BlockPos seed = new BlockPos(1, 1, 0);
        helper.runAfterDelay(8, () -> {
            helper.setBlock(source, Blocks.WATER.defaultBlockState()); // a lone, hand-placed source
            Graph graph = GraphBuilder.build(helper.getLevel(), helper.absolutePos(seed));
            Solution sol = FlowSolver.solve(helper.getLevel(), graph);
            boolean intake = sol.transfers().stream()
                    .anyMatch(t -> t.from().equals(helper.absolutePos(source)));
            if (!intake) helper.fail("open end did not draw in a hand-placed water source");
            else helper.succeed();
        });
    }

    /**
     * The anti-oscillation guard for finite sources: once a network has spilled, it must
     * NOT suck a finite source back in (its own spit, or a sibling mouth's), until a
     * cooldown lapses. Stamp a spill at the mouth, then confirm a source there is refused
     * within the cooldown and accepted again after it — the gate is temporary, not a ban.
     */
    @GameTest(template = "openend/suck_from_cauldron", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void openEndDoesNotReclaimAfterSpill(GameTestHelper helper) {
        BlockPos source = new BlockPos(0, 3, 0);
        BlockPos seed = new BlockPos(1, 1, 0);
        int cooldown = PipesNPhysicsConfig.OPEN_END_INTAKE_COOLDOWN_TICKS.get();

        helper.runAfterDelay(3, () ->
                OpenEndPipes.markSpilled(helper.getLevel(), helper.absolutePos(source)));

        // Within the cooldown: a source at the just-spilled mouth must NOT be drawn in.
        helper.runAfterDelay(6, () -> {
            helper.setBlock(source, Blocks.WATER.defaultBlockState());
            Solution sol = FlowSolver.solve(helper.getLevel(),
                    GraphBuilder.build(helper.getLevel(), helper.absolutePos(seed)));
            if (sol.transfers().stream().anyMatch(t -> t.from().equals(helper.absolutePos(source)))) {
                helper.fail("open end reclaimed a source within the spill cooldown");
            }
        });

        // After the cooldown lapses: the same source is drinkable again.
        helper.runAfterDelay(cooldown + 12, () -> {
            helper.setBlock(source, Blocks.WATER.defaultBlockState());
            Solution sol = FlowSolver.solve(helper.getLevel(),
                    GraphBuilder.build(helper.getLevel(), helper.absolutePos(seed)));
            if (sol.transfers().stream().noneMatch(t -> t.from().equals(helper.absolutePos(source)))) {
                helper.fail("intake never resumed after the spill cooldown elapsed");
            } else {
                helper.succeed();
            }
        });
    }

    /**
     * A HORIZONTAL open mouth must NEVER draw fluid IN — it is a spill outlet only. A sideways mouth
     * sits at the elevation of whatever it spills, so intake would just reclaim its own spilled block,
     * tick after tick (the "why can a horizontal pipe suck in water?" oscillation). Only a vertical
     * mouth may drink (a riser dipping into a body, or opening up beneath one). Face a flat mouth at a
     * hand-placed water source with an emptied tank pulling a hard vacuum below it: the vertical rig
     * ({@link #openEndDrinksHandPlacedSource}) drinks here, this one must plan NOTHING.
     */
    @GameTest(template = "physics/horizontal_mouth", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
    public static void horizontalMouthDoesNotSuckInWater(GameTestHelper helper) {
        // horizontal_mouth: tank(1,1,1) — riser(1,2,1) — flat mouthPipe(2,2,1) whose EAST face opens
        // HORIZONTALLY toward (3,2,1); the hand-placed source there is placed at RUNTIME (what's tested).
        BlockPos tank = new BlockPos(1, 1, 1);
        BlockPos riser = new BlockPos(1, 2, 1);
        BlockPos source = new BlockPos(3, 2, 1);    // the block the horizontal mouth faces

        helper.runAfterDelay(8, () -> {
            drain(helper, tank); // empty tank => head far below the mouth => a hard vacuum
            helper.setBlock(source, Blocks.WATER.defaultBlockState()); // a lone, hand-placed source
            Graph graph = GraphBuilder.build(helper.getLevel(), helper.absolutePos(riser));
            Solution sol = FlowSolver.solve(helper.getLevel(), graph);
            boolean intake = sol.transfers().stream()
                    .anyMatch(t -> t.from().equals(helper.absolutePos(source)));
            if (intake) helper.fail("horizontal open mouth sucked in a water source — it must be spill-only");
            else helper.succeed();
        });
    }

    /**
     * The solve must stay READ-ONLY at an open mouth. A foreign fluid's pass (here lava — it holds
     * the larger volume, so it runs first) must never probe the mouth's Create handler: doing so runs
     * OpenEndedPipe's spill-collision reaction, turning the mouth's water source into stone, straight
     * out of a supposedly read-only solve. Fill the only tank with lava, face the mouth at a water
     * source, and solve repeatedly — the water must survive every pass.
     */
    @GameTest(template = "openend/suck_from_cauldron", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void lavaPassDoesNotStoneifyIntakeMouthWaterSource(GameTestHelper helper) {
        BlockPos mouth = new BlockPos(0, 3, 0); // the space the riser opens up into
        BlockPos tank = new BlockPos(2, 1, 0);  // the network's only tank — hold LAVA (the larger pass)
        BlockPos seed = new BlockPos(1, 1, 0);
        // The template ships a CREATIVE tank (voids fills); swap in a real one so it truly holds lava.
        helper.setBlock(tank, AllBlocks.FLUID_TANK.get().defaultBlockState());
        helper.runAfterDelay(5, () -> {
            fillFluid(helper, tank, Fluids.LAVA, 8000);
            helper.setBlock(mouth, Blocks.WATER.defaultBlockState()); // a lone source at the mouth
        });
        // Force solves across the window; a lava pass probing the water-facing mouth must not mutate it.
        for (int t = 9; t <= 45; t += 4) {
            helper.runAfterDelay(t, () -> {
                FlowSolver.solve(helper.getLevel(),
                        GraphBuilder.build(helper.getLevel(), helper.absolutePos(seed)));
                BlockState front = helper.getBlockState(mouth);
                if (!front.is(Blocks.WATER) && !front.isAir()) {
                    helper.fail("a foreign-fluid solve mutated the open mouth's water source into "
                            + front.getBlock() + " (open-end fill(SIMULATE) is not read-only)");
                }
            });
        }
        helper.runAfterDelay(49, helper::succeed);
    }

    /**
     * An open-end mouth's cached OpenEndedPipe (which buffers partial spill/intake) must be pruned when
     * its pipe is broken, so the buffer is not leaked and a rebuilt mouth starts clean. A break that is
     * NOT the mouth's pipe must leave it alone. Regression for the cache only ever being cleared
     * wholesale on server stop (conservation acceptance criterion).
     */
    @GameTest(template = "openend/suck_from_cauldron", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void breakingMouthPipePrunesOpenEndCache(GameTestHelper helper) {
        BlockPos cauldronRel = new BlockPos(0, 3, 0); // the space the riser opens up into
        BlockPos mouthPipeRel = new BlockPos(0, 2, 0); // the riser cell whose open face points into it
        BlockPos seedRel = new BlockPos(1, 1, 0);
        BlockPos tankRel = new BlockPos(2, 1, 0);
        helper.runAfterDelay(3, () -> {
            var level = helper.getLevel();
            helper.setBlock(cauldronRel, Blocks.WATER_CAULDRON.defaultBlockState()
                    .setValue(LayeredCauldronBlock.LEVEL, 3));
            BlockPos space = helper.absolutePos(cauldronRel);

            // A solve resolves — and so caches — the open-end mouth.
            FlowSolver.solve(level, GraphBuilder.build(level, helper.absolutePos(seedRel)));
            if (OpenEndPipes.existing(level, space) == null) {
                helper.fail("open-end mouth was not cached after a solve");
                return;
            }
            // A break that is not this mouth's pipe must not prune it.
            OpenEndPipes.onPipeRemoved(level, helper.absolutePos(tankRel));
            if (OpenEndPipes.existing(level, space) == null) {
                helper.fail("a non-mouth break wrongly pruned the mouth cache");
                return;
            }
            // Breaking the mouth pipe drops its (stale) cached buffer.
            OpenEndPipes.onPipeRemoved(level, helper.absolutePos(mouthPipeRel));
            if (OpenEndPipes.existing(level, space) != null) {
                helper.fail("breaking the mouth pipe did not prune the cache");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * An open pipe mouth ABOVE the connected fluid, with the run at REST, must hold no fluid at the
     * top of the riser: the mouth is a vent (a spill/intake threshold), not a surface fluid climbs
     * to. Pre-fills the riser cells (as a dying flow would leave them) and asserts the settle pass
     * drains the mouth cell — the fluid falls back down instead of hanging at the opening, so
     * nothing renders (or drips particles) at the open end.
     */
    @GameTest(template = "openend/suck_from_cauldron", templateNamespace = PipesNPhysics.ID, timeoutTicks = 400)
    public static void restingOpenEndAboveSurfaceRendersDry(GameTestHelper helper) {
        BlockPos seed = new BlockPos(1, 1, 0); // leave the mouth slot (0,3,0) as AIR: an open riser
        // Swap the template's CREATIVE (brim-full) tank for a real half-filled one: the riser's
        // fluid must have somewhere to fall back to — above a sealed FULL tank a column physically
        // stays put, which is not the case under test.
        helper.setBlock(new BlockPos(2, 1, 0), AllBlocks.FLUID_TANK.get().defaultBlockState());
        helper.runAfterDelay(3, () -> fill(helper, new BlockPos(2, 1, 0), 4000));
        helper.runAfterDelay(5, () -> {
            Level level = helper.getLevel();
            Graph graph = GraphBuilder.build(level, helper.absolutePos(seed));

            Edge riser = null;
            for (Edge e : graph.edges()) {
                boolean open = graph.node(e.a()).isOpenEnd() || graph.node(e.b()).isOpenEnd();
                if (open && !e.pipes().isEmpty()) { riser = e; break; }
            }
            if (riser == null) { helper.fail("no open-end pipe run in graph" + dump(helper, seed)); return; }

            boolean aOpen = graph.node(riser.a()).isOpenEnd();
            BlockPos mouthCell = aOpen ? riser.pipes().get(0)
                    : riser.pipes().get(riser.pipes().size() - 1);
            for (BlockPos cell : riser.pipes()) {
                PipeStore.Store store = PipeStore.at(level, cell);
                if (store != null) {
                    store.insert(new FluidStack(Fluids.WATER, PipeStore.capacityMb()), PipeStore.capacityMb());
                    store.flush();
                }
            }
            helper.succeedWhen(() -> {
                if (cellMb(helper.getLevel(), mouthCell) > 0) {
                    helper.fail("open-end mouth cell still holds fluid while the run rests below "
                            + "the mouth — it would render (and drip) at the opening");
                }
            });
        });
    }

    /**
     * Breaking the pipe between two tanks leaves a dangling open-ended pipe; the
     * filled tank spills out of it. That spill must settle, not place and reclaim a
     * fluid block forever. Reproduces the user's "break a pipe → block spawns/
     * despawns forever" by removing the pump from tank-pipe-pump-pipe-tank, then
     * sampling the spilled block over consecutive ticks: present and stable.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 240)
    public static void openEndSpillDoesNotFlicker(GameTestHelper helper) {
        BlockPos source = new BlockPos(0, 1, 1);
        BlockPos gap = new BlockPos(2, 1, 1); // the pump's spot, soon to be a broken gap

        helper.runAfterDelay(3, () -> {
            helper.setBlock(gap, Blocks.AIR);             // break the run between the tanks
            helper.setBlock(new BlockPos(2, 0, 1), Blocks.STONE); // floor so the spill stays a source
            fill(helper, source, 6000);
        });

        boolean[] present = new boolean[16];
        for (int i = 0; i < 16; i++) {
            int idx = i;
            helper.runAfterDelay(180 + i, () ->
                    present[idx] = helper.getLevel().getFluidState(helper.absolutePos(gap)).isSource());
        }
        helper.runAfterDelay(200, () -> {
            for (boolean b : present) {
                if (!b) {
                    helper.fail("open-end spill flickered/absent (oscillation): "
                            + Arrays.toString(present));
                    return;
                }
            }
            helper.succeed();
        });
    }
}
