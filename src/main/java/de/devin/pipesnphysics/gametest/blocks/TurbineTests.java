package de.devin.pipesnphysics.gametest.blocks;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.api.stress.BlockStressValues;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import de.devin.pipesnphysics.PipesNPhysics;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.engine.FlowSolver;
import de.devin.pipesnphysics.engine.graph.Graph;
import de.devin.pipesnphysics.engine.graph.GraphBuilder;
import de.devin.pipesnphysics.engine.net.PipeStatusPayload;
import de.devin.pipesnphysics.engine.probe.PipeProbe;
import de.devin.pipesnphysics.engine.turbine.PumpMode;
import de.devin.pipesnphysics.engine.turbine.PumpModeBehaviour;
import de.devin.pipesnphysics.engine.turbine.TurbineRating;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static de.devin.pipesnphysics.gametest.GameTestSupport.*;

/**
 * The Mechanical Pump run backwards: fluid falling through a pump dialed to TURBINE turns it into
 * a kinetic generator. The rigs cover the three things that decide whether that is a feature or a
 * crash — it turns on a real fall, it refuses a short one, and a chattering supply never flickers
 * its rotation hard enough for Create to destroy the block.
 */
@GameTestHolder(PipesNPhysics.ID)
@PrefixGameTestTemplate(false)
public class TurbineTests {
    // blocks/turbine_fall: source tank high, a riser down through a DOWN-facing pump, sink at the
    // bottom. Pinned from the NBT (template pos + (0,1,0), the GameTest floor layer).
    private static final BlockPos FALL_SINK = new BlockPos(0, 1, 0);
    private static final BlockPos FALL_TURBINE = new BlockPos(2, 2, 0);
    private static final BlockPos FALL_SOURCE = new BlockPos(0, 6, 0);
    private static final BlockPos FALL_OUTLET_PIPE = new BlockPos(1, 1, 0);

    // blocks/turbine_flat: two same-level tanks with the pump between them — under a block of fall.
    private static final BlockPos FLAT_SOURCE = new BlockPos(0, 1, 0);
    private static final BlockPos FLAT_TURBINE = new BlockPos(2, 1, 0);
    private static final BlockPos FLAT_OUTLET_PIPE = new BlockPos(3, 1, 0);
    private static final BlockPos FLAT_SINK = new BlockPos(4, 1, 0);

    /**
     * The headline: a column of water falling through a dialed turbine spins it up, and the water
     * keeps going. Both halves matter — a turbine that generated but walled its own pipe would be
     * a very confusing block.
     */
    @GameTest(template = "blocks/turbine_fall", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void fallingColumnSpinsTheTurbine(GameTestHelper helper) {
        helper.runAfterDelay(2, () -> dialTurbine(helper, FALL_TURBINE));
        int[] delivered = {0};
        keepTheFallRunning(helper, delivered, 160);
        helper.runAtTickTime(160, () -> {
            KineticBlockEntity turbine = kinetic(helper, FALL_TURBINE);
            if (turbine.getGeneratedSpeed() == 0) {
                helper.fail("the fall never spun the turbine up (generated speed 0)\n"
                        + dump(helper, helper.absolutePos(FALL_TURBINE)));
                return;
            }
            if (turbine.getSpeed() == 0) {
                helper.fail("the turbine generates but the network reads it as stopped");
                return;
            }
            if (delivered[0] <= 0) {
                helper.fail("the turbine turned but passed no water to the sink\n" + dump(helper));
                return;
            }
            if (turbine.getOrCreateNetwork().calculateCapacity() <= 0) {
                helper.fail("a turning turbine adds no stress capacity to its network");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Hold the rig in the state it is about: a STEADY fall. The head tank is topped up and the sink
     * emptied on a cadence, counting what it received on the way, so neither end can end the test
     * early — a dry source would leave the turbine coasting on its stop debounce, and a full sink
     * would stop the flow outright. Both happen at whatever rate the piping is tuned to, so without
     * this the tests read as pass/fail on the conductance numbers rather than on the turbine.
     */
    private static void keepTheFallRunning(GameTestHelper helper, int[] delivered, int untilTick) {
        for (int tick = 10; tick <= untilTick; tick += 10) {
            helper.runAtTickTime(tick, () -> {
                fill(helper, FALL_SOURCE, 8000);
                delivered[0] += amount(helper, FALL_SINK);
                drain(helper, FALL_SINK);
            });
        }
    }

    /**
     * The dual: a turbine takes its rated head out of the line, so a fall under that rating turns
     * nothing AND passes nothing. The reason must read as the FALL being short — not as a pump
     * being unable to lift, which is the same NO_HEAD status wearing the wrong story.
     */
    @GameTest(template = "blocks/turbine_flat", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void turbineNeedsItsRatedFall(GameTestHelper helper) {
        helper.runAfterDelay(2, () -> dialTurbine(helper, FLAT_TURBINE));
        helper.runAtTickTime(120, () -> {
            if (kinetic(helper, FLAT_TURBINE).getGeneratedSpeed() != 0) {
                helper.fail("under a block of fall turned a turbine rated for "
                        + TurbineRating.ratedHead() + " blocks");
                return;
            }
            if (amount(helper, FLAT_SINK) > 0) {
                helper.fail("water crossed a turbine the fall cannot turn: "
                        + amount(helper, FLAT_SINK) + " mB\n" + dump(helper));
                return;
            }
            PipeStatusPayload status = probe(helper, FLAT_OUTLET_PIPE);
            if (status.status() != PipeStatusPayload.STATUS_NO_HEAD) {
                helper.fail("expected NO_HEAD past the stalled turbine, got " + status.status());
                return;
            }
            if (status.statusDetail() != PipeStatusPayload.DETAIL_TURBINE_FALL) {
                helper.fail("a stalled turbine must read as too little FALL, not as a lift problem"
                        + " (detail " + status.statusDetail() + ")");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * The regression guard for every rig that uses an unpowered pump as a shutoff — most sharply
     * {@code fluidDoesNotTeleportAcrossClosedBarrier}, which splits one graph into two islands with
     * them. A pump left on its default dial must still be a WALL, and must say so as PUMP_OFF
     * rather than wearing the turbine's story.
     */
    @GameTest(template = "blocks/turbine_flat", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void unpoweredPumpStillWallsInPumpMode(GameTestHelper helper) {
        helper.runAtTickTime(120, () -> {
            Level level = helper.getLevel();
            Graph graph = GraphBuilder.build(level, helper.absolutePos(FLAT_OUTLET_PIPE));
            for (var node : graph.nodes()) {
                if (node.isPump() && FlowSolver.isTurbine(level, node)) {
                    helper.fail("a pump nobody dialed came up as a turbine");
                    return;
                }
            }
            if (amount(helper, FLAT_SINK) > 0) {
                helper.fail("an unpowered pump let water past: " + amount(helper, FLAT_SINK) + " mB");
                return;
            }
            PipeStatusPayload status = probe(helper, FLAT_OUTLET_PIPE);
            if (status.statusDetail() != PipeStatusPayload.DETAIL_PUMP_OFF) {
                helper.fail("an unpowered pump must read PUMP_OFF, got detail "
                        + status.statusDetail());
                return;
            }
            helper.succeed();
        });
    }

    /**
     * The safety property, asserted on the quantity Create actually breaks blocks over. A supply
     * that keeps cutting out and coming back must not chatter the generated rotation: every
     * on/off costs 5 flicker points against a decay of 1 per tick, and at 128 {@code
     * RotationPropagator} destroys the block. The debounce must hold the score near zero — and the
     * turbine must still be standing at the end.
     */
    @GameTest(template = "blocks/turbine_fall", templateNamespace = PipesNPhysics.ID, timeoutTicks = 400)
    public static void flickeringSupplyNeverBreaksTheTurbine(GameTestHelper helper) {
        helper.runAfterDelay(2, () -> dialTurbine(helper, FALL_TURBINE));
        int[] worstFlicker = {0};
        for (int tick = 10; tick < 300; tick += 3) {
            boolean supply = (tick / 3) % 2 == 0;
            helper.runAtTickTime(tick, () -> {
                if (supply) fill(helper, FALL_SOURCE, 8000); else drain(helper, FALL_SOURCE);
                if (helper.getLevel().getBlockEntity(helper.absolutePos(FALL_TURBINE))
                        instanceof KineticBlockEntity be) {
                    worstFlicker[0] = Math.max(worstFlicker[0], be.getFlickerScore());
                }
            });
        }
        helper.runAtTickTime(320, () -> {
            if (!(helper.getBlockState(FALL_TURBINE).getBlock() instanceof PumpBlock)) {
                helper.fail("the chattering supply destroyed the turbine block");
                return;
            }
            // 20 is four flips' worth: comfortably clear of the 128 break, and far below what an
            // undebounced turbine reaches when the flow stutters tick to tick.
            if (worstFlicker[0] > 20) {
                helper.fail("rotation chattered under a stuttering supply (flicker score reached "
                        + worstFlicker[0] + " of the 128 that breaks the block)");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * A turbine's output rises with what actually falls through it — no ceiling of its own — and a
     * pumped LOOP still cannot win. That holds without any clamp because a turbine converts its
     * RATED head, never the head the pump paid for: output is linear in throughput exactly as the
     * pump's stress cost is linear in RPM, so the ratio is the same at every speed. Checked against
     * Create's own registered pump impact rather than a copied constant.
     */
    @GameTest(template = "blocks/turbine_flat", templateNamespace = PipesNPhysics.ID, timeoutTicks = 100)
    public static void pumpFedLoopReturnsLessStressThanItCosts(GameTestHelper helper) {
        int rated = (int) TurbineRating.swallowMb();
        if (TurbineRating.stressUnits(rated * 4) <= TurbineRating.stressUnits(rated)) {
            helper.fail("a bigger fall must earn more — output stopped scaling past the rating");
            return;
        }

        double impact = BlockStressValues.getImpact(AllBlocks.MECHANICAL_PUMP.get());
        double pumpFlowPerRpm = PipesNPhysicsConfig.PUMP_FLOW_PER_RPM.get();
        for (int rpm : new int[] {8, 32, 64, 256}) {
            double cost = impact * rpm;
            // The most that pump could ever hand a turbine, ignoring the back-pressure it fights.
            double returned = TurbineRating.stressUnits((int) Math.ceil(rpm * pumpFlowPerRpm));
            if (returned >= cost) {
                helper.fail("a pump at " + rpm + " RPM costs " + cost + " su but its turbine returns "
                        + returned + " — a closed loop would be free power");
                return;
            }
        }
        helper.succeed();
    }

    /**
     * A pump PLACED now decides its own role: undriven, it is a turbine. No head measurement is
     * involved — a fall short of the rating passes nothing anyway — so the wall an unpowered pump
     * gives you survives, and only the case you would have dialed it for changes. A pump loaded
     * from an older save has no stored mode, reads PUMP, and is left alone (the sibling test).
     */
    @GameTest(template = "blocks/turbine_fall", templateNamespace = PipesNPhysics.ID, timeoutTicks = 260)
    public static void freshlyPlacedPumpTurbinesWithoutBeingDialed(GameTestHelper helper) {
        // Re-place the template's pump so its block entity is NEW — a template-loaded one carries
        // the pre-feature default, which is exactly what the flat-rig test pins.
        helper.runAfterDelay(2, () -> {
            BlockState pump = helper.getBlockState(FALL_TURBINE);
            helper.setBlock(FALL_TURBINE, Blocks.AIR.defaultBlockState());
            helper.setBlock(FALL_TURBINE, pump);
        });
        int[] delivered = {0};
        keepTheFallRunning(helper, delivered, 220);
        helper.runAtTickTime(220, () -> {
            Level level = helper.getLevel();
            BlockPos abs = helper.absolutePos(FALL_TURBINE);
            Graph graph = GraphBuilder.build(level, helper.absolutePos(FALL_OUTLET_PIPE));
            var node = graph.nodeAt(abs);
            if (node == null || !FlowSolver.isTurbine(level, node)) {
                helper.fail("a freshly placed, undriven pump did not resolve as a turbine");
                return;
            }
            if (kinetic(helper, FALL_TURBINE).getGeneratedSpeed() == 0) {
                helper.fail("the fall never spun up an undialed pump\n" + dump(helper, abs));
                return;
            }
            if (delivered[0] <= 0) {
                helper.fail("nothing reached the sink through an undialed turbine");
                return;
            }
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------------ helpers

    private static void dialTurbine(GameTestHelper helper, BlockPos rel) {
        PumpModeBehaviour mode = BlockEntityBehaviour.get(helper.getLevel(),
                helper.absolutePos(rel), PumpModeBehaviour.TYPE);
        if (mode == null) {
            helper.fail("no pump mode dial at " + rel);
            return;
        }
        mode.setValue(PumpMode.TURBINE.ordinal());
    }

    private static KineticBlockEntity kinetic(GameTestHelper helper, BlockPos rel) {
        if (helper.getLevel().getBlockEntity(helper.absolutePos(rel))
                instanceof KineticBlockEntity be) {
            return be;
        }
        helper.fail("no kinetic block entity at " + rel);
        throw new IllegalStateException("unreachable");
    }

    private static PipeStatusPayload probe(GameTestHelper helper, BlockPos rel) {
        ServerLevel level = helper.getLevel();
        return PipeProbe.probe(level, helper.absolutePos(rel));
    }
}
