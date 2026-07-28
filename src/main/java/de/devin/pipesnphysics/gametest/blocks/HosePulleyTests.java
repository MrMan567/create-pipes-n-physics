package de.devin.pipesnphysics.gametest.blocks;

import com.simibubi.create.content.fluids.transfer.FluidDrainingBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import de.devin.pipesnphysics.PipesNPhysics;
import de.devin.pipesnphysics.engine.boundary.OpenEndPipes;
import de.devin.pipesnphysics.engine.boundary.RelayDetector;
import de.devin.pipesnphysics.engine.store.PipeStore;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static de.devin.pipesnphysics.gametest.GameTestSupport.*;

/**
 * Hose pulley behaviour: a pulley keeps its identity and its supply across the world edits and
 * drainer restarts that happen around it in normal play.
 *
 * Rig {@code common/hose_pulley_intake}: a hose pulley over a 3x3 pond gravity-feeds a tank two
 * blocks below it. Deliberately PUMP-LESS — a pump between the pulley and the run shields it from
 * every failure mode here (its check valve refuses backflow, and its dead-headed suction line
 * settles fill-only, so it never pours into a reservoir), which makes a pumped rig useless as a
 * regression guard.
 */
@GameTestHolder(PipesNPhysics.ID)
@PrefixGameTestTemplate(false)
public class HosePulleyTests {
    // common/hose_pulley_intake pinned positions (template pos + (0,1,0))
    private static final BlockPos PULLEY = new BlockPos(2, 5, 2);
    private static final BlockPos TANK = new BlockPos(4, 3, 2);
    private static final BlockPos[] PIPES = {
            new BlockPos(3, 5, 2), new BlockPos(4, 5, 2), new BlockPos(4, 4, 2)};
    /** The 3x3 pond, in test-relative coordinates. */
    private static final int POND_Y = 2;

    /**
     * A pulley that is simply DOING ITS JOB must never be learned as a relay. Its column is a
     * SYNTHETIC bottomless stand-in ({@code PULLEY_CAPACITY_MB}), not a real stored amount, so it
     * reads the same 4,000,000 mB every tick no matter how much we draw — and {@link RelayDetector},
     * which assumes a capacitor only moves by the transfers WE apply, scores each drain as an
     * unexplained GAIN of exactly that much ({@code spontaneous = 4e6 - 4e6 - (-moved)}). Five ticks
     * of ordinary intake demoted the pulley for the rest of the session, and only breaking the block
     * ({@code RelayDetector.forget}) cleared it — the "works again after disconnect and reconnect"
     * signature. Demotion costs the pulley its {@code isHosePulley} identity and with it the sticky
     * output latch, so a demoted pulley sucks its own deposit straight back.
     *
     * The fix is general rather than a pulley exemption: only a FINITE RESERVOIR carries a real
     * stored reading, so only a finite reservoir is observed at all.
     */
    @GameTest(template = "common/hose_pulley_intake", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void workingPulleyIsNeverLearnedAsARelay(GameTestHelper helper) {
        helper.runAfterDelay(100, () -> {
            if (amount(helper, TANK) < 300) {
                helper.fail("pulley never started supplying: tank holds " + amount(helper, TANK) + " mB");
                return;
            }
            BlockPos pulley = helper.absolutePos(PULLEY);
            if (RelayDetector.isRelay(pulley)) {
                helper.fail("a working hose pulley was demoted to a learned relay — its synthetic "
                        + "bottomless column reads every drain as a spontaneous gain");
                return;
            }
            if (RelayDetector.strikeCount(pulley) > 0) {
                helper.fail("a working hose pulley accrued " + RelayDetector.strikeCount(pulley)
                        + " relay strikes; a synthetic column must not be observed at all");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * A drainer reset mid-operation — the state every trigger funnels into: a kinetic speed change,
     * a block edit near the body, Create's own revalidation — must be a hiccup, not the end. The
     * pulley may not be latched into output mode and the intake must resume on its own.
     */
    @GameTest(template = "common/hose_pulley_intake", templateNamespace = PipesNPhysics.ID, timeoutTicks = 300)
    public static void pulleyKeepsSupplyingThroughADrainerReset(GameTestHelper helper) {
        helper.runAfterDelay(80, () -> {
            int before = amount(helper, TANK);
            if (before < 300) {
                helper.fail("pulley never started supplying: tank holds " + before + " mB");
                return;
            }
            FluidDrainingBehaviour drainer = BlockEntityBehaviour.get(
                    helper.getLevel(), helper.absolutePos(PULLEY), FluidDrainingBehaviour.TYPE);
            if (drainer == null) {
                helper.fail("no draining behaviour on the pulley");
                return;
            }
            drainer.reset();
            drain(helper, TANK); // headroom, like a consumer drawing the tank down

            helper.runAfterDelay(100, () -> {
                if (OpenEndPipes.isPulleyOutput(helper.getLevel(), helper.absolutePos(PULLEY))) {
                    helper.fail("drainer reset latched the intake pulley into OUTPUT mode");
                    return;
                }
                int after = amount(helper, TANK);
                if (after < 300) {
                    helper.fail("intake did not resume after the drainer reset: tank refilled only "
                            + after + " mB (was " + before + " before the reset)");
                    return;
                }
                helper.succeed();
            });
        });
    }

    /**
     * The user's literal edit: solid blocks placed INTO the drained body, then the body restored.
     * The pulley must not be latched into output mode while it stands dry, and must resume drawing
     * once the water is back — with no break-and-replace.
     */
    @GameTest(template = "common/hose_pulley_intake", templateNamespace = PipesNPhysics.ID, timeoutTicks = 400)
    public static void pulleyResumesAfterItsBodyIsBuriedAndRestored(GameTestHelper helper) {
        helper.runAfterDelay(80, () -> {
            if (amount(helper, TANK) < 300) {
                helper.fail("pulley never started supplying: tank holds " + amount(helper, TANK) + " mB");
                return;
            }
            setPond(helper, Blocks.POLISHED_ANDESITE);
            int atDry = amount(helper, TANK);

            helper.runAfterDelay(120, () -> {
                if (OpenEndPipes.isPulleyOutput(helper.getLevel(), helper.absolutePos(PULLEY))) {
                    helper.fail("burying the body latched the pulley into OUTPUT mode");
                    return;
                }
                if (amount(helper, TANK) < atDry) {
                    helper.fail("tank lost fluid while the pulley stood dry");
                    return;
                }
                setPond(helper, Blocks.WATER);
                drain(helper, TANK); // headroom, like a consumer drawing the tank down

                helper.runAfterDelay(100, () -> {
                    int resumed = amount(helper, TANK);
                    if (resumed < 300) {
                        helper.fail("intake did not resume after the body returned: tank refilled only "
                                + resumed + " mB");
                        return;
                    }
                    helper.succeed();
                });
            });
        });
    }

    /**
     * A FRESH pulley plumbed into already-wet pipes: while its first body search walks (many ticks
     * over a large body) the wet run beside it must not settle-pour into it and pin the output
     * latch before it ever primes.
     */
    @GameTest(template = "common/hose_pulley_intake", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void freshPulleyIsNotLatchedWhilePlumbedWet(GameTestHelper helper) {
        for (BlockPos pipe : PIPES) {
            PipeStore.Store store = PipeStore.at(helper.getLevel(), helper.absolutePos(pipe));
            if (store == null) {
                helper.fail("no pipe store at " + pipe);
                return;
            }
            store.insert(new FluidStack(Fluids.WATER, 250), 250);
            store.flush();
        }
        helper.runAfterDelay(100, () -> {
            if (OpenEndPipes.isPulleyOutput(helper.getLevel(), helper.absolutePos(PULLEY))) {
                helper.fail("wet pipes latched the fresh pulley into OUTPUT mode during its first search");
                return;
            }
            if (amount(helper, TANK) < 300) {
                helper.fail("pulley never started supplying: tank holds " + amount(helper, TANK) + " mB");
                return;
            }
            helper.succeed();
        });
    }

    /** Fill the whole 3x3 pond with one block — the user's "placing blocks in the body" edit. */
    private static void setPond(GameTestHelper helper, Block block) {
        for (int x = 1; x <= 3; x++) {
            for (int z = 1; z <= 3; z++) {
                helper.setBlock(new BlockPos(x, POND_Y, z), block);
            }
        }
    }
}
