package de.devin.pipesnphysics;

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
import de.devin.pipesnphysics.engine.boundary.HandlerRoles;
import de.devin.pipesnphysics.engine.boundary.OpenEndPipes;
import de.devin.pipesnphysics.engine.boundary.RelayDetector;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * End-to-end engine tests on real Create blocks.
 * Run with: ./gradlew runGameTestServer
 */
@GameTestHolder(PipesNPhysics.ID)
@PrefixGameTestTemplate(false)
public class PipesNPhysicsGameTests {
    /**
     * Two identical tanks joined by a U-shaped pipe under them (communicating
     * vessels). One starts full; both must converge to equal fill at gameplay
     * speed, conserving fluid throughout.
     */
    @GameTest(template = "gravity/simple_fluid_leveling", templateNamespace = PipesNPhysics.ID, timeoutTicks = 800)
    public static void tanksEqualizeAtEqualSurfaces(GameTestHelper helper) {
        BlockPos left = new BlockPos(0, 3, 0);
        BlockPos right = new BlockPos(2, 3, 0);
        fill(helper, left, 8000);

        helper.succeedWhen(() -> {
            int a = amount(helper, left);
            int b = amount(helper, right);
            int pipes = pipesnphysics$areaPipeContent(helper, 4, 4, 2);
            if (a + b + pipes != 8000) {
                helper.fail("fluid not conserved: " + a + " + " + b + " + pipes " + pipes);
            }
            if (Math.abs(a - b) > 800) helper.fail("not equalized yet: " + a + " vs " + b);
        });
    }

    /**
     * Tank → pipe → powered pump → pipe → tank on flat ground. The pump must move
     * everything: the source drains to exactly 0 mB even though its connection sits
     * at base level (regression: the lip gate used to strand the last 80 mB).
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 600)
    public static void pumpMovesAllFluidOnFlatGround(GameTestHelper helper) {
        BlockPos source = new BlockPos(0, 1, 1);
        BlockPos sink = new BlockPos(4, 1, 1);
        fill(helper, source, 8000);

        helper.succeedWhen(() -> {
            int left = amount(helper, source);
            int moved = amount(helper, sink);
            int pipes = pipesnphysics$areaPipeContent(helper, 6, 4, 4);
            if (left + moved + pipes != 8000) {
                helper.fail("fluid not conserved: " + left + " + " + moved + " + pipes " + pipes);
            }
            if (left != 0) helper.fail("source still holds " + left + " mB");
        });
    }

    /**
     * A pump holding a sink full must top it back up after a PARTIAL consume, not wait for it to
     * empty. Both tanks start full (pump pressurizes the full sink = SINK_FULL), then a chunk is
     * drained straight from the sink handler (no block event, like a recipe consuming) and the
     * pump must refill it within a few ticks. NOTE: this is the general SINK case and it works —
     * a Create BASIN is different: it gates fill() on recipe state (an empty bare basin returns
     * accepts=0), so a basin only takes fluid when its recipe wants it. That "waits until drained"
     * behavior is Create's, not ours (it persists with the engine off), and we fill via the same
     * fill() the basin gates — so we can't force fluid in, only refill promptly once it accepts.
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 300)
    public static void fullSinkRefillsAfterPartialDrain(GameTestHelper helper) {
        BlockPos source = new BlockPos(0, 1, 1);
        BlockPos sink = new BlockPos(4, 1, 1);
        fill(helper, source, 8000);
        fill(helper, sink, 8000);

        helper.runAfterDelay(10, () -> {
            handler(helper, sink).drain(2000, IFluidHandler.FluidAction.EXECUTE);
            int afterDrain = amount(helper, sink);
            helper.runAfterDelay(40, () -> {
                int refilled = amount(helper, sink);
                if (refilled <= afterDrain + 200) {
                    helper.fail("sink NOT refilled after partial drain: drained to " + afterDrain
                            + ", 40 ticks later still " + refilled + " (source " + amount(helper, source) + ")");
                    return;
                }
                helper.succeed();
            });
        });
    }

    /**
     * A network holding a RUNNING PUMP is "armed": even when it solves to no flow this tick
     * (its source momentarily below the draw lip / empty, or its sink momentarily full), it must
     * re-check on the FAST heartbeat so it resumes the instant conditions allow — a level change
     * inside a tank or basin fires no block event to wake it. Regression: a STRONG pump pinned to
     * zero flow by an unsuppliable source carries no NO_HEAD flag, so it used to drop through to
     * the slow {@code IDLE_RECHECK_TICKS} heartbeat ("takes a long time to retick", "only refills
     * once the basin is near-empty"). Verifies the pump is detected as running and that an idle
     * solution on such a network routes to the fast cadence.
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
    public static void runningPumpArmsTheFastRecheck(GameTestHelper helper) {
        helper.runAfterDelay(10, () -> { // let the kinetics spin the pump up
            BlockPos seed = null;
            for (int x = 0; x < 6 && seed == null; x++)
                for (int y = 0; y < 4 && seed == null; y++)
                    for (int z = 0; z < 4 && seed == null; z++) {
                        BlockPos rel = new BlockPos(x, y, z);
                        if (helper.getBlockState(rel).is(AllBlocks.FLUID_PIPE.get())) seed = rel;
                    }
            if (seed == null) { helper.fail("no pipe in template"); return; }

            Graph g = GraphBuilder.build(helper.getLevel(), helper.absolutePos(seed));
            if (g.pumps().isEmpty()) { helper.fail("graph has no pump"); return; }
            if (!EngineTickHandler.hasRunningPump(helper.getLevel(), g)) {
                helper.fail("a spun-up pump was not detected as running");
                return;
            }

            Solution idle = Solution.idle(g);
            int armed = EngineTickHandler.recheckTicks(idle, true);
            int settled = EngineTickHandler.recheckTicks(idle, false);
            if (armed >= settled) {
                helper.fail("armed re-check (" + armed + ") must be faster than a settled one (" + settled + ")");
                return;
            }
            // The wiring under test: a real running-pump network routes to the fast cadence.
            if (EngineTickHandler.recheckTicks(idle, EngineTickHandler.hasRunningPump(helper.getLevel(), g)) != armed) {
                helper.fail("a running-pump network was not put on the fast re-check");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Integration check for the user-reported "slow retick": a running pump whose source
     * momentarily can't supply (here, empty) solves to no flow with NO NO_HEAD flag, so the
     * network sleeps. Refilling the source through its handler fires NO block event — exactly
     * like a recipe output or external feed — so the armed network must wake itself on the
     * re-check heartbeat and deliver. (The 20→4 SPEED-UP of that heartbeat for a running pump
     * is asserted deterministically by {@link #runningPumpArmsTheFastRecheck}; this test guards
     * the end-to-end path that the network wakes and delivers AT ALL without a block event —
     * the wake cadence here is gated by Create's idle-pipe ticking, not the re-check interval.)
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 160)
    public static void armedPumpRefillsSinkWithoutBlockEvent(GameTestHelper helper) {
        BlockPos source = new BlockPos(0, 1, 1);
        BlockPos sink = new BlockPos(4, 1, 1);
        drain(helper, source);    // source empty: the running pump has nothing to move -> idle
        drain(helper, sink);
        fill(helper, sink, 3000); // sink holds fluid so the network stays live (pipes keep ticking)
        int[] baseline = {0};
        helper.runAfterDelay(30, () -> baseline[0] = amount(helper, sink));
        helper.runAfterDelay(34, () -> fill(helper, source, 4000)); // refill the source: NO block event
        helper.runAfterDelay(150, () -> {
            int now = amount(helper, sink);
            if (now <= baseline[0]) {
                helper.fail("armed pump never delivered after the source rose with no block event "
                        + "(sink stayed " + baseline[0] + " mB, source " + amount(helper, source)
                        + ") — the network never woke itself");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * A basin holding TWO fluids (like water + milk for builder's tea) must get a partially
     * drained ingredient topped back up, not wait for it to hit zero. The basin keeps each
     * fluid in its own segment but reports a single representative {@code contents()} — the
     * engine used to treat the basin as a WALL for the OTHER fluid's pass, so a half-full water
     * segment never refilled while milk sat beside it (the "basin only refills once empty" bug).
     * Force a basin to hold lava + a half-full water segment, then a pump pushing water must
     * top the water back to full while the lava is untouched.
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void basinRefillsDrainedFluidBesideAnother(GameTestHelper helper) {
        BlockPos source = new BlockPos(0, 1, 1);
        BlockPos endPipe = new BlockPos(4, 1, 1);
        BlockPos basinPos = new BlockPos(4, 0, 1);
        helper.setBlock(endPipe, AllBlocks.FLUID_PIPE.get());
        helper.setBlock(basinPos, AllBlocks.BASIN.get());
        helper.runAfterDelay(5, () -> {
            BasinBlockEntity be = (BasinBlockEntity) helper.getBlockEntity(basinPos);
            var internal = (SmartFluidTankBehaviour.InternalFluidHandler) be.inputTank.getCapability();
            internal.forceFill(new FluidStack(Fluids.LAVA, 500), IFluidHandler.FluidAction.EXECUTE);
            internal.forceFill(new FluidStack(Fluids.WATER, 500), IFluidHandler.FluidAction.EXECUTE);
            if (basinFluid(helper, basinPos, Fluids.WATER) != 500) {
                helper.fail("setup: basin should hold 500 mB water beside the lava");
                return;
            }
            // Fill the source only NOW: delivery is instant, so filling it earlier would let the
            // running pump top the basin up before this setup ran, skewing the 500 mB baseline.
            fill(helper, source, 8000);
            helper.runAfterDelay(60, () -> {
                int waterNow = basinFluid(helper, basinPos, Fluids.WATER);
                if (waterNow <= 500) {
                    helper.fail("basin's half-full water segment NOT refilled (still " + waterNow
                            + ") — the lava walls the water pass");
                    return;
                }
                if (basinFluid(helper, basinPos, Fluids.LAVA) != 500) {
                    helper.fail("the other fluid (lava) was disturbed: "
                            + basinFluid(helper, basinPos, Fluids.LAVA));
                    return;
                }
                helper.succeed();
            });
        });
    }

    private static int basinFluid(GameTestHelper helper, BlockPos relativePos, Fluid fluid) {
        IFluidHandler h = handler(helper, relativePos);
        int sum = 0;
        for (int i = 0; i < h.getTanks(); i++) {
            FluidStack f = h.getFluidInTank(i);
            if (f.getFluid() == fluid) sum += f.getAmount();
        }
        return sum;
    }

    /**
     * A pipe run that leaves a junction and loops back to the SAME junction (a ring main) must
     * stay in the graph. The contraction walk used to record it as a self-loop edge, which the
     * dedup dropped — its cells landed in NO node and NO edge, so they never flowed or settled
     * and /pipegraph silently omitted them (the "pipegraph doesn't include the pipe I'm looking
     * at" report). The builder now splits such a run at its middle cell (a promoted JUNCTION)
     * into two parallel edges, so every ring cell belongs to the graph.
     */
    @GameTest(template = "piping/empty_canvas", templateNamespace = PipesNPhysics.ID, timeoutTicks = 60)
    public static void loopBackToSameJunctionStaysInTheGraph(GameTestHelper helper) {
        List<BlockPos> ring = List.of(
                new BlockPos(1, 1, 1), new BlockPos(2, 1, 1), new BlockPos(3, 1, 1),
                new BlockPos(3, 1, 2), new BlockPos(2, 1, 2), new BlockPos(1, 1, 2));
        BlockPos stub = new BlockPos(0, 1, 1); // third connection: makes (1,1,1) a junction
        for (BlockPos rel : ring) helper.setBlock(rel, AllBlocks.FLUID_PIPE.get());
        helper.setBlock(stub, AllBlocks.FLUID_PIPE.get());
        helper.runAfterDelay(5, () -> {
            Graph g = GraphBuilder.build(helper.getLevel(), helper.absolutePos(stub));
            for (BlockPos rel : ring) {
                BlockPos abs = helper.absolutePos(rel);
                boolean inGraph = g.nodeAt(abs) != null
                        || g.edges().stream().anyMatch(e -> e.pipes().contains(abs));
                if (!inGraph) {
                    helper.fail("ring cell " + rel.toShortString()
                            + " is in no node and no edge — the self-loop run was dropped");
                    return;
                }
            }
            helper.succeed();
        });
    }

    /**
     * A junction/gate slot holding a lighter-than-air gas must HOLD it, exactly like a gas run
     * ({@code SettlingRun} bails on gas): the slot's waterline target mixes the node head with
     * world Y, and a gas's head is INVERTED, so the liquid math read "drain to zero" — the slot
     * then bled its gas into an idle edge every settle tick while the brigade pushed it back, an
     * endless churn the player sees as the pipe "constantly filling from the top" (the TFMG
     * coke-oven CO2 report; diagnostic signature: actual= exactly the settle rate on an idle
     * edge). Skips when no lighter-than-air fluid is registered in this runtime.
     */
    @GameTest(template = "piping/empty_canvas", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
    public static void junctionSlotHoldsGasInsteadOfBleedingIt(GameTestHelper helper) {
        Fluid gas = null;
        for (Fluid f : BuiltInRegistries.FLUID) {
            if (f.defaultFluidState().isSource() && f.getFluidType().isLighterThanAir()) {
                gas = f;
                break;
            }
        }
        if (gas == null) {
            helper.succeed(); // no gas fluid in this runtime — nothing to verify
            return;
        }
        Fluid theGas = gas;

        // Capped everywhere (no open ends, one reservoir) so NO flow can solve: settle is the only
        // thing that could move the gas. The broken liquid-target math errs in a direction that
        // depends on the ABSOLUTE world Y (the inverted gas head crosses zero at y≈0): above it
        // the slot BLEEDS into the run cell, below it the slot PULLS the run cell's gas — so seed
        // BOTH stores and assert both hold, and the test bites at any test-world elevation.
        BlockPos tank = new BlockPos(1, 1, 1);
        BlockPos riser = new BlockPos(1, 2, 1);
        BlockPos center = new BlockPos(1, 3, 1); // riser + two stubs = 3 connections, a junction
        helper.setBlock(tank, AllBlocks.FLUID_TANK.get());
        helper.setBlock(riser, AllBlocks.FLUID_PIPE.get());
        helper.setBlock(center, AllBlocks.FLUID_PIPE.get());
        helper.setBlock(new BlockPos(2, 3, 1), AllBlocks.FLUID_PIPE.get());
        helper.setBlock(new BlockPos(1, 3, 2), AllBlocks.FLUID_PIPE.get());
        helper.setBlock(new BlockPos(3, 3, 1), Blocks.IRON_BLOCK);
        helper.setBlock(new BlockPos(1, 3, 3), Blocks.IRON_BLOCK);

        helper.runAfterDelay(10, () -> {
            handler(helper, tank).fill(new FluidStack(theGas, 2000), IFluidHandler.FluidAction.EXECUTE);
            PipeStore.Store slot = PipeStore.at(helper.getLevel(), helper.absolutePos(center));
            PipeStore.Store cell = PipeStore.at(helper.getLevel(), helper.absolutePos(riser));
            if (slot == null || cell == null) {
                helper.fail("no pipe store at the junction/riser cell");
                return;
            }
            slot.insert(new FluidStack(theGas, 200), 200);
            slot.flush();
            cell.insert(new FluidStack(theGas, 100), 100);
            cell.flush();
        });
        helper.runAfterDelay(80, () -> {
            PipeStore.Store slot = PipeStore.at(helper.getLevel(), helper.absolutePos(center));
            PipeStore.Store cell = PipeStore.at(helper.getLevel(), helper.absolutePos(riser));
            int slotHeld = slot == null ? 0 : slot.amount();
            int cellHeld = cell == null ? 0 : cell.amount();
            if (slotHeld < 150 || cellHeld < 90) {
                helper.fail("settle churned the resting gas (slot " + slotHeld + "/200, run cell "
                        + cellHeld + "/100) — the liquid waterline target moved a gas");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Two 1x1 tanks joined SIDE-ON (a lid connection sits above the waterline — the lip walls it)
     * by an 11-cell run arching over a y+2 crest: the minimal pump-less siphon rig. Connection
     * states are set EXPLICITLY — a raw setBlock keeps the pipe's default state (no shape update
     * runs on the placed block itself), and the stale open faces read as spilling open-end mouths.
     */
    private static List<BlockPos> buildSiphonArch(GameTestHelper helper, BlockPos tankA, BlockPos tankB) {
        helper.setBlock(tankA, AllBlocks.FLUID_TANK.get());
        helper.setBlock(tankB, AllBlocks.FLUID_TANK.get());
        Block pipe = AllBlocks.FLUID_PIPE.get();
        helper.setBlock(new BlockPos(0, 1, 1), pipeState(pipe, Direction.EAST, Direction.UP));
        helper.setBlock(new BlockPos(0, 2, 1), pipeState(pipe, Direction.DOWN, Direction.UP));
        helper.setBlock(new BlockPos(0, 3, 1), pipeState(pipe, Direction.DOWN, Direction.EAST));
        for (int x = 1; x <= 5; x++) {
            helper.setBlock(new BlockPos(x, 3, 1), pipeState(pipe, Direction.WEST, Direction.EAST));
        }
        helper.setBlock(new BlockPos(6, 3, 1), pipeState(pipe, Direction.WEST, Direction.DOWN));
        helper.setBlock(new BlockPos(6, 2, 1), pipeState(pipe, Direction.DOWN, Direction.UP));
        helper.setBlock(new BlockPos(6, 1, 1), pipeState(pipe, Direction.WEST, Direction.UP));
        return List.of(
                new BlockPos(0, 1, 1), new BlockPos(0, 2, 1), new BlockPos(0, 3, 1),
                new BlockPos(1, 3, 1), new BlockPos(2, 3, 1), new BlockPos(3, 3, 1),
                new BlockPos(4, 3, 1), new BlockPos(5, 3, 1), new BlockPos(6, 3, 1),
                new BlockPos(6, 2, 1), new BlockPos(6, 1, 1));
    }

    private static int pipeAmount(GameTestHelper helper, BlockPos rel) {
        PipeStore.Store cell = PipeStore.at(helper.getLevel(), helper.absolutePos(rel));
        return cell == null ? 0 : cell.amount();
    }

    /**
     * An UNPRIMED siphon must not start by itself: the crest sits above the source surface, and
     * suction can only HOLD a column there, never create one — nothing pushes water up a dry,
     * air-filled leg (the "why does this flow? that siphon is going up in y" report: the sink
     * barely gained while the solved trickle just climbed the ascending leg). The waterline may
     * still rise INTO the bottom leg cells (communicating vessels), but the cells above it must
     * stay dry.
     */
    @GameTest(template = "piping/empty_canvas", templateNamespace = PipesNPhysics.ID, timeoutTicks = 120)
    public static void dryCrestDoesNotSelfPrimeASiphon(GameTestHelper helper) {
        BlockPos tankA = new BlockPos(1, 1, 1);
        BlockPos tankB = new BlockPos(5, 1, 1);
        buildSiphonArch(helper, tankA, tankB);
        helper.runAfterDelay(5, () -> {
            fill(helper, tankA, 4000);
            fill(helper, tankB, 1000);
        });
        helper.runAfterDelay(100, () -> {
            int climbA = pipeAmount(helper, new BlockPos(0, 2, 1));
            int climbB = pipeAmount(helper, new BlockPos(6, 2, 1));
            if (climbA > 0 || climbB > 0) {
                helper.fail("the dry siphon climbed its leg by itself (" + climbA + " / " + climbB
                        + " mB above the waterline, tanks A=" + amount(helper, tankA)
                        + " B=" + amount(helper, tankB) + ") — a dry crest must not self-prime");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * The counterpart: the SAME arch with its run pre-filled (a primed column, as a pump would
     * leave behind) must siphon source→sink — the wet crest keeps the normal suction allowance —
     * and the sealed full column must not lose its prime to the idle waterline recede (which
     * would show as the SOURCE gaining fluid back from its own leg).
     */
    @GameTest(template = "piping/empty_canvas", templateNamespace = PipesNPhysics.ID, timeoutTicks = 140)
    public static void primedSiphonFlowsAndKeepsItsPrime(GameTestHelper helper) {
        BlockPos tankA = new BlockPos(1, 1, 1);
        BlockPos tankB = new BlockPos(5, 1, 1);
        List<BlockPos> run = buildSiphonArch(helper, tankA, tankB);
        helper.runAfterDelay(5, () -> {
            fill(helper, tankA, 4000);
            fill(helper, tankB, 1000);
            for (BlockPos rel : run) {
                PipeStore.Store cell = PipeStore.at(helper.getLevel(), helper.absolutePos(rel));
                if (cell == null) {
                    helper.fail("no pipe store at " + rel.toShortString());
                    return;
                }
                cell.insert(new FluidStack(Fluids.WATER, PipeStore.capacityMb()), PipeStore.capacityMb());
                cell.flush();
            }
        });
        helper.runAfterDelay(120, () -> {
            int a = amount(helper, tankA);
            int b = amount(helper, tankB);
            if (b < 1200 || a > 3950) {
                helper.fail("the primed siphon did not flow (source " + a + "/4000, sink " + b
                        + "/1000) — a wet crest within the suction limit must siphon");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * A FLOWING run below both waterlines must fill up WHILE it flows, source-side-first. The
     * brigade's plug rules alone only top the TAIL cell (delivery gates on a full tail; every
     * upstream cell passes what it receives, netting zero), so a submerged run froze at whatever
     * partial fill it started with, fullest at the sink ("the 3 pipes get increasingly more
     * fluid" report). The flowing top-up (SettlingRun.topUp) draws from the reservoirs toward
     * the hydrostatic profile alongside the flow: all three cells must reach ~full.
     */
    @GameTest(template = "piping/empty_canvas", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
    public static void submergedFlowingRunTopsUpFromTheSourceSide(GameTestHelper helper) {
        Block pipe = AllBlocks.FLUID_PIPE.get();
        // Two WIDE (2x2x3) tanks, run at their BOTTOM row (both waterlines above it). The head
        // difference stays SMALL — the freeze only shows when the flow is slow next to the cell
        // capacity (the report's 11 mB/t; a fast plug wave keeps cells near-full anyway) — and
        // the width gives the surfaces enough inertia that the run is still FLOWING at assert
        // time (equalized tanks would let the ordinary idle settle fill the run and hide the bug).
        for (int y = 1; y <= 3; y++) {
            for (int z = 1; z <= 2; z++) {
                helper.setBlock(new BlockPos(0, y, z), AllBlocks.FLUID_TANK.get());
                helper.setBlock(new BlockPos(1, y, z), AllBlocks.FLUID_TANK.get());
                helper.setBlock(new BlockPos(5, y, z), AllBlocks.FLUID_TANK.get());
                helper.setBlock(new BlockPos(6, y, z), AllBlocks.FLUID_TANK.get());
            }
        }
        List<BlockPos> run = List.of(
                new BlockPos(2, 1, 1), new BlockPos(3, 1, 1), new BlockPos(4, 1, 1));
        for (BlockPos rel : run) helper.setBlock(rel, pipeState(pipe, Direction.WEST, Direction.EAST));
        helper.runAfterDelay(5, () -> {
            fill(helper, new BlockPos(1, 1, 1), 88000); // surface 2.75 blocks up
            fill(helper, new BlockPos(5, 1, 1), 75200); // surface 2.35 — slow flow, long window
            for (BlockPos rel : run) {
                PipeStore.Store cell = PipeStore.at(helper.getLevel(), helper.absolutePos(rel));
                if (cell == null) {
                    helper.fail("no pipe store at " + rel.toShortString());
                    return;
                }
                cell.insert(new FluidStack(Fluids.WATER, 50), 50); // wet, so the plug moves freely
                cell.flush();
            }
            // The handler fills fire no block event; wake the sleeping network NOW so the flow
            // window starts immediately and the assert below lands well before equalization.
            EngineTickHandler.markChanged(helper.getLevel(), helper.absolutePos(run.get(0)));
        });
        helper.runAfterDelay(35, () -> {
            int full = PipeStore.capacityMb();
            // Guard the premise: the run must still be flowing (tanks not yet equalized), else
            // this asserts the idle settle rather than the flowing top-up.
            if (amount(helper, new BlockPos(1, 1, 1)) - amount(helper, new BlockPos(5, 1, 1)) < 1500) {
                helper.fail("rig equalized before the assert — enlarge the head difference");
                return;
            }
            for (BlockPos rel : run) {
                int held = pipeAmount(helper, rel);
                if (held < full - 25) {
                    helper.fail("submerged flowing cell " + rel.toShortString() + " holds " + held
                            + "/" + full + " — the run froze at the plug's partial fill instead of"
                            + " topping up to the waterline");
                    return;
                }
            }
            helper.succeed();
        });
    }

    /**
     * Swapping a pipe block IN PLACE (the shift-swap, Create's wrench window toggle, encasing)
     * replaces the block entity — and stored fluid rides the block entity, so the glassed pipe
     * used to come up EMPTY and the content was voided ("switch a pipe to glassed view loses its
     * content"). {@code PipeContentCarryMixin} stashes a destroyed cell's content and the
     * replacement cell adopts it as it initializes: swap a full pipe for its windowed variant and
     * the fluid must survive.
     */
    @GameTest(template = "piping/empty_canvas", templateNamespace = PipesNPhysics.ID, timeoutTicks = 60)
    public static void swappedPipeKeepsItsContent(GameTestHelper helper) {
        BlockPos middle = new BlockPos(2, 1, 1);
        // A single capped cell: no open mouths, no neighbours to settle into — the content can
        // only survive the swap or vanish with the replaced block entity.
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.IRON_BLOCK);
        helper.setBlock(middle, pipeState(AllBlocks.FLUID_PIPE.get(), Direction.WEST, Direction.EAST));
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.IRON_BLOCK);
        helper.runAfterDelay(5, () -> {
            PipeStore.Store cell = PipeStore.at(helper.getLevel(), helper.absolutePos(middle));
            if (cell == null) {
                helper.fail("no pipe store at the middle cell");
                return;
            }
            cell.insert(new FluidStack(Fluids.WATER, 250), 250);
            cell.flush();
            helper.setBlock(middle, AllBlocks.GLASS_FLUID_PIPE.get().defaultBlockState()
                    .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS,
                            Direction.Axis.X));
        });
        helper.runAfterDelay(30, () -> {
            PipeStore.Store cell = PipeStore.at(helper.getLevel(), helper.absolutePos(middle));
            int held = cell == null ? 0 : cell.amount();
            if (held < 250) {
                helper.fail("the swapped (glassed) pipe lost its content: holds " + held + "/250");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * The goggle's flow number must be the fluid ACTUALLY moved, not the solver's hydraulic
     * flow (which the lip / max-flow caps — or an unprimed pipe — hold below). The executor
     * records the real per-edge movement into {@code Solution.actualFlow}; a recorded 37 mB on
     * an edge whose hydraulic flow is 200 must report 37, so a near-empty source no longer
     * reads a brisk flow while only a trickle leaves the tank.
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
    public static void gogglePipeRateReflectsActualTransfer(GameTestHelper helper) {
        helper.runAfterDelay(2, () -> {
            BlockPos seed = null;
            for (int x = 0; x < 6 && seed == null; x++)
                for (int y = 0; y < 4 && seed == null; y++)
                    for (int z = 0; z < 4 && seed == null; z++) {
                        BlockPos rel = new BlockPos(x, y, z);
                        if (helper.getBlockState(rel).is(AllBlocks.FLUID_PIPE.get())) seed = rel;
                    }
            if (seed == null) { helper.fail("no pipe in template"); return; }

            Graph g = GraphBuilder.build(helper.getLevel(), helper.absolutePos(seed));
            Edge edge = null;
            for (Edge e : g.edges()) {
                if (!e.pipes().isEmpty()) { edge = e; break; }
            }
            if (edge == null) { helper.fail("no edge with pipes"); return; }

            List<EdgeFlow> flows = new ArrayList<>();
            for (Edge e : g.edges()) {
                flows.add(e.index() == edge.index()
                        ? new EdgeFlow(edge.index(), EdgeFlow.Direction.A_TO_B, 200)
                        : EdgeFlow.none(e.index()));
            }
            int[] actualFlow = new int[g.edges().size()];
            actualFlow[edge.index()] = 37;
            Solution sol = new Solution(flows, List.of(), List.of(), actualFlow,
                    Map.of(), Map.of(), Map.of(), Map.of(),
                    Map.of(), Set.of(), Set.of(), Set.of(), Set.of(), Map.of(), Map.of(), true);

            int actual = PipeProbe.actualEdgeFlow(g, sol, edge);
            if (actual != 37) {
                helper.fail("actualEdgeFlow=" + actual + " — expected the recorded 37 mB, "
                        + "not the hydraulic 200");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * The fine-grained valve throttle (a 0-90 degree scroll value) must scale a run's solved
     * flow: fully open at 90 degrees passes the full hydraulic flow, halving the angle roughly
     * halves it, and 0 degrees shuts the run (blocked, {@code Reason.VALVE}) exactly as the shaft
     * would. A valve is inserted into the bottom of a communicating-vessels U — no pump, so
     * conductance (not a pump cap) sets the rate — and the solved edge flow is read at each angle.
     * The shaft state is forced open and every solve happens in the SAME tick, before the
     * unpowered valve would chase {@code ENABLED} back to closed.
     */
    @GameTest(template = "gravity/simple_fluid_leveling", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
    public static void valveThrottleScalesFlow(GameTestHelper helper) {
        helper.runAfterDelay(2, () -> withLinearValveCurve(() -> {
            Level level = helper.getLevel();

            // The bottom of the U is a straight pipe cell connected only along X — host the valve there.
            BlockPos valveRel = null;
            BlockPos seedRel = null;
            for (int x = 0; x < 6 && valveRel == null; x++)
                for (int y = 0; y < 6 && valveRel == null; y++)
                    for (int z = 0; z < 4; z++) {
                        BlockPos rel = new BlockPos(x, y, z);
                        if (!pipeAt(helper, rel)) continue;
                        if (seedRel == null) seedRel = rel;
                        if (pipeAt(helper, rel.west()) && pipeAt(helper, rel.east())
                                && !pipeAt(helper, rel.above()) && !pipeAt(helper, rel.below())
                                && !pipeAt(helper, rel.north()) && !pipeAt(helper, rel.south())) {
                            valveRel = rel;
                            break;
                        }
                    }
            if (valveRel == null) { helper.fail("no straight X pipe cell to host a valve"); return; }

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
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
    public static void valveThrottleScalesPumpedFlow(GameTestHelper helper) {
        helper.runAfterDelay(10, () -> { // let the kinetics spin the pump up and settle its FACING
            Level level = helper.getLevel();
            BlockPos pumpRel = null;
            for (int x = 0; x < 6 && pumpRel == null; x++)
                for (int y = 0; y < 4 && pumpRel == null; y++)
                    for (int z = 0; z < 4; z++) {
                        if (helper.getBlockState(new BlockPos(x, y, z)).getBlock() instanceof PumpBlock) {
                            pumpRel = new BlockPos(x, y, z);
                            break;
                        }
                    }
            if (pumpRel == null) { helper.fail("no pump in template"); return; }
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
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void valveGovernsPumpedFlowFromThePullSide(GameTestHelper helper) {
        // let the kinetics spin the pump up and settle its FACING
        helper.runAfterDelay(10, () -> withLinearValveCurve(() -> {
            Level level = helper.getLevel();
            BlockPos pumpRel = null;
            for (int x = 0; x < 6 && pumpRel == null; x++)
                for (int y = 0; y < 4 && pumpRel == null; y++)
                    for (int z = 0; z < 4; z++)
                        if (helper.getBlockState(new BlockPos(x, y, z)).getBlock() instanceof PumpBlock) {
                            pumpRel = new BlockPos(x, y, z);
                            break;
                        }
            if (pumpRel == null) { helper.fail("no pump in template"); return; }
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
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
    public static void closedValveSplitsRunAndHoldsFeed(GameTestHelper helper) {
        helper.runAfterDelay(10, () -> { // let the kinetics spin the pump up and settle its FACING
            var level = helper.getLevel();
            BlockPos pumpRel = null;
            for (int x = 0; x < 6 && pumpRel == null; x++)
                for (int y = 0; y < 4 && pumpRel == null; y++)
                    for (int z = 0; z < 4; z++)
                        if (helper.getBlockState(new BlockPos(x, y, z)).getBlock() instanceof PumpBlock) {
                            pumpRel = new BlockPos(x, y, z);
                            break;
                        }
            if (pumpRel == null) { helper.fail("no pump in template"); return; }
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
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
    public static void unsuppliedPumpDeadheadingValveNotHeld(GameTestHelper helper) {
        helper.runAfterDelay(10, () -> {
            var level = helper.getLevel();
            BlockPos pumpRel = null;
            List<BlockPos> tanks = new ArrayList<>();
            for (int x = 0; x < 8; x++)
                for (int y = 0; y < 4; y++)
                    for (int z = 0; z < 4; z++) {
                        BlockPos rel = new BlockPos(x, y, z);
                        if (helper.getBlockState(rel).getBlock() instanceof PumpBlock) pumpRel = rel;
                        else if (helper.getBlockState(rel).is(AllBlocks.FLUID_TANK.get())) tanks.add(rel);
                    }
            if (pumpRel == null || tanks.size() != 2) {
                helper.fail("scan found pump=" + pumpRel + " tanks=" + tanks.size());
                return;
            }
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
    @GameTest(template = "piping/long_pipe", templateNamespace = PipesNPhysics.ID, timeoutTicks = 300)
    public static void heldValveReportsHeldAndResumes(GameTestHelper helper) {
        helper.runAfterDelay(10, () -> { // let the kinetics spin the pump up and settle its FACING
            var level = helper.getLevel();
            BlockPos pump = null;
            List<BlockPos> tanks = new ArrayList<>();
            for (int x = 0; x < 10; x++)
                for (int y = 0; y < 5; y++)
                    for (int z = 0; z < 4; z++) {
                        BlockPos rel = new BlockPos(x, y, z);
                        var st = helper.getBlockState(rel);
                        if (st.getBlock() instanceof PumpBlock) pump = rel;
                        else if (st.is(AllBlocks.FLUID_TANK.get())) tanks.add(rel);
                    }
            if (pump == null || tanks.size() != 2) {
                helper.fail("template scan found pump=" + pump + " tanks=" + tanks.size());
                return;
            }
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
    @GameTest(template = "piping/long_pipe", templateNamespace = PipesNPhysics.ID, timeoutTicks = 300)
    public static void shutValveToOpenEndLeavesDownstreamDry(GameTestHelper helper) {
        helper.runAfterDelay(10, () -> {
            var level = helper.getLevel();
            BlockPos pump = null;
            List<BlockPos> tanks = new ArrayList<>();
            for (int x = 0; x < 10; x++)
                for (int y = 0; y < 5; y++)
                    for (int z = 0; z < 4; z++) {
                        BlockPos rel = new BlockPos(x, y, z);
                        var st = helper.getBlockState(rel);
                        if (st.getBlock() instanceof PumpBlock) pump = rel;
                        else if (st.is(AllBlocks.FLUID_TANK.get())) tanks.add(rel);
                    }
            if (pump == null || tanks.size() != 2) {
                helper.fail("template scan found pump=" + pump + " tanks=" + tanks.size());
                return;
            }
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
    @GameTest(template = "piping/long_pipe", templateNamespace = PipesNPhysics.ID, timeoutTicks = 300)
    public static void shutValveToEmptyTankLeavesDownstreamDry(GameTestHelper helper) {
        helper.runAfterDelay(10, () -> {
            var level = helper.getLevel();
            BlockPos pump = null;
            List<BlockPos> tanks = new ArrayList<>();
            for (int x = 0; x < 10; x++)
                for (int y = 0; y < 5; y++)
                    for (int z = 0; z < 4; z++) {
                        BlockPos rel = new BlockPos(x, y, z);
                        var st = helper.getBlockState(rel);
                        if (st.getBlock() instanceof PumpBlock) pump = rel;
                        else if (st.is(AllBlocks.FLUID_TANK.get())) tanks.add(rel);
                    }
            if (pump == null || tanks.size() != 2) {
                helper.fail("template scan found pump=" + pump + " tanks=" + tanks.size());
                return;
            }
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
    @GameTest(template = "gravity/simple_fluid_leveling", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
    public static void valveDefaultsOpenWhenLoadedWithoutThrottleNbt(GameTestHelper helper) {
        helper.runAfterDelay(2, () -> {
            Level level = helper.getLevel();
            BlockPos rel = null;
            for (int x = 0; x < 6 && rel == null; x++)
                for (int y = 0; y < 6 && rel == null; y++)
                    for (int z = 0; z < 4; z++) {
                        if (pipeAt(helper, new BlockPos(x, y, z))) { rel = new BlockPos(x, y, z); break; }
                    }
            if (rel == null) { helper.fail("no pipe cell to host a valve"); return; }
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
    @GameTest(template = "gravity/simple_fluid_leveling", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
    public static void valveStaysOpenWhileShaftIdles(GameTestHelper helper) {
        helper.runAfterDelay(2, () -> {
            Level level = helper.getLevel();
            BlockPos rel = null;
            for (int x = 0; x < 6 && rel == null; x++)
                for (int y = 0; y < 6 && rel == null; y++)
                    for (int z = 0; z < 4; z++) {
                        if (pipeAt(helper, new BlockPos(x, y, z))) { rel = new BlockPos(x, y, z); break; }
                    }
            if (rel == null) { helper.fail("no pipe cell to host a valve"); return; }
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
     * The valve-side of the crank: a Valve Handle adds its set angle to connected valves via
     * {@code adjustThrottle}, which must step the opening by that many degrees and clamp 0–90.
     * (The handle applies its INTENT directly because its actual shaft rotation overshoots a small
     * set angle — 1° turns the shaft ~17°.) Drive a few steps and a clamp at each end.
     */
    @GameTest(template = "gravity/simple_fluid_leveling", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
    public static void valveHandleStepsAndClampsTheThrottle(GameTestHelper helper) {
        helper.runAfterDelay(2, () -> {
            Level level = helper.getLevel();
            BlockPos rel = null;
            for (int x = 0; x < 6 && rel == null; x++)
                for (int y = 0; y < 6 && rel == null; y++)
                    for (int z = 0; z < 4; z++) {
                        if (pipeAt(helper, new BlockPos(x, y, z))) { rel = new BlockPos(x, y, z); break; }
                    }
            if (rel == null) { helper.fail("no pipe cell to host a valve"); return; }
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

    private static boolean pipeAt(GameTestHelper helper, BlockPos rel) {
        return helper.getBlockState(rel).is(AllBlocks.FLUID_PIPE.get());
    }

    /** A raised tank must drain completely into the tank below it, no pump needed. */
    @GameTest(template = "gravity/2_drop_fall", templateNamespace = PipesNPhysics.ID, timeoutTicks = 600)
    public static void gravityDrainsUpperTankCompletely(GameTestHelper helper) {
        BlockPos top = new BlockPos(0, 4, 0);
        BlockPos bottom = new BlockPos(0, 1, 0);
        fill(helper, top, 8000);

        helper.succeedWhen(() -> {
            int left = amount(helper, top);
            int below = amount(helper, bottom);
            int pipes = pipesnphysics$areaPipeContent(helper, 4, 6, 4);
            if (left + below + pipes != 8000) {
                helper.fail("fluid not conserved: " + left + " + " + below + " + pipes " + pipes);
            }
            if (left != 0) helper.fail("upper tank still holds " + left + " mB");
        });
    }

    /**
     * Tank above an open-ended pipe pointing down: the fluid must spill out into
     * the world (the tank drains and a water block appears below the opening).
     */
    @GameTest(template = "gravity/open_pipe", templateNamespace = PipesNPhysics.ID, timeoutTicks = 600)
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
    @GameTest(template = "piping/open_end", templateNamespace = PipesNPhysics.ID, timeoutTicks = 600)
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
    @GameTest(template = "piping/open_end", templateNamespace = PipesNPhysics.ID, timeoutTicks = 600)
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
     * Conservation: a spill must never MINT a block. With only 500 mB of network fluid — less than
     * one source's 1000 mB — the open end's buffer can hold it but must NOT place a source block, or
     * fluid is created from nothing (the user's "placed a block but only took ~500 mB" duplication).
     */
    @GameTest(template = "piping/open_end", templateNamespace = PipesNPhysics.ID, timeoutTicks = 300)
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
    @GameTest(template = "suck_from_cauldron", templateNamespace = PipesNPhysics.ID, timeoutTicks = 400)
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
    @GameTest(template = "suck_from_cauldron", templateNamespace = PipesNPhysics.ID, timeoutTicks = 400)
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
    @GameTest(template = "suck_from_cauldron", templateNamespace = PipesNPhysics.ID, timeoutTicks = 400)
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
    @GameTest(template = "suck_from_cauldron", templateNamespace = PipesNPhysics.ID, timeoutTicks = 400)
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
    @GameTest(template = "gravity/long_equalization", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
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
    @GameTest(template = "suck_from_cauldron", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
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
    @GameTest(template = "suck_from_cauldron", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
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
     * The solve must stay READ-ONLY at an open mouth. A foreign fluid's pass (here lava — it holds
     * the larger volume, so it runs first) must never probe the mouth's Create handler: doing so runs
     * OpenEndedPipe's spill-collision reaction, turning the mouth's water source into stone, straight
     * out of a supposedly read-only solve. Fill the only tank with lava, face the mouth at a water
     * source, and solve repeatedly — the water must survive every pass.
     */
    @GameTest(template = "suck_from_cauldron", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
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
    @GameTest(template = "suck_from_cauldron", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
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
     * A multiblock tank's pipe connection can be far from an edited cell — outside findSeed's one-block
     * ring — so a break/place on a far corner would never wake the settled network. Build a 3-tall tank
     * whose base sits beside a pipe and edit its TOP cell (two blocks up); the wake must walk the whole
     * tank footprint and mark the base pipe URGENT.
     */
    @GameTest(template = "suck_from_cauldron", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void tankEditFarFromPipeWakesNetwork(GameTestHelper helper) {
        BlockPos bottomRel = new BlockPos(2, 1, 0);       // beside the pipe at (1,1,0)
        BlockPos topRel = new BlockPos(2, 3, 0);          // two blocks up — out of findSeed's ring
        BlockPos pipeRel = new BlockPos(1, 1, 0);
        // The template ships a creative tank at the base slot; build a 3-tall REAL tank.
        helper.setBlock(bottomRel, AllBlocks.FLUID_TANK.get().defaultBlockState());
        helper.setBlock(new BlockPos(2, 2, 0), AllBlocks.FLUID_TANK.get().defaultBlockState());
        helper.setBlock(topRel, AllBlocks.FLUID_TANK.get().defaultBlockState());

        helper.runAfterDelay(5, () -> {
            var level = helper.getLevel();
            if (!(level.getBlockEntity(helper.absolutePos(bottomRel)) instanceof FluidTankBlockEntity tank)
                    || tank.getControllerBE() == null
                    || ((FluidTankAccessor) (Object) tank.getControllerBE()).pipesnphysics$getHeight() < 3) {
                helper.fail("the 3-tall tank did not assemble into one controller");
                return;
            }
            BlockPos pipe = helper.absolutePos(pipeRel);
            NetworkEditHandler.wakeThroughTank(level, helper.absolutePos(topRel));
            if (!EngineTickHandler.hasPendingUrgent(level, pipe)) {
                helper.fail("editing a far multiblock-tank cell did not wake the pipe at its base");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Two pipe runs whose ONLY connection is a shared tank must build as ONE network — fluid flows
     * run→tank→run through the reservoir. Splice a tank into the middle of a straight run: the far tank
     * is then reachable only THROUGH it, and the graph seeded from the near end must still contain it.
     * Before the fix a tank was a terminal node, so the two halves were independent networks (each
     * solving the tank's fill blind to the other — a full pass-through tank then wrongly reported
     * "destination full" on its inflow run, while the pipes visibly flowed).
     */
    @GameTest(template = "gravity/long_equalization", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void tankCouplesTwoRunsIntoOneNetwork(GameTestHelper helper) {
        BlockPos midTank = new BlockPos(0, 1, 5); // spliced into the straight glass run
        BlockPos seed = new BlockPos(0, 1, 1);    // a pipe near one end
        BlockPos farTank = new BlockPos(0, 1, 9); // reachable only through the mid tank
        helper.setBlock(midTank, AllBlocks.FLUID_TANK.get().defaultBlockState());
        helper.runAfterDelay(4, () -> {
            Graph graph = GraphBuilder.build(helper.getLevel(), helper.absolutePos(seed));
            boolean reachesFar = graph.nodes().stream()
                    .anyMatch(n -> n.pos().equals(helper.absolutePos(farTank)));
            if (!reachesFar) {
                helper.fail("a tank between two runs split the network — the far tank is unreachable "
                        + "(the graph has " + graph.nodes().size() + " nodes)");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * The goggle "Head left" readout must exist on BOTH sides of a working pump —
     * including when the suction run contains a junction with a dead-end stub,
     * which makes the suction cells junction NODES rather than edge interiors.
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void headLeftShowsOnBothPumpSides(GameTestHelper helper) {
        BlockPos suctionPipe = new BlockPos(1, 1, 1);
        BlockPos stubPipe = new BlockPos(1, 2, 1);
        BlockPos pushPipe = new BlockPos(3, 1, 1);

        var pipe = AllBlocks.FLUID_PIPE.get();
        helper.setBlock(stubPipe, pipeState(pipe, Direction.DOWN));
        helper.setBlock(suctionPipe, pipeState(pipe,
                Direction.EAST, Direction.WEST,
                Direction.UP));
        fill(helper, new BlockPos(0, 1, 1), 4000);

        // Poll until the pump's kinetics have spun up and the readout is stable, rather
        // than racing the creative motor at a fixed tick (see the idle-suction test).
        helper.succeedWhen(() -> {
            var suction = PipeProbe.probe(
                    helper.getLevel(), helper.absolutePos(suctionPipe));
            var stub = PipeProbe.probe(
                    helper.getLevel(), helper.absolutePos(stubPipe));
            var push = PipeProbe.probe(
                    helper.getLevel(), helper.absolutePos(pushPipe));
            if (!push.hasHeadroom()) helper.fail("push side has no head-left value" + dump(helper));
            if (!suction.hasHeadroom()) helper.fail("suction junction has no head-left value" + dump(helper));
            if (!stub.hasHeadroom()) helper.fail("suction stub has no head-left value" + dump(helper));
            if (suction.headroomBlocks() < 1) {
                helper.fail("suction side head-left should include the pump boost, got "
                        + suction.headroomBlocks() + dump(helper));
            }
            if (stub.headTotalBlocks() < stub.headroomBlocks() + 0.2f) {
                helper.fail("stub sits above the supply surface, so its budget must exceed "
                        + "what is left: total=" + stub.headTotalBlocks()
                        + " left=" + stub.headroomBlocks() + dump(helper));
            }
            double suctionLimit = PipesNPhysicsConfig.SUCTION_LIMIT.get();
            if (!stub.hasSuctionMargin()
                    || stub.suctionMarginBlocks() <= 0
                    || stub.suctionMarginBlocks() >= suctionLimit) {
                helper.fail("stub hangs above the supply surface and must report a suction "
                        + "margin below the limit, got "
                        + (stub.hasSuctionMargin() ? stub.suctionMarginBlocks() : null)
                        + dump(helper));
            }
        });
    }

    /**
     * A gravity siphon whose crest rises more than the suction limit above the supply surface
     * BREAKS: the arch is a CREST-blocked edge carrying no flow. The goggle must then report the
     * air break as its reason and NOT a positive "air break in N blocks" margin — that margin is
     * recomputed from the display heads and can read a small POSITIVE value right at the threshold,
     * flatly contradicting the "air break over the crest" line the same run shows (the reported
     * "air break in 0.82 blocks on a dead pipe"). Builds its own tall arch so the crest height is
     * under our control, independent of any template's geometry.
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void airBrokenCrestReportsNoSuctionMargin(GameTestHelper helper) {
        int crestY = (int) Math.ceil(PipesNPhysicsConfig.SUCTION_LIMIT.get()) + 3; // safely above the limit
        BlockPos supply = new BlockPos(0, 1, 0);
        BlockPos sink = new BlockPos(2, 1, 0);
        BlockPos riserProbe = new BlockPos(0, crestY - 2, 1);

        helper.runAfterDelay(10, () -> pipesnphysics$placeBrokenSiphon(helper, crestY, supply, sink));

        // Fill AFTER the freshly placed tank BEs have ticked once, so the handler is ready — then a
        // driving surface difference (full supply, empty sink) gives the pre-gate solve the flow the
        // crest gate cuts (a balanced pair would idle for an unrelated reason).
        helper.runAfterDelay(20, () -> {
            fill(helper, supply, 8000);
            drain(helper, sink);
        });

        helper.succeedWhen(() -> {
            BlockPos seedAbs = helper.absolutePos(riserProbe);
            Graph graph = GraphBuilder.build(helper.getLevel(), seedAbs);
            Solution solution = FlowSolver.solve(helper.getLevel(), graph);
            Edge arch = graph.edges().stream()
                    .filter(e -> e.pipes().contains(seedAbs))
                    .findFirst().orElse(null);
            if (arch == null) {
                helper.fail("no arch edge through the riser" + dump(helper, riserProbe));
                return;
            }
            if (!solution.blockedEdges().contains(arch.index())
                    || solution.edgeReasons().get(arch.index()) != Solution.Reason.CREST) {
                helper.fail("the arch should be CREST-blocked, got reason "
                        + solution.edgeReasons().get(arch.index())
                        + " supply=" + amount(helper, supply) + " sink=" + amount(helper, sink)
                        + dump(helper, riserProbe));
                return;
            }
            PipeStatusPayload probe = PipeProbe.probe(helper.getLevel(), seedAbs);
            if (probe.statusDetail() != PipeStatusPayload.DETAIL_CREST) {
                helper.fail("probe should read the air-break reason, got detail "
                        + probe.statusDetail() + dump(helper, riserProbe));
                return;
            }
            if (probe.hasSuctionMargin()) {
                helper.fail("a broken air-break run must not report a positive suction margin, got "
                        + probe.suctionMarginBlocks() + dump(helper, riserProbe));
            }
        });
    }

    /**
     * A broken siphon is a BAROMETER: the level renderer must fill the source leg with the static
     * column suction holds — up to {@code source_surface + SUCTION_LIMIT} — then leave an air gap over
     * the crest, so the water visibly climbs to the break. Reproduces "the source pipes render empty":
     * the resting waterline was flattened to the LOWER (sink) surface, hiding the source column. Checks
     * the LEVEL path ({@code apply(...,true)} → {@link PipeLevelData}): a source-riser cell well ABOVE
     * the source surface is stamped (impossible under the min flatten), and the crest cell stays dry.
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200, batch = "levelRender")
    public static void brokenSiphonRendersBarometricSourceColumn(GameTestHelper helper) {
        int crestY = (int) Math.ceil(PipesNPhysicsConfig.SUCTION_LIMIT.get()) + 3;
        BlockPos supply = new BlockPos(0, 1, 0);
        BlockPos sink = new BlockPos(2, 1, 0);

        helper.runAfterDelay(10, () -> pipesnphysics$placeBrokenSiphon(helper, crestY, supply, sink));
        // BOTH tanks hold water (supply fuller, to drive the pre-gate flow the crest cuts) — like the
        // real report — so each leg is a barometer and the source column fills right up to the break,
        // not tapering toward an empty far end.
        helper.runAfterDelay(20, () -> {
            fill(helper, supply, 8000);
            fill(helper, sink, 1000);
        });
        // Prime the legs as a previously-running siphon would have left them: full to the crest.
        // Physically the break then collapses only the part above the barometric reach — the legs
        // within surface + SUCTION_LIMIT hold (a vacuum gap forms at the crest), which is exactly
        // what the settle pass must reproduce with the real stored volume.
        helper.runAfterDelay(25, () -> {
            Level level = helper.getLevel();
            for (int y = 1; y <= crestY; y++) {
                for (int x : new int[] {0, 2}) {
                    PipeStore.Store cell = PipeStore.at(level, helper.absolutePos(new BlockPos(x, y, 1)));
                    if (cell != null) {
                        cell.insert(new FluidStack(Fluids.WATER, PipeStore.capacityMb()), PipeStore.capacityMb());
                        cell.flush();
                    }
                }
            }
            PipeStore.Store arch = PipeStore.at(level, helper.absolutePos(new BlockPos(1, crestY, 1)));
            if (arch != null) {
                arch.insert(new FluidStack(Fluids.WATER, PipeStore.capacityMb()), PipeStore.capacityMb());
                arch.flush();
            }
        });
        helper.succeedWhen(() -> {
            Level level = helper.getLevel();
            BlockPos seedAbs = helper.absolutePos(new BlockPos(0, crestY - 2, 1));
            Graph graph = GraphBuilder.build(level, seedAbs);
            Solution solution = FlowSolver.solve(level, graph);
            Edge arch = graph.edges().stream()
                    .filter(e -> e.pipes().contains(seedAbs)).findFirst().orElse(null);
            if (arch == null || !solution.blockedEdges().contains(arch.index())
                    || solution.edgeReasons().get(arch.index()) != Solution.Reason.CREST) {
                helper.fail("arch not crest-blocked" + dump(helper, new BlockPos(0, crestY - 2, 1)));
                return;
            }
            Double supplyHead = graph.nodes().stream()
                    .filter(n -> n.pos().equals(helper.absolutePos(supply)))
                    .findFirst().map(n -> solution.nodeHeads().get(n.index())).orElse(null);
            if (supplyHead == null) { helper.fail("no supply head" + dump(helper, supply)); return; }
            double reach = PipesNPhysicsConfig.SUCTION_LIMIT.get();

            // The crest cell sits above surface + reach — the air-break gap must drain dry
            // (level-spreading leaves at most a dregs film on a flat crest arch).
            if (cellMb(level, helper.absolutePos(new BlockPos(0, crestY, 1))) > 8) {
                helper.fail("the crest cell should drain to the dry air-break gap, but holds "
                        + cellMb(level, helper.absolutePos(new BlockPos(0, crestY, 1))) + " mB");
            }
            // A source-riser cell WELL above the source surface yet within the suction reach must
            // KEEP its barometric column — the settle pass may only collapse the part above reach.
            boolean sawColumnAboveSurface = false;
            for (int y = 2; y < crestY; y++) {
                BlockPos abs = helper.absolutePos(new BlockPos(0, y, 1));
                double cellBottom = abs.getY() - 0.5;
                if (cellBottom <= supplyHead + 0.5) continue;          // still at/below the surface
                if (cellBottom > supplyHead + reach - 1) continue;     // near/above the barometric reach
                if (cellMb(level, abs) > 0) {
                    sawColumnAboveSurface = true;
                    break;
                }
            }
            if (!sawColumnAboveSurface) {
                helper.fail("no source-riser cell above the surface kept the barometric column — "
                        + "the settle drained the leg (surface=" + supplyHead + " reach=" + reach + ")"
                        + dump(helper, new BlockPos(0, crestY - 2, 1)));
            }
        });
    }

    /** The stored mB in the pipe cell at an absolute position, 0 when empty or not a pipe. */
    private static int cellMb(Level level, BlockPos absolutePos) {
        PipeStore.Store store = PipeStore.at(level, absolutePos);
        return store == null ? 0 : store.amount();
    }

    /** Builds the tall gravity siphon used by the air-break tests: two side-drawn tanks an x-gap apart,
     *  a run that climbs at z=1, bridges the crest across x, and drops into the sink. */
    private static void pipesnphysics$placeBrokenSiphon(GameTestHelper helper, int crestY,
                                                        BlockPos supply, BlockPos sink) {
        var pipe = AllBlocks.FLUID_PIPE.get();
        var tank = AllBlocks.FLUID_TANK.get();
        // Clear the template's own machine out of the working volume first.
        for (int x = 0; x <= 4; x++)
            for (int y = 1; y <= crestY + 1; y++)
                for (int z = 0; z <= 1; z++)
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());

        helper.setBlock(supply, tank.defaultBlockState());
        helper.setBlock(sink, tank.defaultBlockState());

        // Draw from the SIDE of each tank at its own level (a pipe leaving the TOP of a full 1-tall
        // tank sits exactly at the surface lip and reads "can't draw").
        helper.setBlock(new BlockPos(0, 1, 1), pipeState(pipe, Direction.NORTH, Direction.UP));
        helper.setBlock(new BlockPos(2, 1, 1), pipeState(pipe, Direction.NORTH, Direction.UP));
        helper.setBlock(new BlockPos(0, crestY, 1), pipeState(pipe, Direction.DOWN, Direction.EAST));
        helper.setBlock(new BlockPos(1, crestY, 1), pipeState(pipe, Direction.WEST, Direction.EAST));
        helper.setBlock(new BlockPos(2, crestY, 1), pipeState(pipe, Direction.WEST, Direction.DOWN));
        for (int y = 2; y < crestY; y++) {
            helper.setBlock(new BlockPos(0, y, 1), pipeState(pipe, Direction.UP, Direction.DOWN));
            helper.setBlock(new BlockPos(2, y, 1), pipeState(pipe, Direction.UP, Direction.DOWN));
        }
    }

    /**
     * A powered pump with nothing to pull (empty source tank) moves no fluid, yet
     * "Head left" must still read on BOTH sides: the push side anchored by the
     * downstream tank, and the suction side seeded with the pump's waiting boost
     * so the player can read the budget before any fluid arrives.
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void headLeftShowsOnIdleSuctionSide(GameTestHelper helper) {
        BlockPos suctionPipe = new BlockPos(1, 1, 1);
        BlockPos pushPipe = new BlockPos(3, 1, 1);
        BlockPos suctionTank = new BlockPos(0, 1, 1);
        BlockPos pushTank = new BlockPos(4, 1, 1);

        // Set up the empty source / full downstream AFTER the pump's kinetics and FACING
        // have settled. Filling at tick 0 races the spin-up, whose transient facing flips
        // slosh (and can drain) the downstream tank before it stabilizes; then POLL for the
        // readout so a slightly-late spin-up still passes.
        helper.runAfterDelay(60, () -> {
            drain(helper, suctionTank);
            drain(helper, pushTank);
            fill(helper, pushTank, 4000);

            helper.succeedWhen(() -> {
                var suction = PipeProbe.probe(helper.getLevel(), helper.absolutePos(suctionPipe));
                var push = PipeProbe.probe(helper.getLevel(), helper.absolutePos(pushPipe));
                if (!push.hasHeadroom()) helper.fail("push side has no head-left value" + dump(helper));
                if (!suction.hasHeadroom()) {
                    helper.fail("idle suction side has no head-left value" + dump(helper));
                }
                if (suction.headroomBlocks() < 1) {
                    helper.fail("idle suction head-left should carry the pump boost, got "
                            + suction.headroomBlocks() + dump(helper));
                }
                if (suction.headTotalBlocks() < suction.headroomBlocks() - 0.01f) {
                    helper.fail("budget can never be smaller than what is left: total="
                            + suction.headTotalBlocks() + " left=" + suction.headroomBlocks()
                            + dump(helper));
                }
            });
        });
    }

    /**
     * Two powered pumps in series with nothing to pull: the dry suction side must
     * read the SUM of both pump boosts while the delivery stretch past both pumps
     * reads only what remains — head-left accumulates across boosters before any
     * fluid arrives. Pump facing is read at runtime because Create re-orients
     * pumps to match their rotation once kinetics settle.
     */
    @GameTest(template = "piping/double_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void headLeftAccumulatesAcrossIdlePumpsInSeries(GameTestHelper helper) {
        helper.runAfterDelay(10, () -> {
            List<BlockPos> pumps = new ArrayList<>();
            List<BlockPos> tanks = new ArrayList<>();
            for (int x = 0; x < 12; x++) {
                for (int y = 0; y < 4; y++) {
                    for (int z = 0; z < 4; z++) {
                        BlockPos rel = new BlockPos(x, y, z);
                        var state = helper.getBlockState(rel);
                        if (state.getBlock() instanceof PumpBlock) pumps.add(rel);
                        else if (state.is(AllBlocks.FLUID_TANK.get())) tanks.add(rel);
                    }
                }
            }
            if (pumps.size() != 2 || tanks.size() != 2) {
                helper.fail("expected 2 pumps and 2 tanks, found " + pumps.size() + "/" + tanks.size());
                return;
            }
            pumps.sort(Comparator.comparingInt(BlockPos::getX));
            tanks.sort(Comparator.comparingInt(BlockPos::getX));

            boolean west = helper.getBlockState(pumps.get(0))
                    .getValue(PumpBlock.FACING) == Direction.WEST;
            BlockPos pushTank = west ? tanks.get(0) : tanks.get(1);
            BlockPos suctionTank = west ? tanks.get(1) : tanks.get(0);
            BlockPos suctionPipe = suctionTank.relative(west ? Direction.WEST : Direction.EAST);
            BlockPos deliveryPipe = pushTank.relative(west ? Direction.EAST : Direction.WEST);
            BlockPos betweenPumps = new BlockPos(
                    (pumps.get(0).getX() + pumps.get(1).getX()) / 2,
                    pumps.get(0).getY(), pumps.get(0).getZ());
            fill(helper, pushTank, 4000);

            helper.runAfterDelay(3, () -> {
                if (amount(helper, pushTank) != 4000) {
                    helper.fail("network was expected to stay idle" + dump(helper, betweenPumps));
                }
                var suction = PipeProbe.probe(helper.getLevel(), helper.absolutePos(suctionPipe));
                var between = PipeProbe.probe(helper.getLevel(), helper.absolutePos(betweenPumps));
                var delivery = PipeProbe.probe(helper.getLevel(), helper.absolutePos(deliveryPipe));
                if (!suction.hasHeadroom() || !between.hasHeadroom() || !delivery.hasHeadroom()) {
                    helper.fail("head-left missing on an idle series segment" + dump(helper, betweenPumps));
                }
                if (suction.headroomBlocks() < delivery.headroomBlocks() * 1.5f) {
                    helper.fail("suction head-left should stack both pump boosts: suction="
                            + suction.headroomBlocks() + " delivery=" + delivery.headroomBlocks()
                            + dump(helper, betweenPumps));
                }
                helper.succeed();
            });
        });
    }

    /**
     * A pump pushing into a tank that has no room left must report the stall's
     * culprit: the goggle detail line reads "destination is full".
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void stalledPipeReportsSinkFull(GameTestHelper helper) {
        BlockPos pushPipe = new BlockPos(3, 1, 1);
        fill(helper, new BlockPos(0, 1, 1), 8000);
        fill(helper, new BlockPos(4, 1, 1), 8000);

        // Poll: the run first PRIMES (real fluid filling the pipe reads as flow), then stalls
        // against the full sink once the column is packed.
        helper.succeedWhen(() -> {
            var push = PipeProbe.probe(helper.getLevel(), helper.absolutePos(pushPipe));
            if (push.status() != PipeStatusPayload.STATUS_STALLED) {
                helper.fail("expected STALLED on the push pipe, got status "
                        + push.status() + dump(helper));
            }
            if (push.statusDetail() != PipeStatusPayload.DETAIL_SINK_FULL) {
                helper.fail("expected SINK_FULL detail, got " + push.statusDetail() + dump(helper));
            }
        });
    }

    /**
     * A dry pipe whose (running) pump has nothing to pull must name the real culprit, not
     * leave a bare "No flow" beside a healthy-looking lift bar. With the source emptied the
     * powered pump just spins at zero flow — every branch idle and unflagged — so the probe
     * reports the pump as starved. (A pump pressing a full sink stalls; one that can't lift
     * is NO_HEAD; a valved one is blocked — only starvation reads as plain idle + dry.)
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void dryPipeReportsStarvedPump(GameTestHelper helper) {
        BlockPos pushPipe = new BlockPos(3, 1, 1);
        drain(helper, new BlockPos(0, 1, 1));
        drain(helper, new BlockPos(4, 1, 1));

        helper.runAfterDelay(5, () -> {
            var push = PipeProbe.probe(helper.getLevel(), helper.absolutePos(pushPipe));
            if (push.status() != PipeStatusPayload.STATUS_NO_FLOW) {
                helper.fail("expected NO_FLOW on the dry push pipe, got status "
                        + push.status() + dump(helper));
                return;
            }
            if (push.statusDetail() != PipeStatusPayload.DETAIL_PUMP_STARVED) {
                helper.fail("expected PUMP_STARVED detail (running pump, empty source), got "
                        + push.statusDetail() + dump(helper));
                return;
            }
            helper.succeed();
        });
    }

    /**
     * A running pump whose OUTPUT faces a solid block has nowhere to deliver - it is NOT short of
     * supply. The dry run must name the blocked output, not send the player to the source: capping
     * the push side and reading the intake pipe must report PUMP_NO_OUTPUT, the discriminator being
     * the missing push-side connection (contrast {@link #dryPipeReportsStarvedPump}, same dry pump
     * but an OPEN output, which stays PUMP_STARVED). This was the "can't pull its supply" misreport.
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void deadEndedPumpReportsNoOutput(GameTestHelper helper) {
        BlockPos suctionPipe = new BlockPos(1, 1, 1);
        BlockPos pushPipe = new BlockPos(3, 1, 1);
        drain(helper, new BlockPos(0, 1, 1));
        drain(helper, new BlockPos(4, 1, 1));
        helper.setBlock(pushPipe, Blocks.STONE);

        helper.runAfterDelay(5, () -> {
            var suction = PipeProbe.probe(helper.getLevel(), helper.absolutePos(suctionPipe));
            if (suction.status() != PipeStatusPayload.STATUS_NO_FLOW) {
                helper.fail("expected NO_FLOW on the intake pipe, got status "
                        + suction.status() + dump(helper));
                return;
            }
            if (suction.statusDetail() != PipeStatusPayload.DETAIL_PUMP_NO_OUTPUT) {
                helper.fail("expected PUMP_NO_OUTPUT detail (pump output capped by a solid block), got "
                        + suction.statusDetail() + dump(helper));
                return;
            }
            helper.succeed();
        });
    }

    /**
     * An unpowered pump acts as a closed valve; pipes feeding it must report
     * BLOCKED with the pump named as the culprit. The pump is unpowered by
     * removing the template's creative motor once kinetics have settled.
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void blockedPipeReportsUnpoweredPump(GameTestHelper helper) {
        BlockPos suctionPipe = new BlockPos(1, 1, 1);
        fill(helper, new BlockPos(0, 1, 1), 4000);

        helper.runAfterDelay(3, () -> {
            List<BlockPos> motors = new ArrayList<>();
            for (int x = 0; x < 8; x++) {
                for (int y = 0; y < 4; y++) {
                    for (int z = 0; z < 4; z++) {
                        BlockPos rel = new BlockPos(x, y, z);
                        if (helper.getBlockState(rel).is(AllBlocks.CREATIVE_MOTOR.get())) {
                            motors.add(rel);
                        }
                    }
                }
            }
            if (motors.isEmpty()) {
                helper.fail("no creative motor found to unpower the pump");
                return;
            }
            motors.forEach(motor -> helper.setBlock(motor, Blocks.AIR));

            helper.runAfterDelay(5, () -> {
                var suction = PipeProbe.probe(helper.getLevel(), helper.absolutePos(suctionPipe));
                if (suction.status() != PipeStatusPayload.STATUS_BLOCKED) {
                    helper.fail("expected BLOCKED on the suction pipe, got status "
                            + suction.status() + dump(helper));
                }
                if (suction.statusDetail() != PipeStatusPayload.DETAIL_PUMP_OFF) {
                    helper.fail("expected PUMP_OFF detail, got "
                            + suction.statusDetail() + dump(helper));
                }
                helper.succeed();
            });
        });
    }

    /**
     * Fluid must never cross a hydraulic barrier. Two unpowered pumps (closed valves)
     * split one discovered graph into two islands; each island has an elevated full
     * source over a near tank. Island A's near tank is FULL (its source has nowhere
     * local to put its surplus); island B's near tank is EMPTY (its source can fill
     * it). The greedy transfer planner used to spill island A's stuck surplus into
     * island B's open sink — teleporting fluid through the closed pumps. Sources may
     * now pair only with sinks in the same active-branch component, so nothing crosses.
     */
    @GameTest(template = "piping/double_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void fluidDoesNotTeleportAcrossClosedBarrier(GameTestHelper helper) {
        helper.runAfterDelay(10, () -> {
            List<BlockPos> baseTanks = new ArrayList<>();
            List<BlockPos> motors = new ArrayList<>();
            for (int x = 0; x <= 9; x++) for (int y = 0; y <= 1; y++) for (int z = 0; z <= 2; z++) {
                BlockPos rel = new BlockPos(x, y, z);
                var st = helper.getBlockState(rel);
                if (st.is(AllBlocks.FLUID_TANK.get())) baseTanks.add(rel);
                else if (st.is(AllBlocks.CREATIVE_MOTOR.get())) motors.add(rel);
            }
            if (baseTanks.size() != 2) { helper.fail("expected 2 base tanks, found " + baseTanks); return; }
            if (motors.isEmpty()) { helper.fail("no motors found to unpower the pumps"); return; }

            // Unpower both pumps so each is a closed check valve. The whole pipe line
            // is still ONE discovered graph (BFS walks through pump cells), but the
            // solver drops the off-pump branches, splitting it into two islands.
            motors.forEach(m -> helper.setBlock(m, Blocks.AIR));

            baseTanks.sort(Comparator.comparingInt(BlockPos::getX));
            BlockPos nearA = baseTanks.get(0);   // island A near tank, kept FULL (clamped sink)
            BlockPos nearB = baseTanks.get(1);   // island B near tank, kept EMPTY (open sink)
            // Elevated sources join the line through the horizontal pipe next to each
            // tank (a stub above a PIPE, not above the tank — a tank is a graph leaf).
            BlockPos pipeA = nearA.east();
            BlockPos pipeB = nearB.west();
            if (isNotPipe(helper, pipeA) || isNotPipe(helper, pipeB)) {
                helper.fail("expected a pipe beside each base tank (A=" + pipeA + " B=" + pipeB + ")");
                return;
            }
            BlockPos srcA = pipeA.above(2);
            BlockPos srcB = pipeB.above(2);

            var pipe = AllBlocks.FLUID_PIPE.get();
            helper.setBlock(pipeA.above(), pipeState(pipe, Direction.UP, Direction.DOWN));
            helper.setBlock(pipeB.above(), pipeState(pipe, Direction.UP, Direction.DOWN));
            helper.setBlock(srcA, AllBlocks.FLUID_TANK.get().defaultBlockState());
            helper.setBlock(srcB, AllBlocks.FLUID_TANK.get().defaultBlockState());

            helper.runAfterDelay(5, () -> {
                drain(helper, nearA); fill(helper, nearA, 8000);   // island A: source over a FULL tank
                fill(helper, srcA, 8000);
                drain(helper, nearB);                              // island B: source over an EMPTY tank
                fill(helper, srcB, 8000);

                helper.runAfterDelay(5, () -> {
                    var level = helper.getLevel();
                    var graph = GraphBuilder.build(level, helper.absolutePos(pipeA));
                    var sol = FlowSolver.solve(level, graph);

                    Set<BlockPos> islandA = Set.of(
                            helper.absolutePos(nearA), helper.absolutePos(srcA));
                    Set<BlockPos> islandB = Set.of(
                            helper.absolutePos(nearB), helper.absolutePos(srcB));

                    boolean withinB = false;
                    for (var t : sol.transfers()) {
                        boolean cross = (islandA.contains(t.from()) && islandB.contains(t.to()))
                                || (islandB.contains(t.from()) && islandA.contains(t.to()));
                        if (cross) {
                            helper.fail("fluid teleported across the closed pumps: "
                                    + t.from().toShortString() + " -> " + t.to().toShortString()
                                    + dump(helper, pipeA));
                            return;
                        }
                        if (islandB.contains(t.from()) && islandB.contains(t.to())) withinB = true;
                    }
                    if (!withinB) {
                        helper.fail("island B should move its source into its empty tank, but planned "
                                + sol.transfers().size() + " transfers" + dump(helper, pipeA));
                        return;
                    }
                    helper.succeed();
                });
            });
        });
    }

    private static boolean isNotPipe(GameTestHelper helper, BlockPos rel) {
        return FluidPropagator.getPipe(
                helper.getLevel(), helper.absolutePos(rel)) == null;
    }

    /**
     * A pump pushing a viscous fluid (lava) down a long run is friction-limited:
     * its goggle load breakdown must report a friction factor below 1, and the
     * shipped factors must reconstruct the displayed load bar exactly
     * (load = headFactor · frictionFactor = rate / cap). Pump facing settles with
     * its rotation, so the suction tank and run side are chosen at runtime.
     */
    @GameTest(template = "piping/charging_max_range", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void pumpLoadBreakdownExplainsFrictionLimit(GameTestHelper helper) {
        helper.runAfterDelay(5, () -> {
            BlockPos pump = null;
            List<BlockPos> tanks = new ArrayList<>();
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 5; y++) {
                    for (int z = 0; z < 4; z++) {
                        BlockPos rel = new BlockPos(x, y, z);
                        var state = helper.getBlockState(rel);
                        if (state.getBlock() instanceof PumpBlock) pump = rel;
                        else if (state.is(AllBlocks.FLUID_TANK.get())) tanks.add(rel);
                    }
                }
            }
            if (pump == null || tanks.size() != 2) {
                helper.fail("template scan found pump=" + pump + " tanks=" + tanks.size());
                return;
            }
            tanks.sort(Comparator.comparingInt(BlockPos::getX));
            BlockPos pumpPos = pump;

            Direction facing = helper.getBlockState(pumpPos).getValue(PumpBlock.FACING);
            // Feed the suction side; the long run (most pipe cells) sits toward the
            // low-x tank, so the discharge is long only when the pump faces that way.
            BlockPos source = facing == Direction.WEST ? tanks.get(1) : tanks.get(0);
            boolean longDischarge = facing == Direction.WEST
                    ? pumpPos.getX() - tanks.get(0).getX() > tanks.get(1).getX() - pumpPos.getX()
                    : tanks.get(1).getX() - pumpPos.getX() > pumpPos.getX() - tanks.get(0).getX();
            // The template ships water-filled tanks; clear both so only the lava we
            // add is in play (a viscous fluid is what makes the long run friction-bound).
            drain(helper, tanks.get(0));
            drain(helper, tanks.get(1));
            fillFluid(helper, source, Fluids.LAVA, 8000);
            // Pre-prime every pipe with lava: at viscous trickle rates a cold 11-cell line takes
            // thousands of ticks to pack, and the load breakdown is a STEADY-STATE identity.
            for (int x = 0; x < 16; x++)
                for (int y = 0; y < 5; y++)
                    for (int z = 0; z < 4; z++) {
                        PipeStore.Store store = PipeStore.at(
                                helper.getLevel(), helper.absolutePos(new BlockPos(x, y, z)));
                        if (store != null) {
                            store.insert(new FluidStack(Fluids.LAVA, PipeStore.capacityMb()),
                                    PipeStore.capacityMb());
                            store.flush();
                        }
                    }

            // Poll: the reconstruction holds once the line reaches its steady operating point.
            helper.succeedWhen(() -> {
                var probe = PipeProbe.probe(helper.getLevel(), helper.absolutePos(pumpPos));
                if (!probe.hasPumpLoad()) {
                    helper.fail("a running pump reported no load breakdown" + dump(helper, pumpPos));
                    return;
                }
                float speed = helper.getLevel().getBlockEntity(helper.absolutePos(pumpPos))
                        instanceof KineticBlockEntity k ? Math.abs(k.getSpeed()) : 0;
                double cap = speed * PipesNPhysicsConfig.PUMP_FLOW_PER_RPM.get();
                double headSupplied = speed * PipesNPhysicsConfig.PUMP_HEAD_PER_RPM.get();
                if (cap <= 0 || headSupplied <= 0) {
                    helper.fail("pump is not spinning, speed=" + speed + dump(helper, pumpPos));
                    return;
                }
                double headFactor = (headSupplied - probe.pumpHeadAgainst()) / headSupplied;
                double loadCalc = headFactor * probe.pumpFrictionFactor();
                double loadBar = probe.mbPerTick() / cap;
                if (Math.abs(loadCalc - loadBar) > 1.0 / cap + 0.03) {
                    helper.fail("breakdown must reconstruct the load bar: head·friction="
                            + loadCalc + " bar=" + loadBar + dump(helper, pumpPos));
                    return;
                }
                if (longDischarge && probe.pumpFrictionFactor() >= 0.95f) {
                    helper.fail("lava down a long run should be friction-limited, factor="
                            + probe.pumpFrictionFactor() + dump(helper, pumpPos));
                }
            });
        });
    }

    /**
     * A moving run really holds its fluid: while the pump transfers, some pipe cell must store
     * water ({@link PipeFluidCell} content — the only thing the client renders), of the right
     * fluid. Verifies the transfer brigade fills the pipes it moves fluid through.
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void flowingPipesCarryRenderableFluid(GameTestHelper helper) {
        fill(helper, new BlockPos(0, 1, 1), 8000);

        helper.succeedWhen(() -> {
            Level level = helper.getLevel();
            for (BlockPos rel : new BlockPos[] {new BlockPos(1, 1, 1), new BlockPos(3, 1, 1)}) {
                PipeStore.Store store = PipeStore.at(level, helper.absolutePos(rel));
                if (store == null || store.amount() <= 0) continue;
                if (!FluidStack.isSameFluidSameComponents(store.fluid(), new FluidStack(Fluids.WATER, 1))) {
                    helper.fail("pipe cell stores the wrong fluid: " + store.fluid().getHoverName().getString());
                }
                return;
            }
            helper.fail("no pipe cell stores fluid while the pump is moving water" + dump(helper));
        });
    }

    /**
     * Two equal tanks joined by a submerged pipe equalize to zero net flow, yet the
     * connecting pipe is full of water and must keep rendering it — the render bridge
     * must show resting fluid below the surface, not blank the pipe when flow stops.
     * (Threshold matches {@link #tanksEqualizeAtEqualSurfaces}: travel time delays the
     * start of delivery, so check "mostly settled with the pipe full" rather than dead level.)
     */
    @GameTest(template = "gravity/simple_fluid_leveling", templateNamespace = PipesNPhysics.ID, timeoutTicks = 800)
    public static void restingFullPipesStillRenderFluid(GameTestHelper helper) {
        BlockPos left = new BlockPos(0, 3, 0);
        BlockPos right = new BlockPos(2, 3, 0);
        fill(helper, left, 8000);

        helper.succeedWhen(() -> {
            int a = amount(helper, left);
            int b = amount(helper, right);
            if (Math.abs(a - b) > 800) helper.fail("not equalized yet: " + a + " vs " + b);
            // Settled (near-zero flow) but the U-pipe under the tanks still HOLDS its water —
            // submerged cells below the waterline keep their real content at rest.
            boolean anyWet = false;
            for (int x = 0; x < 4 && !anyWet; x++)
                for (int y = 0; y < 4 && !anyWet; y++)
                    for (int z = 0; z < 2 && !anyWet; z++) {
                        anyWet = cellMb(helper.getLevel(), helper.absolutePos(new BlockPos(x, y, z))) > 0;
                    }
            if (!anyWet) helper.fail("equalized pipe lost its stored fluid" + dump(helper, left));
        });
    }

    /**
     * A running pump dead-headed by a solid block on its push side leaves only ONE reservoir on the
     * network (its supply tank). The SUBMERGED pull pipe between the full tank and the pump sits
     * below the tank's surface, so the settle pass must FILL it from the tank — the user's "no
     * fluid in the pipe though the head is there" now means real stored fluid, not a render stamp.
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void deadEndedPumpRendersSubmergedSupplyPipe(GameTestHelper helper) {
        BlockPos pullPipe = new BlockPos(1, 1, 1);
        fill(helper, new BlockPos(0, 1, 1), 8000);            // full source → pull pipe sits below it
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.STONE); // cap the output: one reservoir left

        helper.succeedWhen(() -> {
            if (cellMb(helper.getLevel(), helper.absolutePos(pullPipe)) <= 0) {
                helper.fail("submerged supply pipe of a dead-headed pump holds no fluid" + dump(helper));
            }
        });
    }

    /**
     * A horizontal run capped by a solid block ends in a dead-end pipe cell — a JUNCTION NODE. With
     * the tank surface above the run, the whole run INCLUDING that terminal cell must fill: the
     * junction cell is a one-cell buffer on its node, and the settle pass tops it up through the
     * adjacent edge cell, so the fluid no longer stops one cell short of the block. Here the pump
     * of the single_pump template is swapped for a plain pipe and the far cell capped, so the run
     * is tank -> pipe -> dead-end pipe -> stone with NO pump.
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200, batch = "levelRender")
    public static void deadEndJunctionCellRendersAgainstTheBlock(GameTestHelper helper) {
        fill(helper, new BlockPos(0, 1, 1), 8000);           // full tank -> run sits below its surface
        helper.setBlock(new BlockPos(2, 1, 1), AllBlocks.FLUID_PIPE.get().defaultBlockState()); // pump -> pipe
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.STONE); // cap the run: (2,1,1) becomes a dead-end junction

        helper.runAfterDelay(5, () -> {
            BlockPos deadEnd = new BlockPos(2, 1, 1);
            Graph graph = GraphBuilder.build(helper.getLevel(), helper.absolutePos(new BlockPos(1, 1, 1)));
            boolean hasJunction = graph.nodes().stream()
                    .anyMatch(n -> n.isJunction() && n.pos().equals(helper.absolutePos(deadEnd)));
            if (!hasJunction) {
                helper.fail("capped run did not classify (2,1,1) as a dead-end JUNCTION" + dump(helper));
                return;
            }
            helper.succeedWhen(() -> {
                if (cellMb(helper.getLevel(), helper.absolutePos(deadEnd)) <= 0) {
                    helper.fail("dead-end junction cell against the block holds no fluid — the run "
                            + "stops one cell short" + dump(helper));
                }
            });
        });
    }

    /**
     * The waterline INSIDE a settled pipe must match the tank it connects to: with the tank half
     * full, the pipe cells at the tank's base settle at the SAME surface elevation — the stored
     * fraction equals the tank surface over the cell, neither painted full nor left dry. The pump
     * is swapped for a plain pipe and the run capped, so it is a pure resting stub beside the tank.
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 400)
    public static void settledPipeMatchesTankWaterline(GameTestHelper helper) {
        BlockPos tank = new BlockPos(0, 1, 1);
        BlockPos cell = new BlockPos(1, 1, 1);
        helper.setBlock(new BlockPos(2, 1, 1), AllBlocks.FLUID_PIPE.get().defaultBlockState());
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.STONE);
        fill(helper, tank, 4000);

        helper.succeedWhen(() -> {
            Level level = helper.getLevel();
            int capacity = PipeStore.capacityMb();
            // The tank's current surface over the pipe cell's bottom, both measured from the tank
            // base (tank and run share one Y level in this template).
            double surface = amount(helper, tank) / 8000.0;
            double expected = Math.clamp(surface, 0.0, 1.0);
            double got = cellMb(level, helper.absolutePos(cell)) / (double) capacity;
            if (Math.abs(got - expected) > 0.2) {
                helper.fail("pipe waterline " + got + " does not match the tank surface "
                        + expected + dump(helper, tank));
            }
            if (got <= 0.05 || got >= 0.95) {
                helper.fail("half-full tank left the pipe " + (got <= 0.05 ? "dry" : "painted full")
                        + " (" + got + ")");
            }
        });
    }

    /**
     * An open pipe mouth ABOVE the connected fluid, with the run at REST, must hold no fluid at the
     * top of the riser: the mouth is a vent (a spill/intake threshold), not a surface fluid climbs
     * to. Pre-fills the riser cells (as a dying flow would leave them) and asserts the settle pass
     * drains the mouth cell — the fluid falls back down instead of hanging at the opening, so
     * nothing renders (or drips particles) at the open end.
     */
    @GameTest(template = "suck_from_cauldron", templateNamespace = PipesNPhysics.ID, timeoutTicks = 400)
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
     * CONSERVATION under transfer: from the first tick to the settled end state, the water in the
     * two tanks plus the water stored in the pipes must always sum to what was poured in — the
     * brigade may neither mint nor void a single mB while it primes, flows, and settles.
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 600)
    public static void fluidConservedThroughPriming(GameTestHelper helper) {
        BlockPos source = new BlockPos(0, 1, 1);
        BlockPos sink = new BlockPos(4, 1, 1);
        fill(helper, source, 8000);

        helper.succeedWhen(() -> {
            int total = amount(helper, source) + amount(helper, sink)
                    + pipesnphysics$areaPipeContent(helper, 6, 4, 4);
            if (total != 8000) helper.fail("fluid not conserved: tanks+pipes=" + total);
            if (amount(helper, source) > 0) helper.fail("source not fully drained yet");
        });
    }

    /** Sum of all pipe-cell contents within the template-relative area. */
    private static int pipesnphysics$areaPipeContent(GameTestHelper helper, int sx, int sy, int sz) {
        int sum = 0;
        for (int x = 0; x < sx; x++)
            for (int y = 0; y < sy; y++)
                for (int z = 0; z < sz; z++) {
                    sum += cellMb(helper.getLevel(), helper.absolutePos(new BlockPos(x, y, z)));
                }
        return sum;
    }

    /**
     * TRAVEL TIME: the sink must not receive its first drop before the pipe feeding it is full —
     * fluid physically resides in the run while it primes, so delivery begins exactly when the
     * column reaches the sink (what the player sees is what happens).
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 600)
    public static void sinkFillsOnlyAfterPipePrimes(GameTestHelper helper) {
        BlockPos source = new BlockPos(0, 1, 1);
        BlockPos sink = new BlockPos(4, 1, 1);
        BlockPos pushPipe = new BlockPos(3, 1, 1);
        fill(helper, source, 8000);

        helper.succeedWhen(() -> {
            int delivered = amount(helper, sink);
            int pushCell = cellMb(helper.getLevel(), helper.absolutePos(pushPipe));
            if (delivered > 0 && pushCell < PipeStore.capacityMb()) {
                helper.fail("sink received " + delivered + " mB while the pipe feeding it holds only "
                        + pushCell + "/" + PipeStore.capacityMb() + " — delivery outran the fluid");
            }
            if (delivered <= 0) helper.fail("nothing delivered yet");
        });
    }

    /**
     * A pump wedged DIRECTLY against a junction — zero pipe cells between them — must still
     * deliver. The brigade tops a junction slot up from the feeding run's TAIL CELL; a zero-cell
     * run has none, and a consumer past the junction only pulls from a FULL slot, so the slot
     * never filled and the whole line read solved flow with zero actual (the coke-oven smokestack
     * report: pumps flush under the junction row). Converts the template's push-side cell into a
     * junction by hanging a tank off it, then asserts fluid really arrives past it.
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 400)
    public static void pumpAgainstJunctionSlotStillDelivers(GameTestHelper helper) {
        helper.runAfterDelay(10, () -> { // let the kinetics spin the pump up and settle its FACING
            BlockPos pumpRel = null;
            for (int x = 0; x < 6 && pumpRel == null; x++)
                for (int y = 0; y < 4 && pumpRel == null; y++)
                    for (int z = 0; z < 4; z++)
                        if (helper.getBlockState(new BlockPos(x, y, z)).getBlock() instanceof PumpBlock) {
                            pumpRel = new BlockPos(x, y, z);
                            break;
                        }
            if (pumpRel == null) { helper.fail("no pump in template"); return; }
            Direction push = helper.getBlockState(pumpRel).getValue(PumpBlock.FACING);
            BlockPos junctionRel = pumpRel.relative(push);
            if (!pipeAt(helper, junctionRel)) { helper.fail("pump push side is not a pipe cell"); return; }

            // A tank flush against the push-side cell gives it a third connection: the cell
            // becomes a junction NODE and the pump→junction edge carries ZERO pipe cells.
            BlockPos tankRel = null;
            for (Direction side : Direction.values()) {
                if (side.getAxis() == push.getAxis()) continue;
                if (helper.getBlockState(junctionRel.relative(side)).isAir()) {
                    tankRel = junctionRel.relative(side);
                    break;
                }
            }
            if (tankRel == null) { helper.fail("no free face beside the junction cell"); return; }
            helper.setBlock(tankRel, AllBlocks.FLUID_TANK.get().defaultBlockState());

            fill(helper, new BlockPos(0, 1, 1), 8000); // source full
            drain(helper, new BlockPos(4, 1, 1));      // sink empty -> the pump wants to move fluid

            BlockPos sideTank = tankRel;
            helper.succeedWhen(() -> {
                if (amount(helper, sideTank) <= 0 && amount(helper, new BlockPos(4, 1, 1)) <= 0) {
                    helper.fail("no fluid delivered past the zero-cell pump→junction edge");
                }
            });
        });
    }

    /**
     * A manifold's junction slots must serve their feeders FAIRLY. Several runs feeding one slot
     * used to refill its freed room in fixed tick order, so on a chained manifold (junction row,
     * one shared outlet) the first feeder monopolized the room every tick and a competing line
     * starved indefinitely — the coke-oven row: one oven fed the smokestack, its neighbours never
     * did. The brigade splits a slot's room among its feeders by proportional share (the planner's
     * manifold rule, applied at the slot). Builds a gravity rig — three source tanks flush against
     * a junction row draining through ONE outlet — and asserts every source contributes.
     */
    // Own batch: this test PINS the global PIPE_CONDUCTANCE at 5000 for a 60-tick window, and
    // batches are the only isolation gametests have — in the default batch every concurrently
    // running flow test solved with 42x conductance during that window (it masked the
    // submerged-run top-up freeze as a false green, and can skew any rate-shaped assertion).
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 300, batch = "pinnedConfig")
    public static void manifoldSlotServesAllFeeders(GameTestHelper helper) {
        var level = helper.getLevel();
        // Raze the template rig and build the manifold in its bounds (floor at y=0 stays).
        for (int x = 0; x < 6; x++)
            for (int y = 1; y < 4; y++)
                for (int z = 0; z < 4; z++)
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());

        // Junction row at y=2 with a full source tank ON TOP of each cell (full head, no side
        // lip), draining through one outlet corner into a single sink below.
        BlockPos sink = new BlockPos(0, 1, 0);
        BlockPos outlet = new BlockPos(0, 2, 0);
        List<BlockPos> junctionRow = List.of(
                new BlockPos(0, 2, 1), new BlockPos(1, 2, 1), new BlockPos(2, 2, 1));
        List<BlockPos> sources = List.of(
                new BlockPos(0, 3, 1), new BlockPos(1, 3, 1), new BlockPos(2, 3, 1));

        helper.setBlock(sink, AllBlocks.FLUID_TANK.get().defaultBlockState());
        for (BlockPos tank : sources) helper.setBlock(tank, AllBlocks.FLUID_TANK.get().defaultBlockState());
        for (BlockPos pipe : junctionRow) {
            level.setBlockAndUpdate(helper.absolutePos(pipe), AllBlocks.FLUID_PIPE.getDefaultState());
        }
        level.setBlockAndUpdate(helper.absolutePos(outlet), AllBlocks.FLUID_PIPE.getDefaultState());
        // setBlock only re-shapes the NEIGHBOURS; recompute the placed cells' own connections too.
        for (BlockPos pipe : junctionRow) {
            BlockPos abs = helper.absolutePos(pipe);
            level.setBlock(abs, Block.updateFromNeighbourShapes(level.getBlockState(abs), level, abs), 3);
        }
        BlockPos outletAbs = helper.absolutePos(outlet);
        level.setBlock(outletAbs, Block.updateFromNeighbourShapes(level.getBlockState(outletAbs), level, outletAbs), 3);

        for (BlockPos tank : sources) fill(helper, tank, 8000);

        // Starvation needs SCARCITY: freed slot room per tick far below the feeders' solved
        // budgets. In the wild it comes from a rate-limited sink (a venting smokestack); here the
        // engine's own per-boundary ceiling manufactures it — solved rates cranked far above the
        // one-cell-volume-per-tick cap, so every slot's room is a crumb the feeders compete for.
        double priorConductance = PipesNPhysicsConfig.PIPE_CONDUCTANCE.get();
        PipesNPhysicsConfig.PIPE_CONDUCTANCE.set(5000.0);

        // Well before the sink fills: every source must have contributed a fair share, not the
        // crumbs a monopolized slot leaks. The one whose feeder ticks first at its slot must not
        // be the only real contributor.
        helper.runAfterDelay(60, () -> {
            PipesNPhysicsConfig.PIPE_CONDUCTANCE.set(priorConductance);
            int[] given = new int[sources.size()];
            int total = 0;
            for (int i = 0; i < sources.size(); i++) {
                given[i] = 8000 - amount(helper, sources.get(i));
                total += given[i];
            }
            if (total < 1000) {
                helper.fail("manifold barely moved: " + total + " mB total");
                return;
            }
            for (int i = 0; i < given.length; i++) {
                if (given[i] * 6 < total) {
                    helper.fail("source " + i + " gave " + given[i] + " of " + total
                            + " mB — starved by its junction slot's other feeders (gave "
                            + given[0] + "/" + given[1] + "/" + given[2] + ")");
                    return;
                }
            }
            helper.succeed();
        });
    }

    /**
     * Fluid crossing a junction traverses the junction CELL — it may not skip it: the cell fills
     * (and renders) before anything continues into the downstream run, so a chain reads as one
     * continuous travel. Splits the template's longest run with a pipe stub (the mid cell gains a
     * third connection and becomes a junction), primes the feeder half, and drives the executor:
     * the junction slot must go wet no later than the dependent run's first cell.
     */
    @GameTest(template = "piping/charging_max_range", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200, batch = "levelRender")
    public static void junctionCellFillsBeforeDownstreamRun(GameTestHelper helper) {
        helper.runAfterDelay(5, () -> {
            var level = helper.getLevel();
            BlockPos seed = null;
            for (int x = 0; x < 16 && seed == null; x++)
                for (int y = 0; y < 5 && seed == null; y++)
                    for (int z = 0; z < 4 && seed == null; z++) {
                        BlockPos rel = new BlockPos(x, y, z);
                        if (helper.getBlockState(rel).is(AllBlocks.FLUID_PIPE.get())) seed = rel;
                    }
            if (seed == null) { helper.fail("no pipe in template"); return; }

            Graph scan = GraphBuilder.build(level, helper.absolutePos(seed));
            Edge longest = null;
            for (Edge e : scan.edges())
                if (!e.pipes().isEmpty() && (longest == null || e.pipes().size() > longest.pipes().size())) longest = e;
            if (longest == null || longest.pipes().size() < 5) { helper.fail("no long pipe run"); return; }

            // Split the run: swap the mid cell for a REGULAR (auto-connecting) fluid pipe — the
            // template's run is straight glass, which is axis-locked and ignores side stubs — and
            // give it a stub neighbour for a third connection, so the rebuilt graph contracts the
            // run into two edges joined at a junction node there.
            List<BlockPos> run = longest.pipes();
            BlockPos mid = run.get(run.size() / 2);
            level.setBlockAndUpdate(mid, AllBlocks.FLUID_PIPE.getDefaultState());
            BlockPos stub = null;
            for (Direction dir : Direction.values()) {
                BlockPos candidate = mid.relative(dir);
                if (!level.getBlockState(candidate).isAir()) continue;
                boolean touchesOtherPipe = false;
                for (Direction d2 : Direction.values()) {
                    BlockPos n = candidate.relative(d2);
                    if (!n.equals(mid) && level.getBlockState(n).is(AllBlocks.FLUID_PIPE.get())) {
                        touchesOtherPipe = true;
                        break;
                    }
                }
                if (!touchesOtherPipe) { stub = candidate; break; }
            }
            if (stub == null) { helper.fail("no free face beside the mid cell for the stub"); return; }
            level.setBlockAndUpdate(stub, AllBlocks.FLUID_PIPE.getDefaultState());
            // setBlock only re-shapes the NEIGHBOURS; the placed cells' own connection blockstates
            // must be recomputed too (GraphBuilder requires reciprocal openings on both sides).
            level.setBlock(stub, Block.updateFromNeighbourShapes(level.getBlockState(stub), level, stub), 3);
            level.setBlock(mid, Block.updateFromNeighbourShapes(level.getBlockState(mid), level, mid), 3);

            Graph graph = GraphBuilder.build(level, helper.absolutePos(seed));
            Node junction = graph.nodeAt(mid);
            if (junction == null) {
                helper.fail("mid cell did not become a junction node (mid=" + level.getBlockState(mid)
                        + ", stub=" + level.getBlockState(stub) + ")");
                return;
            }
            Edge feeder = null;
            Edge dependent = null;
            for (Edge e : graph.edgesOf(junction.index())) {
                if (e.pipes().isEmpty()) continue;
                if (feeder == null) feeder = e;
                else if (dependent == null || e.pipes().size() > dependent.pipes().size()) dependent = e;
            }
            if (feeder == null || dependent == null) { helper.fail("junction did not split the run into two edges"); return; }

            // Flow: feeder INTO the junction, dependent OUT of it, water on both — as one FlowPass
            // the executor runs. The feeder starts FULL (a primed line), everything past it dry.
            List<EdgeFlow> flows = new ArrayList<>();
            double[] passFlow = new double[graph.edges().size()];
            for (Edge e : graph.edges()) {
                if (e.index() == feeder.index()) {
                    boolean aToB = e.b() == junction.index();
                    flows.add(new EdgeFlow(e.index(), aToB
                            ? EdgeFlow.Direction.A_TO_B : EdgeFlow.Direction.B_TO_A, 200));
                    passFlow[e.index()] = aToB ? 200 : -200;
                } else if (e.index() == dependent.index()) {
                    boolean aToB = e.a() == junction.index();
                    flows.add(new EdgeFlow(e.index(), aToB
                            ? EdgeFlow.Direction.A_TO_B : EdgeFlow.Direction.B_TO_A, 200));
                    passFlow[e.index()] = aToB ? 200 : -200;
                } else {
                    flows.add(EdgeFlow.none(e.index()));
                }
            }
            FluidStack water = new FluidStack(Fluids.WATER, 1);
            Solution flowing = new Solution(flows, List.of(),
                    List.of(new Solution.FlowPass(water, passFlow)), new int[graph.edges().size()],
                    Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                    Set.of(), Set.of(), Set.of(), Set.of(), Map.of(), Map.of(), true);

            int capacity = PipeStore.capacityMb();
            for (BlockPos cell : feeder.pipes()) {
                PipeStore.Store store = PipeStore.at(level, cell);
                if (store != null) {
                    store.extract(capacity);
                    store.insert(new FluidStack(Fluids.WATER, capacity), capacity);
                    store.flush();
                }
            }
            for (BlockPos cell : dependent.pipes()) {
                PipeStore.Store store = PipeStore.at(level, cell);
                if (store != null) { store.extract(capacity); store.flush(); }
            }
            PipeStore.Store slot = PipeStore.at(level, mid);
            if (slot != null) { slot.extract(capacity); slot.flush(); }

            // Drive the executor tick by tick: the junction CELL must fill before the dependent
            // run's first cell sees anything — fluid traverses the junction, it never skips it.
            int slotTick = -1;
            int depTick = -1;
            BlockPos depFirst = dependent.a() == junction.index()
                    ? dependent.pipes().get(0) : dependent.pipes().get(dependent.pipes().size() - 1);
            for (int i = 0; i < 40 && depTick < 0; i++) {
                PipeFlowExecutor.run((ServerLevel) level, graph, flowing);
                if (slotTick < 0 && cellMb(level, mid) > 0) {
                    slotTick = i;
                    // The goggle must READ that slot: a junction's probe reports its stored
                    // content ("Holds: N mB") exactly like an edge cell's — it used to send 0.
                    if (PipeProbe.probe((ServerLevel) level, mid).holdsMb() <= 0) {
                        helper.fail("junction slot is wet but the goggle probe reads holds=0");
                        return;
                    }
                }
                if (depTick < 0 && cellMb(level, depFirst) > 0) depTick = i;
            }
            if (depTick < 0) { helper.fail("flow never crossed the junction into the dependent run"); return; }
            if (slotTick < 0 || slotTick > depTick) {
                helper.fail("fluid skipped the junction cell (slot wet at tick " + slotTick
                        + ", dependent at " + depTick + ")");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * The pipe's stored fluid is REAL volume, so it must survive the world save: the disk path
     * writes the content key, and reading the saved tag back yields the same stack — a reload
     * resumes with the exact in-transit fluid. The cosmetic flow stamp (direction/rate) is
     * re-derived every tick and must NOT be saved.
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100, batch = "levelRender")
    public static void contentPersistsToSaveButFlowStampDoesNot(GameTestHelper helper) {
        fill(helper, new BlockPos(0, 1, 1), 8000);
        helper.succeedWhen(() -> {
            Level level = helper.getLevel();
            BlockPos wet = null;
            for (BlockPos rel : new BlockPos[] {new BlockPos(1, 1, 1), new BlockPos(3, 1, 1)}) {
                if (cellMb(level, helper.absolutePos(rel)) > 0) { wet = helper.absolutePos(rel); break; }
            }
            if (wet == null) { helper.fail("no pipe cell holds fluid yet"); return; }

            var be = level.getBlockEntity(wet);
            if (be == null) { helper.fail("no BE at wet cell"); return; }
            CompoundTag saved = be.saveWithoutMetadata(level.registryAccess());
            if (!pipesnphysics$containsKey(saved, "PnpContent")) {
                helper.fail("stored pipe fluid was NOT written to the world save — it would vanish on reload");
            }
            if (pipesnphysics$containsKey(saved, "PnpFlow")) {
                helper.fail("the cosmetic flow stamp was written to the world save");
            }
        });
    }

    /** Whether a serialized-BE NBT tree contains {@code key} anywhere. */
    private static boolean pipesnphysics$containsKey(net.minecraft.nbt.Tag tag, String key) {
        if (tag instanceof CompoundTag c) {
            if (c.contains(key)) return true;
            for (String k : c.getAllKeys()) if (pipesnphysics$containsKey(c.get(k), key)) return true;
        } else if (tag instanceof net.minecraft.nbt.CollectionTag<?> list) {
            for (net.minecraft.nbt.Tag t : list) if (pipesnphysics$containsKey(t, key)) return true;
        }
        return false;
    }

    /**
     * The "Lift left / Reach limit" reach readout must be SUPPRESSED on an idle, settled run — it is
     * only meaningful while fluid moves or a pump is being asked to lift. A balanced pipe otherwise
     * reads an alarming "Reach limit — raise the supply or add a pump" though nothing is trying to
     * deliver (the user's confusion). Asserts a settled tank-to-tank pipe is NOT shown the reach line,
     * while a flowing payload still is.
     */
    @GameTest(template = "gravity/simple_fluid_leveling", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void reachLineSuppressedOnSettledRun(GameTestHelper helper) {
        fill(helper, new BlockPos(0, 3, 0), 8000);
        fill(helper, new BlockPos(2, 3, 0), 8000);
        helper.runAfterDelay(10, () -> {
            Graph graph = GraphBuilder.build(helper.getLevel(), helper.absolutePos(new BlockPos(0, 3, 0)));
            BlockPos pipeCell = null;
            for (Edge e : graph.edges()) {
                if (graph.node(e.a()).isHandler() && graph.node(e.b()).isHandler() && !e.pipes().isEmpty()) {
                    BlockPos lowest = e.pipes().get(0);
                    for (BlockPos c : e.pipes()) if (c.getY() < lowest.getY()) lowest = c;
                    pipeCell = lowest; // graph built from an absolute seed → pipe cells are absolute
                    break;
                }
            }
            if (pipeCell == null) { helper.fail("no tank-to-tank pipe in graph" + dump(helper)); return; }
            BlockPos probeCell = pipeCell;

            // Poll: the run first PRIMES with real fluid (reads as flow), then settles.
            helper.succeedWhen(() -> {
                PipeStatusPayload settled = PipeProbe.probe(helper.getLevel(), probeCell);
                if (settled.status() != PipeStatusPayload.STATUS_NO_FLOW || settled.fluid().isEmpty()) {
                    helper.fail("expected a settled NO_FLOW pipe with resting fluid, got status "
                            + settled.status() + dump(helper));
                    return;
                }
                if (PipeStatusText.showsReach(settled)) {
                    helper.fail("settled idle pipe still shows the reach line (a balanced run would read "
                            + "a false 'Reach limit')");
                    return;
                }
                PipeStatusPayload flowing = new PipeStatusPayload(BlockPos.ZERO,
                        PipeStatusPayload.STATUS_FLOWING, 100, null, new FluidStack(Fluids.WATER, 1),
                        true, 1f, true, 3f, 5f, PipeStatusPayload.DETAIL_NONE, false, 0, false, 0, 0, 0);
                if (!PipeStatusText.showsReach(flowing)) {
                    helper.fail("a flowing pipe with headroom must still show the reach line");
                }
            });
        });
    }

    /**
     * Goggle legibility (the complement of {@link #restingOpenEndAboveSurfaceRendersDry}): on a
     * pipe rising past the tank's fluid surface to an open end, the goggle must report the DRY
     * upper cells as dry — not "settled, levels balanced". PipeProbe read the cell's fluid from
     * the edge-global restFluids, so every cell of a half-full run claimed water even where the
     * pipe is visibly empty ("the pipe says it has water inside, the vertical ones"). Per-cell
     * waterline gating fixes it: the highest riser cell (above the surface) probes EMPTY, the
     * lowest (below it) still probes the resting fluid.
     */
    @GameTest(template = "suck_from_cauldron", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void dryRiserCellAboveSurfaceProbesDry(GameTestHelper helper) {
        BlockPos tank = new BlockPos(2, 1, 0);
        BlockPos seed = new BlockPos(1, 1, 0);
        // The template's tank is CREATIVE (always brim-full); swap a real one so its surface sits
        // low and the riser is dry above it. The mouth slot holds an EMPTY cauldron by default
        // (un-fillable, so the open end wouldn't even join the solve) — clear it to AIR so the run
        // is a true open-to-air vent that neither spills nor intakes.
        helper.setBlock(new BlockPos(0, 3, 0), Blocks.AIR.defaultBlockState());
        helper.setBlock(tank, AllBlocks.FLUID_TANK.get().defaultBlockState());
        helper.runAfterDelay(5, () -> {
            fill(helper, tank, 4000);
            helper.runAfterDelay(5, () -> {
                var level = helper.getLevel();
                Graph graph = GraphBuilder.build(level, helper.absolutePos(seed));

                Edge riser = null;
                for (Edge e : graph.edges()) {
                    boolean open = graph.node(e.a()).isOpenEnd() || graph.node(e.b()).isOpenEnd();
                    if (open && !e.pipes().isEmpty()) { riser = e; break; }
                }
                if (riser == null) { helper.fail("no open-end pipe run" + dump(helper, seed)); return; }

                BlockPos highest = riser.pipes().get(0);
                BlockPos lowest = riser.pipes().get(0);
                for (BlockPos c : riser.pipes()) {
                    if (c.getY() > highest.getY()) highest = c;
                    if (c.getY() < lowest.getY()) lowest = c;
                }
                if (highest.getY() == lowest.getY()) {
                    helper.fail("riser is not vertical, can't test a dry-above/wet-below split");
                    return;
                }

                // Poll: the settle pass needs a few ticks to draw the submerged cell's fill in.
                BlockPos dry = highest;
                BlockPos wet = lowest;
                helper.succeedWhen(() -> {
                    PipeStatusPayload top = PipeProbe.probe(level, dry);
                    PipeStatusPayload bottom = PipeProbe.probe(level, wet);
                    if (!top.fluid().isEmpty()) {
                        helper.fail("dry riser cell above the surface still reports fluid — the goggle "
                                + "would call an empty pipe 'settled, levels balanced'");
                    }
                    if (bottom.fluid().isEmpty()) {
                        helper.fail("submerged riser cell below the surface lost its resting fluid");
                    }
                });
            });
        });
    }

    /**
     * Goggle legibility: an idle pipe that is FULL of resting fluid must report that fluid
     * (so the goggle can say "settled, levels balanced"), not read empty like a starved/dry
     * run. The probe used to send only the flowing fluid (empty when idle), so a healthy
     * balanced pipe and a dry one were indistinguishable — both bare "No flow". Equalizes two
     * tanks and asserts the settled pipe between them probes NO_FLOW with a non-empty fluid.
     */
    @GameTest(template = "gravity/simple_fluid_leveling", templateNamespace = PipesNPhysics.ID, timeoutTicks = 400)
    public static void settledPipeReportsRestingFluidForGoggle(GameTestHelper helper) {
        BlockPos left = new BlockPos(0, 3, 0);
        BlockPos right = new BlockPos(2, 3, 0);
        // Equal fills on both ends → no head difference → settled at rest (no asymptotic
        // trickle), and the U-pipe between them sits submerged and full.
        fill(helper, left, 8000);
        fill(helper, right, 8000);
        helper.succeedWhen(() -> {
            Graph graph = GraphBuilder.build(helper.getLevel(), helper.absolutePos(left));
            BlockPos pipeCell = null;
            for (Edge e : graph.edges()) {
                if (graph.node(e.a()).isHandler() && graph.node(e.b()).isHandler() && !e.pipes().isEmpty()) {
                    pipeCell = e.pipes().get(e.pipes().size() / 2);
                    break;
                }
            }
            if (pipeCell == null) { helper.fail("no tank-to-tank pipe edge in graph"); return; }

            PipeStatusPayload payload = PipeProbe.probe(helper.getLevel(), pipeCell);
            if (payload.status() != PipeStatusPayload.STATUS_NO_FLOW) {
                helper.fail("pipe not settled yet (status " + payload.status() + ")");
                return;
            }
            if (payload.fluid().isEmpty()) {
                helper.fail("settled full pipe probed EMPTY — goggle would call a balanced pipe 'dry'");
            }
        });
    }

    /**
     * Fluid travels down a pipe as a front, NOT a pop-fill: the number of cells holding fluid
     * GROWS over ticks while a long run primes — the front is the real stored volume advancing.
     * End-to-end with a real pump pushing water down the long discharge run.
     */
    @GameTest(template = "piping/charging_max_range", templateNamespace = PipesNPhysics.ID, timeoutTicks = 400)
    public static void fluidFrontAdvancesOverTime(GameTestHelper helper) {
        helper.runAfterDelay(5, () -> {
            BlockPos pump = null;
            List<BlockPos> tanks = new ArrayList<>();
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 5; y++) {
                    for (int z = 0; z < 4; z++) {
                        BlockPos rel = new BlockPos(x, y, z);
                        var state = helper.getBlockState(rel);
                        if (state.getBlock() instanceof PumpBlock) pump = rel;
                        else if (state.is(AllBlocks.FLUID_TANK.get())) tanks.add(rel);
                    }
                }
            }
            if (pump == null || tanks.size() != 2) {
                helper.fail("template scan found pump=" + pump + " tanks=" + tanks.size());
                return;
            }
            tanks.sort(Comparator.comparingInt(BlockPos::getX));
            BlockPos pumpPos = pump;
            Direction facing = helper.getBlockState(pumpPos).getValue(PumpBlock.FACING);
            BlockPos suction = facing == Direction.WEST ? tanks.get(1) : tanks.get(0);
            drain(helper, tanks.get(0));
            drain(helper, tanks.get(1));
            fillFluid(helper, suction, Fluids.WATER, 8000);

            int[] early = {-1};
            helper.runAfterDelay(8, () -> early[0] = pipesnphysics$countChargedPipes(helper));
            helper.runAfterDelay(160, () -> {
                int late = pipesnphysics$countChargedPipes(helper);
                if (late < 1) {
                    helper.fail("no pipe ever charged — front never formed" + dump(helper, pumpPos));
                    return;
                }
                if (late <= early[0]) {
                    helper.fail("front did not advance over time (instant fill?): early="
                            + early[0] + " late=" + late + dump(helper, pumpPos));
                    return;
                }
                helper.succeed();
            });
        });
    }

    /** Count pipe cells that hold stored fluid. */
    private static int pipesnphysics$countChargedPipes(GameTestHelper helper) {
        int count = 0;
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 5; y++) {
                for (int z = 0; z < 4; z++) {
                    if (cellMb(helper.getLevel(), helper.absolutePos(new BlockPos(x, y, z))) > 0) count++;
                }
            }
        }
        return count;
    }


    /**
     * DIAGNOSTIC: after two tanks equalize AND the network has settled (slept),
     * the connecting pipe must still render fluid — not revert to empty. Probes the
     * solve state to report WHY if it reverted.
     */
    /**
     * A raised tank draining into a lower one: the upper tank must empty COMPLETELY, and every
     * drop is accounted for — what is not yet in the lower tank still resides in the pipes
     * (settling down over time), never voided. The recede is gradual; this guards the end state
     * and conservation, the feel is visual.
     */
    @GameTest(template = "gravity/2_drop_fall", templateNamespace = PipesNPhysics.ID, timeoutTicks = 1000)
    public static void drainedPipeRecedesNotStuck(GameTestHelper helper) {
        BlockPos top = new BlockPos(0, 4, 0);
        fill(helper, top, 8000);

        helper.succeedWhen(() -> {
            if (amount(helper, top) != 0) {
                helper.fail("upper tank has not drained yet: " + amount(helper, top));
                return;
            }
            int pipes = pipesnphysics$areaPipeContent(helper, 4, 6, 4);
            BlockPos lower = null;
            for (int x = 0; x < 4 && lower == null; x++)
                for (int y = 0; y < 6 && lower == null; y++)
                    for (int z = 0; z < 4 && lower == null; z++) {
                        BlockPos rel = new BlockPos(x, y, z);
                        if (!rel.equals(top) && helper.getBlockState(rel).is(AllBlocks.FLUID_TANK.get())) {
                            lower = rel;
                        }
                    }
            int lowerMb = lower == null ? 0 : amount(helper, lower);
            if (lowerMb + pipes != 8000) {
                helper.fail("fluid lost while draining down: lower=" + lowerMb + " pipes=" + pipes);
            }
        });
    }

    /**
     * Breaking the pipe between two tanks leaves a dangling open-ended pipe; the
     * filled tank spills out of it. That spill must settle, not place and reclaim a
     * fluid block forever. Reproduces the user's "break a pipe → block spawns/
     * despawns forever" by removing the pump from tank-pipe-pump-pipe-tank, then
     * sampling the spilled block over consecutive ticks: present and stable.
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 240)
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

    /**
     * Two level 1x1 tanks joined by a flat pipe run (tank-pipe-pipe-tank). Partly
     * filled, they equalize with the waterline settling INSIDE the connecting pipe
     * cells — those cells are still full and must keep rendering, not revert to empty
     * the instant flow stops. (Regression: the submersion test used the cell centre,
     * so an equalized level below centre wrongly read as above the waterline.)
     */
    @GameTest(template = "gravity/long_equalization", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void flatEqualizedPipeKeepsFluid(GameTestHelper helper) {
        helper.runAfterDelay(2, () -> {
            List<BlockPos> tanks = new ArrayList<>();
            for (int x = 0; x < 12; x++) for (int y = 0; y < 5; y++) for (int z = 0; z < 12; z++) {
                BlockPos rel = new BlockPos(x, y, z);
                if (helper.getBlockState(rel).is(AllBlocks.FLUID_TANK.get())) tanks.add(rel);
            }
            if (tanks.size() < 2) { helper.fail("expected 2 tanks, found " + tanks.size()); return; }
            // Equal, partial fill: no flow at all, and the surface settles low inside
            // the connecting pipe cells (below their centre, above their bottom). Drain
            // first — the template ships its tanks full.
            for (BlockPos t : tanks) drain(helper, t);
            for (BlockPos t : tanks) fill(helper, t, 2000);

            // The connecting cells settle at the shared waterline INSIDE them: partial content,
            // neither drained dry nor painted full.
            helper.succeedWhen(() -> {
                int wet = 0;
                for (int x = 0; x < 12; x++) for (int y = 0; y < 5; y++) for (int z = 0; z < 12; z++) {
                    if (cellMb(helper.getLevel(), helper.absolutePos(new BlockPos(x, y, z))) > 0) wet++;
                }
                if (wet == 0) helper.fail("flat resting pipe (surface inside the cell) holds no fluid");
            });
        });
    }

    /**
     * Regression: a fully-charged pipe must NOT visually revert (drain and refill)
     * when an equalizing flow that runs B&rarr;A stops or briefly resumes.
     *
     * The render bridge keys each cell's inbound rim off the flow direction, but the
     * resting path used to hardcode node a as the inbound side. An edge whose flow ran
     * toward node a therefore had its inbound flags flipped on every flowing&harr;resting
     * transition, and the next charge reset {@code progress} to 0 &mdash; replaying the
     * whole fill backward. This drives that exact sequence on a real pipe and asserts
     * the charged cells stay charged.
     */
    /**
     * A pump pushing into a full sink backs the pipe up with NO flow this tick. The backed-up run
     * (a SINK_FULL stall or a dead-headed NO_HEAD pump) must KEEP its stored fluid — the settle
     * pass may not drain a column a running pump is holding, so when the sink makes room the flow
     * resumes instantly instead of re-priming the whole run.
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void backedUpStallKeepsChargedPipe(GameTestHelper helper) {
        helper.runAfterDelay(2, () -> {
            var level = helper.getLevel();
            BlockPos seed = null;
            for (int x = 0; x < 6 && seed == null; x++)
                for (int y = 0; y < 4 && seed == null; y++)
                    for (int z = 0; z < 4 && seed == null; z++) {
                        BlockPos rel = new BlockPos(x, y, z);
                        if (helper.getBlockState(rel).is(AllBlocks.FLUID_PIPE.get())) seed = rel;
                    }
            if (seed == null) { helper.fail("no pipe in template"); return; }

            Graph graph = GraphBuilder.build(level, helper.absolutePos(seed));
            Edge edge = null;
            for (Edge e : graph.edges()) {
                var a = graph.node(e.a());
                var b = graph.node(e.b());
                if (((a.isPump() && b.isHandler()) || (a.isHandler() && b.isPump())) && !e.pipes().isEmpty()) {
                    edge = e;
                    break;
                }
            }
            if (edge == null) { helper.fail("no pump-to-handler pipe edge in graph"); return; }
            Edge run = edge;

            // Both tanks brim-full, so the live engine's settle can't siphon the held column away
            // between the synthetic executor passes below.
            fill(helper, new BlockPos(0, 1, 1), 8000);
            fill(helper, new BlockPos(4, 1, 1), 8000);

            int capacity = PipeStore.capacityMb();
            for (BlockPos cell : run.pipes()) {
                PipeStore.Store store = PipeStore.at(level, cell);
                if (store != null) {
                    store.insert(new FluidStack(Fluids.WATER, capacity), capacity);
                    store.flush();
                }
            }

            for (boolean noHead : new boolean[]{false, true}) {
                for (int i = 0; i < 10; i++) {
                    PipeFlowExecutor.run((ServerLevel) level, graph,
                            pipesnphysics$backedUpSolution(graph, run.index(), noHead));
                }
                for (BlockPos cell : run.pipes()) {
                    if (cellMb(level, cell) < capacity) {
                        helper.fail("backed-up " + (noHead ? "NO_HEAD" : "SINK_FULL")
                                + " stall drained the held column at " + cell.toShortString());
                        return;
                    }
                }
            }
            helper.succeed();
        });
    }

    /**
     * BREAK-SPILL: a broken pipe cell's stored fluid is pushed back into the network (adjacent
     * cells and tanks with room), not voided — tearing down a wet line gives the fluid back.
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
    public static void brokenPipeSpillsContentBackIntoNetwork(GameTestHelper helper) {
        BlockPos tank = new BlockPos(0, 1, 1);
        BlockPos cell = new BlockPos(1, 1, 1);
        fill(helper, tank, 4000);
        helper.runAfterDelay(5, () -> {
            var level = helper.getLevel();
            int capacity = PipeStore.capacityMb();
            PipeStore.Store store = PipeStore.at(level, helper.absolutePos(cell));
            if (store == null) { helper.fail("no pipe store at the pull cell"); return; }
            store.extract(capacity);
            store.insert(new FluidStack(Fluids.WATER, capacity), capacity);
            store.flush();
            int before = amount(helper, tank) + capacity;

            FluidStack content = store.fluid().copy();
            helper.setBlock(cell, Blocks.AIR.defaultBlockState());
            NetworkEditHandler.spillBrokenPipe((ServerLevel) level, helper.absolutePos(cell), content);

            int after = amount(helper, tank);
            if (after != before) {
                helper.fail("broken pipe voided its content: tank holds " + after
                        + " mB, expected " + before);
                return;
            }
            helper.succeed();
        });
    }

    /** A flowless solution where the edge is backed up against a blockage (no fluid carried). */
    private static Solution pipesnphysics$backedUpSolution(Graph graph, int edgeIndex, boolean noHead) {
        List<EdgeFlow> flows = new ArrayList<>();
        for (Edge e : graph.edges()) flows.add(EdgeFlow.none(e.index()));
        Set<Integer> stalled = new HashSet<>();
        Set<Integer> noHeadEdges = new HashSet<>();
        Map<Integer, Solution.Reason> reasons = new HashMap<>();
        if (noHead) {
            noHeadEdges.add(edgeIndex);
        } else {
            stalled.add(edgeIndex);
            reasons.put(edgeIndex, Solution.Reason.SINK_FULL);
        }
        return new Solution(flows, List.of(), List.of(), new int[graph.edges().size()],
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Set.of(), stalled, noHeadEdges, Set.of(), reasons, Map.of(), true);
    }

    /**
     * The "goofy_network" freeze: a pump chain lifts water from a source up a series line
     * source → header → BIG TANK → spout, where the terminal spout is FULL. The big tank has room,
     * so the pump-lifted water must back up and fill it (toward 100%). It currently freezes at 92%:
     * the one-shot solve routes a through-current to the full spout, the intermediate big tank reads
     * as a pass-through (net ~0), and {@code planTransfers} zeroes the whole line on the full
     * terminal — so a reservoir with room is starved by a full downstream sink. Reproduces the
     * user's "every pump says no room ahead"; draining the spout (their spout-pump fix) unfreezes it.
     */
    @GameTest(template = "goofy_network", templateNamespace = PipesNPhysics.ID, timeoutTicks = 300)
    public static void pumpFillsIntermediateTankDespiteFullTerminal(GameTestHelper helper) {
        int[] before = {-1};
        helper.runAfterDelay(40, () -> { // spin pumps up, then reproduce the screenshot's stuck fill state
            IFluidHandler big = pipesnphysics$goofyHandler(helper, 32000);
            IFluidHandler header = pipesnphysics$goofyHandler(helper, 8000, /*wantEmpty*/true);
            IFluidHandler spout = pipesnphysics$goofyHandler(helper, 1000);
            if (big == null || header == null) { System.out.println("GOOFY: could not find tanks"); return; }
            big.drain(Integer.MAX_VALUE, IFluidHandler.FluidAction.EXECUTE);
            big.fill(new FluidStack(Fluids.WATER, 29558), IFluidHandler.FluidAction.EXECUTE); // 92%, as reported
            header.drain(Integer.MAX_VALUE, IFluidHandler.FluidAction.EXECUTE);
            header.fill(new FluidStack(Fluids.WATER, 36), IFluidHandler.FluidAction.EXECUTE);
            if (spout != null) spout.fill(new FluidStack(Fluids.WATER, 1000), IFluidHandler.FluidAction.EXECUTE);
            before[0] = big.getFluidInTank(0).getAmount();
        });
        helper.runAfterDelay(250, () -> {
            IFluidHandler big = pipesnphysics$goofyHandler(helper, 32000);
            int after = big == null ? -1 : big.getFluidInTank(0).getAmount();
            // The pump keeps lifting source water; the spout is full so it can't leave — the big tank
            // (which has room) MUST fill toward 100%. It currently freezes at 92% because a full
            // terminal sink zeroes the whole series line (the intermediate reservoir is starved).
            if (after <= before[0] + 100) {
                pipesnphysics$dumpGoofy(helper, "FAIL: intermediate tank starved by a full terminal");
                helper.fail("pump-fed intermediate tank starved by a full downstream sink: "
                        + before[0] + " -> " + after + " mB (expected it to fill toward 32000)");
                return;
            }
            // Force the big tank full and drain the HEADER (the single tank above). With the tank below
            // full, the header is the intermediate reservoir with room — the pump must still refill it.
            big.fill(new FluidStack(Fluids.WATER, 32000), IFluidHandler.FluidAction.EXECUTE);
            IFluidHandler header = pipesnphysics$goofyHandler(helper, 8000, true);
            if (header != null) header.drain(Integer.MAX_VALUE, IFluidHandler.FluidAction.EXECUTE);
        });
        int[] hdr = {-1};
        helper.runAfterDelay(256, () -> {
            IFluidHandler header = pipesnphysics$goofyHandler(helper, 8000, true);
            hdr[0] = header == null ? -1 : header.getFluidInTank(0).getAmount();
        });
        helper.runAfterDelay(300, () -> {
            IFluidHandler header = pipesnphysics$goofyHandler(helper, 8000, true);
            int now = header == null ? -1 : header.getFluidInTank(0).getAmount();
            if (now <= hdr[0] + 100) {
                pipesnphysics$dumpGoofy(helper, "FAIL: header above a full tank did not refill");
                helper.fail("the single tank above a FULL tank did not refill: " + hdr[0]
                        + " -> " + now + " mB (the pump must still fill the intermediate reservoir)");
                return;
            }
            helper.succeed();
        });
    }

    private static IFluidHandler pipesnphysics$goofyHandler(GameTestHelper helper, int capacity) {
        return pipesnphysics$goofyHandler(helper, capacity, false);
    }

    /** Find a graph HANDLER whose total capacity matches; wantEmpty picks the highest-Y match (header vs source). */
    private static IFluidHandler pipesnphysics$goofyHandler(GameTestHelper helper, int capacity, boolean topmost) {
        Level level = helper.getLevel();
        BlockPos seed = null;
        for (int x = 0; x < 6 && seed == null; x++)
            for (int y = 0; y < 7 && seed == null; y++)
                for (int z = 0; z < 2 && seed == null; z++) {
                    BlockPos rel = new BlockPos(x, y, z);
                    if (FluidPropagator.getPipe(level, helper.absolutePos(rel)) != null) seed = rel;
                }
        if (seed == null) return null;
        Graph g = GraphBuilder.build(level, helper.absolutePos(seed));
        IFluidHandler best = null;
        int bestY = Integer.MIN_VALUE;
        for (Node n : g.nodes()) {
            if (n.kind() != Node.Kind.HANDLER) continue;
            IFluidHandler h = pipesnphysics$sideFallback(level, n.pos());
            if (h == null) continue;
            int cap = 0;
            for (int i = 0; i < h.getTanks(); i++) cap += h.getTankCapacity(i);
            if (cap != capacity) continue;
            if (!topmost) return h;
            if (n.pos().getY() > bestY) { bestY = n.pos().getY(); best = h; }
        }
        return best;
    }

    private static IFluidHandler pipesnphysics$sideFallback(Level level, BlockPos pos) {
        IFluidHandler h = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null);
        if (h != null) return h;
        for (Direction d : Direction.values()) {
            h = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, d);
            if (h != null) return h;
        }
        return null;
    }

    /** Print the full goofy_network solve to stdout (fail messages truncate at 1024). */
    private static void pipesnphysics$dumpGoofy(GameTestHelper helper, String label) {
        Level level = helper.getLevel();
        BlockPos seed = null;
        for (int x = 0; x < 6 && seed == null; x++)
            for (int y = 0; y < 7 && seed == null; y++)
                for (int z = 0; z < 2 && seed == null; z++) {
                    BlockPos rel = new BlockPos(x, y, z);
                    if (FluidPropagator.getPipe(level, helper.absolutePos(rel)) != null) seed = rel;
                }
        if (seed == null) { System.out.println("GOOFY: no pipe found"); return; }
        Graph g = GraphBuilder.build(level, helper.absolutePos(seed));
        Solution sol = FlowSolver.solve(level, g);
        StringBuilder sb = new StringBuilder("\nGOOFY === " + label + " ===\n");
        sb.append("GOOFY pumps=").append(g.pumps().size())
                .append(" runningPump=").append(EngineTickHandler.hasRunningPump(helper.getLevel(), g))
                .append(" active=").append(sol.active())
                .append(" transfers=").append(sol.transfers().size()).append("\n");
        for (Node n : g.nodes())
            sb.append(String.format("GOOFY  N%-2d %-8s head=%.3f ceil=%.3f%s%n",
                    n.index(), n.kind(), sol.nodeHeads().getOrDefault(n.index(), 0.0),
                    sol.nodeCeilings().getOrDefault(n.index(), 0.0), pipesnphysics$roomAt(level, n.pos())));
        for (Edge e : g.edges()) {
            String tag = sol.blockedEdges().contains(e.index()) ? " BLOCKED"
                    : sol.stalledEdges().contains(e.index()) ? " STALLED"
                    : sol.noHeadEdges().contains(e.index()) ? " NOHEAD" : "";
            Solution.Reason r = sol.edgeReasons().get(e.index());
            boolean rest = !sol.restFluids().getOrDefault(e.index(), FluidStack.EMPTY).isEmpty();
            boolean ef = !sol.edgeFluids().getOrDefault(e.index(), FluidStack.EMPTY).isEmpty();
            sb.append(String.format("GOOFY  E%-2d %d-%d len%d dir=%s%s%s rest=%b edgeFluid=%b%s%n",
                    e.index(), e.a(), e.b(), e.length(),
                    sol.edgeFlows().get(e.index()).direction(), tag, r == null ? "" : " (" + r + ")",
                    rest, ef, sol.heldEdges().contains(e.index()) ? " HELD" : ""));
        }
        for (Solution.Transfer t : sol.transfers())
            sb.append("GOOFY  T ").append(t.from().toShortString()).append(" -> ")
                    .append(t.to().toShortString()).append(" ").append(t.fluid().getAmount()).append("\n");
        System.out.println(sb);
    }

    /** " content/capacity mB (has room)" for a fluid handler at pos, or "" if none — for diagnostics. */
    private static String pipesnphysics$roomAt(Level level, BlockPos pos) {
        IFluidHandler h = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null);
        if (h == null) return "";
        int content = 0, capacity = 0;
        for (int i = 0; i < h.getTanks(); i++) {
            content += h.getFluidInTank(i).getAmount();
            capacity += h.getTankCapacity(i);
        }
        return String.format("  %d/%d mB%s", content, capacity, content < capacity ? " (ROOM)" : " (full)");
    }

    private static String dump(GameTestHelper helper) {
        return dump(helper, new BlockPos(2, 1, 1));
    }

    private static String dump(GameTestHelper helper, BlockPos probe) {
        var graph = GraphBuilder.build(helper.getLevel(), helper.absolutePos(probe));
        var solution = FlowSolver.solve(helper.getLevel(), graph);
        StringBuilder out = new StringBuilder(" | GRAPH:");
        for (var n : graph.nodes()) {
            out.append(String.format(" [%d %s %s head=%s ceil=%s]",
                    n.index(), n.kind(), n.pos().toShortString(),
                    solution.nodeHeads().get(n.index()), solution.nodeCeilings().get(n.index())));
        }
        for (var e : graph.edges()) {
            out.append(String.format(" e%d(%d-%d len%d %s)",
                    e.index(), e.a(), e.b(), e.length(),
                    solution.edgeFlows().get(e.index()).direction()));
        }
        return out.toString();
    }

    private static BlockState pipeState(
            Block pipe, Direction... connections) {
        var state = pipe.defaultBlockState();
        for (var property : PipeBlock.PROPERTY_BY_DIRECTION.values()) {
            state = state.setValue(property, false);
        }
        for (var direction : connections) {
            state = state.setValue(
                    PipeBlock.PROPERTY_BY_DIRECTION.get(direction), true);
        }
        return state;
    }

    private static void fill(GameTestHelper helper, BlockPos relativePos, int mb) {
        fillFluid(helper, relativePos, Fluids.WATER, mb);
    }

    private static void fillFluid(GameTestHelper helper, BlockPos relativePos,
                                  Fluid fluid, int mb) {
        handler(helper, relativePos)
                .fill(new FluidStack(fluid, mb), IFluidHandler.FluidAction.EXECUTE);
    }

    private static void drain(GameTestHelper helper, BlockPos relativePos) {
        handler(helper, relativePos).drain(Integer.MAX_VALUE, IFluidHandler.FluidAction.EXECUTE);
    }

    private static int amount(GameTestHelper helper, BlockPos relativePos) {
        return handler(helper, relativePos).getFluidInTank(0).getAmount();
    }

    private static IFluidHandler handler(GameTestHelper helper, BlockPos relativePos) {
        IFluidHandler handler = helper.getLevel().getCapability(
                Capabilities.FluidHandler.BLOCK, helper.absolutePos(relativePos), null);
        if (handler == null) helper.fail("no fluid handler at " + relativePos);
        return handler;
    }

    /**
     * A run BACKED UP from the very first tick — never charged by a travelling front — must still
     * render FULL, not empty. A pump reverse-blocking a full tank (its check valve stops that tank
     * draining out through it) leaves the pipes between them a pressurized column: SINK_FULL /
     * {@code isBackedUp}, yet no flow ever filled them. The renderer must STAMP such a run full, not
     * merely preserve the (nonexistent) prior charge. Reproduces "a reverse pump renders the pipe
     * empty instead of full". Uses long_pipe (tank - 5 pipes - pump - tank); coords shift at
     * placement, so everything is found from the graph in absolute space.
     */
    @GameTest(template = "piping/long_pipe", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void bornBackedUpRunRendersFull(GameTestHelper helper) {
        Level level = helper.getLevel();
        List<BlockPos> run = new ArrayList<>();
        helper.runAfterDelay(10, () -> {
            BlockPos seed = null;
            for (int x = 0; x <= 8 && seed == null; x++) for (int y = 0; y <= 2 && seed == null; y++)
                for (int z = 0; z <= 2 && seed == null; z++) {
                    BlockPos abs = helper.absolutePos(new BlockPos(x, y, z));
                    if (FluidPropagator.getPipe(level, abs) != null) seed = abs;
                }
            if (seed == null) { helper.fail("no pipe seed"); return; }
            Graph g = GraphBuilder.build(level, seed);
            BlockPos pump = null;
            List<BlockPos> tanks = new ArrayList<>();
            for (Node n : g.nodes()) { if (n.isPump()) pump = n.pos(); else if (n.isHandler()) tanks.add(n.pos()); }
            if (pump == null || tanks.size() < 2) { helper.fail("layout not found: " + tanks); return; }
            for (Edge e : g.edges()) run.addAll(e.pipes());
            // The tank the pipe RUN reaches (far from the pump) is on the pump's push side — fill it
            // full so the pump's check valve backs the run up against it; empty the pump's supply side.
            BlockPos backedTank = tanks.get(0).distManhattan(pump) >= tanks.get(1).distManhattan(pump)
                    ? tanks.get(0) : tanks.get(1);
            BlockPos supply = backedTank.equals(tanks.get(0)) ? tanks.get(1) : tanks.get(0);
            IFluidHandler bh = pipesnphysics$sideFallback(level, backedTank);
            if (bh != null) bh.fill(new FluidStack(Fluids.WATER, 8000), IFluidHandler.FluidAction.EXECUTE);
            IFluidHandler sh = pipesnphysics$sideFallback(level, supply);
            if (sh != null) sh.drain(Integer.MAX_VALUE, IFluidHandler.FluidAction.EXECUTE);
        });
        helper.runAfterDelay(160, () -> {
            if (run.isEmpty()) { helper.fail("no run cells captured"); return; }
            for (BlockPos abs : run) {
                if (cellMb(level, abs) <= 0) {
                    helper.fail("backed-up run cell holds no fluid (never filled from the full tank) at "
                            + helper.relativePos(abs));
                    return;
                }
            }
            helper.succeed();
        });
    }

    /**
     * A run BLOCKED by an unpowered pump, but connected to a tank that STILL HOLDS fluid, must render
     * the settled water sitting in the pipe up to the pump — not blank. The pump-off branch never
     * assembles, so the run gets no rest fluid or heads unless {@code settleBlockedRuns} supplies them
     * from the filled reservoir. Reproduces "a pump on this line is unpowered, and the pipes render
     * empty". The tank on the pump's FAR side is emptied and must stay dry (no phantom water past the
     * block).
     */
    @GameTest(template = "piping/long_pipe", templateNamespace = PipesNPhysics.ID, timeoutTicks = 300)
    public static void blockedRunFromFullTankRendersSettled(GameTestHelper helper) {
        Level level = helper.getLevel();
        List<BlockPos> runToFull = new ArrayList<>();
        List<BlockPos> runToEmpty = new ArrayList<>();
        helper.runAfterDelay(10, () -> {
            BlockPos seed = null;
            for (int x = 0; x <= 8 && seed == null; x++) for (int y = 0; y <= 2 && seed == null; y++)
                for (int z = 0; z <= 2 && seed == null; z++) {
                    BlockPos abs = helper.absolutePos(new BlockPos(x, y, z));
                    if (FluidPropagator.getPipe(level, abs) != null) seed = abs;
                }
            if (seed == null) { helper.fail("no pipe seed"); return; }
            Graph g = GraphBuilder.build(level, seed);
            BlockPos pump = null;
            List<BlockPos> tanks = new ArrayList<>();
            for (Node n : g.nodes()) { if (n.isPump()) pump = n.pos(); else if (n.isHandler()) tanks.add(n.pos()); }
            if (pump == null || tanks.size() < 2) { helper.fail("layout not found: " + tanks); return; }
            BlockPos fullTank = tanks.get(0).distManhattan(pump) >= tanks.get(1).distManhattan(pump) ? tanks.get(0) : tanks.get(1);
            BlockPos emptyTank = fullTank.equals(tanks.get(0)) ? tanks.get(1) : tanks.get(0);
            for (Edge e : g.edges()) {
                boolean touchesFull = g.node(e.a()).pos().equals(fullTank) || g.node(e.b()).pos().equals(fullTank);
                (touchesFull ? runToFull : runToEmpty).addAll(e.pipes());
            }
            // Unpower the pump by clearing its kinetic neighbours (motor/cogwheel).
            for (Direction d : Direction.values()) {
                var st = level.getBlockState(pump.relative(d));
                if (!st.is(AllBlocks.FLUID_PIPE.get()) && !st.is(AllBlocks.FLUID_TANK.get()) && !st.isAir()) {
                    level.setBlockAndUpdate(pump.relative(d), Blocks.AIR.defaultBlockState());
                }
            }
            IFluidHandler fh = pipesnphysics$sideFallback(level, fullTank);
            if (fh != null) fh.fill(new FluidStack(Fluids.WATER, 8000), IFluidHandler.FluidAction.EXECUTE);
            IFluidHandler eh = pipesnphysics$sideFallback(level, emptyTank);
            if (eh != null) eh.drain(Integer.MAX_VALUE, IFluidHandler.FluidAction.EXECUTE);
        });
        helper.runAfterDelay(160, () -> {
            if (runToFull.isEmpty()) { helper.fail("no run captured"); return; }
            for (BlockPos abs : runToFull) {
                if (cellMb(level, abs) <= 0) {
                    helper.fail("blocked run from the FULL tank holds no fluid at " + helper.relativePos(abs));
                    return;
                }
            }
            // The far run (to the drained tank) must stay dry — no phantom water past the off pump.
            for (BlockPos abs : runToEmpty) {
                if (cellMb(level, abs) > 0) {
                    helper.fail("run to the EMPTY tank wrongly holds water past the off pump at " + helper.relativePos(abs));
                    return;
                }
            }
            helper.succeed();
        });
    }

    // ---- automatic relay detection (CLAUDE.md §2, RelayDetector / HandlerRoles) ----
    // These drive RelayDetector.observe directly on a placed block: a real relay (a docking connector,
    // a VS hose) needs a second mod, but the learning is block-type + fluid-amount math the detector
    // exposes. Each body runs synchronously (no runAfterDelay), so clearing the detector at the start
    // fully isolates it from its batch siblings. Distinct block types keep the learned sets disjoint.

    /**
     * A handler whose stored fluid keeps GROWING on its own — with no fill from the engine — is the
     * relay signature: learned as a relay and demoted to receive-only, so the solver stops draining and
     * equalizing it as a tank.
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100, batch = "relayDetector")
    public static void relayDetectorLearnsSpontaneousGain(GameTestHelper helper) {
        RelayDetector.clear();
        Level level = helper.getLevel();
        // Blocks.STONE stands in for an unknown mod's relay: non-exempt and untagged.
        BlockPos rel = new BlockPos(1, 2, 1);
        BlockPos pos = helper.absolutePos(rel);
        helper.setBlock(rel, Blocks.STONE);
        for (int amount = 100; amount <= 700; amount += 100) {
            RelayDetector.observe(level, pos, Fluids.WATER, amount); // +100 each step, no fill from us
        }
        if (!RelayDetector.isRelay(Blocks.STONE)) {
            helper.fail("a block that gained fluid on its own every tick was not learned as a relay");
            return;
        }
        if (!HandlerRoles.isRelayEndpoint(level, pos)) {
            helper.fail("a learned relay is not treated as a drain-priority relay endpoint");
            return;
        }
        RelayDetector.clear();
        helper.succeed();
    }

    /**
     * A handler that spontaneously LOSES fluid is a consumer (a basin, a boiler), not a relay — it must
     * keep receiving fluid and must never be demoted.
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100, batch = "relayDetector")
    public static void relayDetectorSparesConsumers(GameTestHelper helper) {
        RelayDetector.clear();
        Level level = helper.getLevel();
        BlockPos rel = new BlockPos(1, 2, 1);
        BlockPos pos = helper.absolutePos(rel);
        helper.setBlock(rel, Blocks.DIRT);
        for (int amount = 1000; amount >= 200; amount -= 100) {
            RelayDetector.observe(level, pos, Fluids.WATER, amount); // spontaneously LOSING = a consumer
        }
        if (RelayDetector.isRelay(Blocks.DIRT)) {
            helper.fail("a block that only lost fluid (a consumer) was wrongly demoted to a relay");
            return;
        }
        RelayDetector.clear();
        helper.succeed();
    }

    /**
     * A relay_endpoint-tagged handler (the create-aeronautics docking connector, loaded from run/mods)
     * resolves to a drain-priority BOTTOMLESS column, NOT a finite reservoir — so the solver never holds
     * it "balanced" and refuses to drain it (the equalization stall that stopped fluid crossing a docked
     * connector). Skips if the mod is absent.
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100, batch = "relayDetector")
    public static void relayEndpointResolvesBottomless(GameTestHelper helper) {
        Level level = helper.getLevel();
        Block connector = BuiltInRegistries.BLOCK.get(
                ResourceLocation.fromNamespaceAndPath("simulated", "docking_connector"));
        if (connector == Blocks.AIR) { helper.succeed(); return; } // aeronautics not installed
        BlockPos rel = new BlockPos(1, 2, 1);
        helper.setBlock(rel, connector);
        BlockPos pos = helper.absolutePos(rel);
        if (!HandlerRoles.isRelayEndpoint(level, pos)) {
            helper.fail("docking connector is not classified as a relay endpoint (tag not applied)");
            return;
        }
        BoundaryColumn column = BoundaryColumn.resolve(level,
                new Node(0, pos, Node.Kind.HANDLER, pos.getY() + 0.5, null, null, null));
        if (column == null) { helper.succeed(); return; } // no live cap on a lone connector — nothing to assert
        if (column.isFiniteReservoir()) {
            helper.fail("relay endpoint resolved as a finite reservoir — it would surface-equalize and stall");
            return;
        }
        helper.succeed();
    }

    /**
     * A SIDE-SPECIFIC handler (the dev-only {@link TestSideHandlers} on a sponge: a different tank per
     * face, no null-side handler) resolves each face to ITS OWN fluid — the core of the per-face
     * endpoint feature, and the thing no real pack block can exercise. NORTH holds water, SOUTH holds
     * lava, and resolving through each face returns the matching fluid.
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100, batch = "relayDetector")
    public static void sideSpecificHandlerResolvesPerFace(GameTestHelper helper) {
        TestSideHandlers.clear();
        Level level = helper.getLevel();
        BlockPos rel = new BlockPos(1, 2, 1);
        BlockPos pos = helper.absolutePos(rel);
        helper.setBlock(rel, Blocks.SPONGE);
        TestSideHandlers.tankAt(pos, Direction.NORTH).fill(
                new FluidStack(Fluids.WATER, 8000), IFluidHandler.FluidAction.EXECUTE);
        TestSideHandlers.tankAt(pos, Direction.SOUTH).fill(
                new FluidStack(Fluids.LAVA, 8000), IFluidHandler.FluidAction.EXECUTE);
        if (level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null) != null) {
            helper.fail("test fixture is not side-specific (it exposes a null-side handler)");
            return;
        }
        double y = pos.getY() + 0.5;
        BoundaryColumn north = BoundaryColumn.resolve(level,
                new Node(0, pos, Node.Kind.HANDLER, y, null, null, Direction.NORTH));
        BoundaryColumn south = BoundaryColumn.resolve(level,
                new Node(0, pos, Node.Kind.HANDLER, y, null, null, Direction.SOUTH));
        if (north == null || north.contents().getFluid() != Fluids.WATER) {
            helper.fail("NORTH face did not resolve to water: "
                    + (north == null ? "null column" : north.contents().getFluid()));
            return;
        }
        if (south == null || south.contents().getFluid() != Fluids.LAVA) {
            helper.fail("SOUTH face did not resolve to lava — the access face is ignored in resolution");
            return;
        }
        TestSideHandlers.clear();
        helper.succeed();
    }

    /**
     * A side-specific handler is NOT coupled across faces: the pipe on each face lands in its own
     * network, reaching the block through its own face ({@link Node#accessFace}). Confirms the
     * coupling-skip (the south pipe never leaks into the north network) and the recorded face.
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100, batch = "relayDetector")
    public static void sideSpecificHandlerSplitsNetworksPerFace(GameTestHelper helper) {
        TestSideHandlers.clear();
        Level level = helper.getLevel();
        BlockPos spongeRel = new BlockPos(1, 2, 1);
        BlockPos spongePos = helper.absolutePos(spongeRel);
        helper.setBlock(spongeRel, Blocks.SPONGE);
        TestSideHandlers.tankAt(spongePos, Direction.NORTH).fill(
                new FluidStack(Fluids.WATER, 8000), IFluidHandler.FluidAction.EXECUTE);
        TestSideHandlers.tankAt(spongePos, Direction.SOUTH).fill(
                new FluidStack(Fluids.LAVA, 8000), IFluidHandler.FluidAction.EXECUTE);
        helper.setBlock(spongeRel.north(), AllBlocks.FLUID_PIPE.get());
        helper.setBlock(spongeRel.south(), AllBlocks.FLUID_PIPE.get());
        BlockPos southPipe = helper.absolutePos(spongeRel.south());
        Graph northGraph = GraphBuilder.build(level, helper.absolutePos(spongeRel.north()));
        Node sponge = northGraph.nodes().stream()
                .filter(n -> n.isHandler() && n.pos().equals(spongePos)).findFirst().orElse(null);
        if (sponge == null) {
            helper.fail("side-specific sponge was not discovered as a handler node from the north pipe");
            return;
        }
        if (sponge.accessFace() != Direction.NORTH) {
            helper.fail("sponge reached from the north pipe recorded accessFace " + sponge.accessFace()
                    + " (expected NORTH)");
            return;
        }
        if (northGraph.coverage().contains(southPipe)) {
            helper.fail("side-specific handler coupled its faces — the south pipe leaked into the north network");
            return;
        }
        TestSideHandlers.clear();
        helper.succeed();
    }

    /**
     * A block that DOES expose a null-side handler but hands back a DIFFERENT handler on one face is still
     * side-specific — the shape of TFMG's coke oven (creosote on the null side + non-top faces, CO2 on the
     * top). The old {@code sideAgnostic = (null cap exists)} test coupled it and read the null side, so a
     * pump on top of a coke oven saw the empty creosote tank and never pulled the CO2. With the identity
     * discriminator the top pipe records {@code accessFace = UP} and the node resolves the SECONDARY (CO2)
     * tank, not the null-side PRIMARY (creosote) one. The dev-only {@link TestSideHandlers} wet-sponge
     * reproduces the exact capability shape.
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100, batch = "relayDetector")
    public static void perFaceHandlerResolvesTopDespiteNullCap(GameTestHelper helper) {
        TestSideHandlers.clear();
        Level level = helper.getLevel();
        BlockPos rel = new BlockPos(1, 2, 1);
        BlockPos pos = helper.absolutePos(rel);
        helper.setBlock(rel, Blocks.WET_SPONGE);
        TestSideHandlers.primaryAt(pos).fill(     // creosote — the null side + non-top faces
                new FluidStack(Fluids.WATER, 8000), IFluidHandler.FluidAction.EXECUTE);
        TestSideHandlers.secondaryAt(pos).fill(   // CO2 — the top face only
                new FluidStack(Fluids.LAVA, 8000), IFluidHandler.FluidAction.EXECUTE);
        if (level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null) == null) {
            helper.fail("test fixture must expose a null-side handler (the coke-oven shape)");
            return;
        }
        helper.setBlock(rel.above(), AllBlocks.FLUID_PIPE.get());
        Graph graph = GraphBuilder.build(level, helper.absolutePos(rel.above()));
        Node node = graph.nodes().stream()
                .filter(n -> n.isHandler() && n.pos().equals(pos)).findFirst().orElse(null);
        if (node == null) {
            helper.fail("coke-oven-shaped block was not discovered as a handler node from the top pipe");
            return;
        }
        if (node.accessFace() != Direction.UP) {
            helper.fail("top pipe recorded accessFace " + node.accessFace()
                    + " (expected UP — the block was coupled via its null side instead of read per-face)");
            return;
        }
        BoundaryColumn column = BoundaryColumn.resolve(level, node);
        if (column == null || column.contents().getFluid() != Fluids.LAVA) {
            helper.fail("top face resolved to " + (column == null ? "null column" : column.contents().getFluid())
                    + " — expected the SECONDARY (top) tank, not the null-side PRIMARY");
            return;
        }
        TestSideHandlers.clear();
        helper.succeed();
    }

    /**
     * A side-specific block that is ALSO a relay (a machine that PRODUCES a fluid on one face and is
     * demoted to a bottomless one-way source — TFMG's coke oven, learned by the {@link RelayDetector}
     * because it spontaneously gains CO2) must still drain through its ACCESS FACE. {@code relayEndpoint}
     * used to build its column without the face, so the contents resolved through the correct handler but
     * {@code handler(level)} later hit the empty null side — solved flow, no transfer, a SOURCE_DRY stall
     * ("can pull the fluid but can't push it anywhere"). Here the WET_SPONGE fixture (LAVA on top,
     * WATER on null+sides) is forced to the relay role: the column must resolve the top LAVA AND keep
     * {@code accessFace=UP} so a real drain through {@code handler(level)} yields LAVA, not the null WATER.
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100, batch = "relayDetector")
    public static void relaySideSpecificDrainsThroughAccessFace(GameTestHelper helper) {
        TestSideHandlers.clear();
        FluidHandlerApi.setRole(Blocks.WET_SPONGE, FluidHandlerRole.RELAY);
        try {
            Level level = helper.getLevel();
            BlockPos rel = new BlockPos(1, 2, 1);
            BlockPos pos = helper.absolutePos(rel);
            helper.setBlock(rel, Blocks.WET_SPONGE);
            TestSideHandlers.primaryAt(pos).fill(     // null side + non-top faces
                    new FluidStack(Fluids.WATER, 8000), IFluidHandler.FluidAction.EXECUTE);
            TestSideHandlers.secondaryAt(pos).fill(   // top face — the produced fluid
                    new FluidStack(Fluids.LAVA, 8000), IFluidHandler.FluidAction.EXECUTE);
            Node node = new Node(0, pos, Node.Kind.HANDLER, pos.getY() + 0.5, null, null, Direction.UP);
            BoundaryColumn column = BoundaryColumn.resolve(level, node);
            if (column == null || column.contents().getFluid() != Fluids.LAVA) {
                helper.fail("relay column did not resolve the top (secondary) fluid: "
                        + (column == null ? "null" : column.contents().getFluid()));
                return;
            }
            if (column.accessFace() != Direction.UP) {
                helper.fail("relay column dropped its access face (was " + column.accessFace() + ")");
                return;
            }
            FluidStack drained = BoundaryColumn.drainMatching(column.handler(level),
                    new FluidStack(Fluids.LAVA, 1000), IFluidHandler.FluidAction.SIMULATE);
            if (drained.isEmpty() || drained.getFluid() != Fluids.LAVA) {
                helper.fail("relay handler(level) drained the null side, not the top — the SOURCE_DRY bug ("
                        + (drained.isEmpty() ? "empty" : drained.getFluid()) + ")");
                return;
            }
        } finally {
            FluidHandlerApi.clearRole(Blocks.WET_SPONGE);
            TestSideHandlers.clear();
        }
        helper.succeed();
    }

    /**
     * Create's own tanks are exempt: one legitimately fed by a second network from another side reads
     * as an external gain, so the detector must never demote a real reservoir type.
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100, batch = "relayDetector")
    public static void relayDetectorExemptsCreateTanks(GameTestHelper helper) {
        RelayDetector.clear();
        Level level = helper.getLevel();
        BlockPos rel = new BlockPos(1, 2, 1);
        BlockPos pos = helper.absolutePos(rel);
        helper.setBlock(rel, AllBlocks.FLUID_TANK.get());
        Block tank = level.getBlockState(pos).getBlock();
        for (int amount = 100; amount <= 900; amount += 100) {
            RelayDetector.observe(level, pos, Fluids.WATER, amount); // gains, but a Create tank is exempt
        }
        if (RelayDetector.isRelay(tank)) {
            helper.fail("a Create fluid tank was demoted to a relay despite the exemption");
            return;
        }
        RelayDetector.clear();
        helper.succeed();
    }

    /**
     * A display link reads a pipe network cell through the same server-side {@link PipeProbe} the
     * goggle uses, and every metric folds that into one non-empty line. Locks the source wiring:
     * probing a spun-up pump yields the pump-curve cap/lift, FLOW reflects the probed rate, and no
     * metric (pipe or pump) throws or renders blank. The link BE / GUI are Create's — verify those
     * visually in-game.
     */
    @GameTest(template = "piping/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
    public static void displaySourcesReportPipeAndPumpMetrics(GameTestHelper helper) {
        BlockPos source = new BlockPos(0, 1, 1);
        fill(helper, source, 8000);
        helper.runAfterDelay(20, () -> { // let the kinetics spin the pump up
            ServerLevel level = helper.getLevel();
            BlockPos pumpRel = null, pipeRel = null;
            for (int x = 0; x < 6; x++)
                for (int y = 0; y < 4; y++)
                    for (int z = 0; z < 4; z++) {
                        BlockPos rel = new BlockPos(x, y, z);
                        BlockState st = helper.getBlockState(rel);
                        if (st.getBlock() instanceof PumpBlock) pumpRel = rel;
                        else if (st.is(AllBlocks.FLUID_PIPE.get())) pipeRel = rel;
                    }
            if (pumpRel == null || pipeRel == null) { helper.fail("template has no pump/pipe"); return; }

            BlockPos pumpAbs = helper.absolutePos(pumpRel);
            float speed = level.getBlockEntity(pumpAbs) instanceof KineticBlockEntity k ? Math.abs(k.getSpeed()) : 0f;
            if (speed <= 0.01f) { helper.fail("pump is not spinning"); return; }
            double cap = speed * PipesNPhysicsConfig.PUMP_FLOW_PER_RPM.get();
            double canLift = speed * PipesNPhysicsConfig.PUMP_HEAD_PER_RPM.get();

            PipeStatusPayload pumpData = PipeProbe.probe(level, pumpAbs);
            PipeDisplayMetric.Readout pump = new PipeDisplayMetric.Readout(pumpData, cap, canLift);
            String capText = PipeDisplayMetric.CAPACITY.format(pump).getString();
            if (cap <= 0 || !capText.startsWith(LangNumberFormat.format(cap))) {
                helper.fail("pump capacity metric did not reflect the curve cap: " + capText);
                return;
            }
            if (!PipeDisplayMetric.FLOW.format(pump).getString().startsWith(LangNumberFormat.format(pumpData.mbPerTick()))) {
                helper.fail("pump flow metric did not reflect the probed rate");
                return;
            }
            for (PipeDisplayMetric m : PipeDisplayMetric.PUMP_METRICS)
                if (m.format(pump).getString().isEmpty()) { helper.fail("blank pump metric: " + m); return; }

            PipeStatusPayload pipeData = PipeProbe.probe(level, helper.absolutePos(pipeRel));
            PipeDisplayMetric.Readout pipe = new PipeDisplayMetric.Readout(pipeData, 0, 0);
            for (PipeDisplayMetric m : PipeDisplayMetric.PIPE_METRICS)
                if (m.format(pipe).getString().isEmpty()) { helper.fail("blank pipe metric: " + m); return; }

            helper.succeed();
        });
    }
}