package de.devin.pipesnphysics.gametest.blocks;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;
import de.devin.pipesnphysics.PipesNPhysics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static de.devin.pipesnphysics.gametest.GameTestSupport.*;

/**
 * Create's SMART FLUID PIPE — a straight pipe with a fluid filter — against a BASIN, on lines with
 * NO pump. The engine consults the filter through Create's own {@code canPullFluidFrom}
 * ({@code FluidPass.runAcceptsFluid}), and a rejected fluid blocks the whole run for that fluid's
 * pass; a basin is where that gets interesting, because it is the one endpoint that holds SEVERAL
 * fluids at once, so a filtered line off it must pass one and wall the other in the same tick.
 *
 * The pumped separation rig is covered by {@code multiFluidBasinSeparatesCompletely} (template
 * {@code common/multi_fluid_basin}, two filtered lines flush against a basin). These are its
 * PUMP-LESS twins in both directions — a pump changes the picture completely (its check valves,
 * its EMF, and the pump-driven settle paths), so gravity needs its own guard.
 *
 * Both rigs are built over {@code common/single_pump}: its pump becomes the smart pipe, so the run
 * is basin/tank — pipe — smart pipe — pipe — tank with nothing driving it but the levels.
 */
@GameTestHolder(PipesNPhysics.ID)
@PrefixGameTestTemplate(false)
public class SmartPipeTests {
    // common/single_pump pinned positions (template pos + (0,1,0)).
    private static final BlockPos WEST_END = new BlockPos(0, 1, 1);
    private static final BlockPos PUMP = new BlockPos(2, 1, 1);
    private static final BlockPos EAST_END = new BlockPos(4, 1, 1);

    /**
     * A basin holding two fluids, gravity-feeding a tank through a water-filtered smart pipe: the
     * water must arrive and the milk must stay put. Water alone reaching the tank is not enough —
     * an unfiltered pipe would pass both, so the milk assertion is what proves the filter is read.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 400)
    public static void smartPipeFiltersAGravityLineOffABasin(GameTestHelper helper) {
        Fluid milk = NeoForgeMod.MILK.value();
        helper.setBlock(WEST_END, AllBlocks.BASIN.get());
        placeSmartPipe(helper, PUMP, Items.WATER_BUCKET);
        drain(helper, EAST_END);

        helper.runAfterDelay(5, () -> {
            BasinBlockEntity basin = (BasinBlockEntity) helper.getBlockEntity(WEST_END);
            var internal = (SmartFluidTankBehaviour.InternalFluidHandler) basin.inputTank.getCapability();
            internal.forceFill(new FluidStack(Fluids.WATER, 1000), IFluidHandler.FluidAction.EXECUTE);
            internal.forceFill(new FluidStack(milk, 1000), IFluidHandler.FluidAction.EXECUTE);
        });

        helper.runAtTickTime(300, () -> {
            FluidStack arrived = handler(helper, EAST_END).getFluidInTank(0);
            if (!arrived.isEmpty() && arrived.getFluid() == milk) {
                helper.fail("milk crossed a water-filtered smart pipe — the filter is not being read"
                        + dump(helper, PUMP));
                return;
            }
            if (arrived.getAmount() < 500) {
                helper.fail("the water-filtered gravity line off the basin moved "
                        + arrived.getAmount() + " mB of its 1000" + dump(helper, PUMP));
                return;
            }
            if (basinFluid(helper, WEST_END, milk) != 1000) {
                helper.fail("the basin's milk went somewhere: "
                        + basinFluid(helper, WEST_END, milk) + "/1000 mB left" + dump(helper, PUMP));
                return;
            }
            helper.succeed();
        });
    }

    /**
     * The dual: a tank gravity-feeding a BASIN through a smart pipe filtered to the fluid it
     * carries. A basin is a receive-only sink here (it holds nothing), and its own head is what
     * the water falls toward — the filter must not wall a fluid it accepts.
     */
    @GameTest(template = "common/single_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 400)
    public static void smartPipeFillsABasinItAccepts(GameTestHelper helper) {
        helper.setBlock(EAST_END, AllBlocks.BASIN.get());
        placeSmartPipe(helper, PUMP, Items.WATER_BUCKET);
        fill(helper, WEST_END, 8000);

        helper.runAtTickTime(300, () -> {
            int arrived = basinFluid(helper, EAST_END, Fluids.WATER);
            if (arrived <= 0) {
                helper.fail("nothing reached the basin through a water-filtered smart pipe"
                        + dump(helper, PUMP));
                return;
            }
            helper.succeed();
        });
    }

    /** Swap in a smart fluid pipe along the rig's X axis and set its fluid filter. */
    private static void placeSmartPipe(GameTestHelper helper, BlockPos rel, net.minecraft.world.item.Item filter) {
        BlockState smart = AllBlocks.SMART_FLUID_PIPE.getDefaultState()
                .setValue(FaceAttachedHorizontalDirectionalBlock.FACE, AttachFace.FLOOR)
                .setValue(FaceAttachedHorizontalDirectionalBlock.FACING, Direction.EAST);
        helper.setBlock(rel, smart);
        FilteringBehaviour behaviour = BlockEntityBehaviour.get(
                helper.getLevel(), helper.absolutePos(rel), FilteringBehaviour.TYPE);
        if (behaviour == null) {
            helper.fail("no filtering behaviour on the smart pipe at " + rel);
            return;
        }
        behaviour.setFilter(new ItemStack(filter));
        // The neighbours were shaped against whatever stood here before, so recompute their own
        // connection state — setBlock only re-shapes outward.
        for (Direction side : Direction.values()) {
            BlockPos abs = helper.absolutePos(rel).relative(side);
            helper.getLevel().setBlock(abs, Block.updateFromNeighbourShapes(
                    helper.getLevel().getBlockState(abs), helper.getLevel(), abs), 3);
        }
    }
}
