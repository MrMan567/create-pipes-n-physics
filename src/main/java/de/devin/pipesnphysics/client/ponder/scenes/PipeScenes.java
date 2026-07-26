package de.devin.pipesnphysics.client.ponder.scenes;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllFluids;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.fluids.pipes.FluidPipeBlock;
import com.simibubi.create.content.fluids.pipes.GlassFluidPipeBlock;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import de.devin.pipesnphysics.client.ponder.PnpPonderScene;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class PipeScenes extends PnpPonderScene {


    public static void overview(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("pipe_overview", "Pipes overview");
        var helper = new CreateSceneBuilder(scene);
        setupScene(6, scene);
        freezeEngine(scene);

        BlockState pipeStateX = AllBlocks.GLASS_FLUID_PIPE.getDefaultState()
            .setValue(GlassFluidPipeBlock.AXIS, Direction.Axis.X);
        BlockState pipeStateZ = AllBlocks.GLASS_FLUID_PIPE.getDefaultState()
            .setValue(GlassFluidPipeBlock.AXIS, Direction.Axis.Z);
        BlockState branchStub = AllBlocks.FLUID_PIPE.getDefaultState()
            .setValue(FluidPipeBlock.SOUTH, false)
            .setValue(FluidPipeBlock.NORTH, false)
            .setValue(FluidPipeBlock.EAST, false)
            .setValue(FluidPipeBlock.WEST, false);

        var tankA = util.select().fromTo(0, 1, 3, 0, 2, 3);
        var tankB = util.select().fromTo(5, 1, 2, 5, 2, 2);
        var run = util.select().fromTo(4, 1, 2, 4, 1, 1)
            .add(util.select().fromTo(3, 1, 1, 2, 1, 1))
            .add(util.select().fromTo(2, 1, 2, 2, 1, 3))
            .add(util.select().position(1, 1, 3));
        var pumpPos = util.grid().at(2, 1, 2);
        var driveCog = util.select().position(1, 1, 2);

        reveal(scene, tankB, Direction.DOWN);
        scene.idle(5);
        reveal(scene, tankA, Direction.DOWN);
        scene.idle(5);
        reveal(scene, run, Direction.DOWN);
        scene.idle(10);
        narrate(scene, "Pipes carry fluid between tanks and machines", util.vector().topOf(3, 1, 1));

        showClickWithItemAt(scene, util, util.grid().at(3, 1, 1), AllItems.WRENCH.asStack());
        scene.idle(7);
        scene.world().replaceBlocks(util.select().position(3, 1, 1), pipeStateX, true);
        scene.idle(15);
        showClickWithItemAt(scene, util, util.grid().at(2, 1, 2), AllItems.WRENCH.asStack());
        scene.idle(7);
        scene.world().replaceBlocks(util.select().position(2, 1, 2), pipeStateZ, true);
        scene.idle(10);
        narrate(scene, "A Wrench turns a straight pipe into a windowed one, showing the fluid inside", util.vector().centerOf(2, 1, 2));

        scene.world().setBlock(util.grid().at(3, 1, 2), branchStub, true);
        Vec3 center = util.vector().centerOf(3, 1, 2);
        AABB bb = new AABB(center, center).inflate(1 / 6f);
        AABB bb1 = bb.move(-0.5, 0, 0);
        AABB bb2 = bb.move(0, 0, -0.5);
        scene.idle(10);
        scene.overlay().chaseBoundingBoxOutline(PonderPalette.RED, bb1, bb, 1);
        scene.overlay().chaseBoundingBoxOutline(PonderPalette.RED, bb2, bb, 1);
        scene.idle(1);
        scene.overlay().chaseBoundingBoxOutline(PonderPalette.RED, bb1, bb1, 50);
        scene.overlay().chaseBoundingBoxOutline(PonderPalette.RED, bb2, bb2, 50);
        scene.idle(10);
        narrate(scene, "Windowed pipes only run straight through, they cannot branch sideways", util.vector().centerOf(2, 1, 2), PonderPalette.RED);

        scene.world().setBlock(util.grid().at(3, 1, 2), Blocks.AIR.defaultBlockState(), false);
        scene.world().restoreBlocks(util.select().position(3, 1, 1));
        scene.idle(10);

        fillTankAt(scene, util.grid().at(5, 1, 2), Fluids.WATER, 12000);
        scene.world().setBlock(pumpPos, AllBlocks.MECHANICAL_PUMP.getDefaultState()
            .setValue(PumpBlock.FACING, Direction.SOUTH), true);
        reveal(scene, driveCog, Direction.EAST);
        scene.idle(10);
        applyKineticSpeedAt(helper, util, driveCog, -16);
        applyKineticSpeedAt(helper, util, util.select().position(pumpPos), 16);
        helper.effects().rotationDirectionIndicator(util.grid().at(1, 1, 2));
        unfreezeEngine(scene);
        scene.idle(10);
        narrate(scene, "A powered pump then drives the fluid through the run", util.vector().topOf(pumpPos));
        narrate(scene, "It takes time to travel, filling one pipe after another", util.vector().centerOf(3, 1, 1));
        narrate(scene, "Unlike in vanilla Create, each pipe really holds fluid, up to 250 mB", util.vector().centerOf(2, 1, 1));
        waitSeconds(scene, 3);
    }

}
