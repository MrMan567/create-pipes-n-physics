package de.devin.pipesnphysics.client.ponder.scenes;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import de.devin.pipesnphysics.client.ponder.PnpPonderScene;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

public class PumpScenes extends PnpPonderScene {

    private PumpScenes() {}

    public static void basics(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("pump_intro", "Moving Fluids with Pumps");
        var helper = new CreateSceneBuilder(scene);
        setupScene(6, scene);
        freezeEngine(scene);

        var sourceTank = util.select().fromTo(5, 1, 4, 5, 2, 4);
        var targetTank = util.select().fromTo(0, 1, 4, 0, 2, 4);
        var suctionLeg = util.select().fromTo(4, 1, 4, 3, 1, 2);
        var pushLeg = util.select().fromTo(1, 1, 4, 1, 1, 2);
        var pumpPos = util.grid().at(2, 1, 2);
        var driveCog = util.select().position(2, 1, 1);

        reveal(scene, sourceTank, Direction.DOWN);
        fillTankAt(helper, new BlockPos(5, 1, 4), Fluids.WATER, 12000);
        scene.idle(10);
        narrate(scene, "Fluid sits in a tank until something moves it", util.vector().topOf(5, 2, 4));

        reveal(scene, suctionLeg, Direction.NORTH);
        scene.idle(5);
        reveal(scene, util.select().position(pumpPos), Direction.DOWN);
        scene.idle(5);
        reveal(scene, pushLeg, Direction.SOUTH);
        scene.idle(5);
        reveal(scene, targetTank, Direction.DOWN);
        scene.idle(10);

        narrate(scene, "A Mechanical Pump moves it through pipes, out of the side its arrow points", util.vector().topOf(pumpPos));
        chase(scene, PonderPalette.INPUT, util.vector().centerOf(3, 1, 4), util.vector().centerOf(3, 1, 2), 40);
        chase(scene, PonderPalette.OUTPUT, util.vector().centerOf(1, 1, 2), util.vector().centerOf(1, 1, 4), 40);

        unfreezeEngine(scene);
        reveal(scene, driveCog, Direction.NORTH);
        scene.idle(10);
        applyKineticSpeedAt(helper, util, driveCog, -32);
        applyKineticSpeedAt(helper, util, util.select().position(pumpPos), 32);
        helper.effects().rotationDirectionIndicator(util.grid().at(2, 1, 1));
        scene.idle(10);
        narrate(scene, "It only works while rotation powers it", util.vector().topOf(2, 1, 1));
        narrate(scene, "Watch the fluid travel, filling each pipe on its way", util.vector().centerOf(3, 1, 3));
        narrate(scene, "Faster rotation moves more fluid, but costs more Stress Units", util.vector().topOf(pumpPos));
        waitSeconds(scene, 3);
    }

    public static void pumpingUphill(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("pump_vertically", "Pumping Fluids Uphill");
        var helper = new CreateSceneBuilder(scene);
        setupScene(6, scene);
        freezeEngine(scene);

        var source = util.select().fromTo(5, 1, 2, 5, 2, 2);
        var sinkTower = util.select().fromTo(0, 1, 2, 0, 6, 2);
        var lowRun = util.select().fromTo(4, 1, 2, 3, 1, 2);
        var riser = util.select().fromTo(3, 2, 2, 3, 4, 2);
        var topRun = util.select().fromTo(3, 5, 2, 1, 5, 2);
        var climb = util.select().fromTo(3, 1, 2, 3, 5, 2);
        var mainDrive = util.select().fromTo(3, 1, 1, 3, 3, 1);
        var boosterCog = util.select().position(4, 1, 1);
        var pumpPos = util.grid().at(3, 3, 2);
        var boosterPos = util.grid().at(4, 1, 2);

        // the problem: a supply below, a tank far above
        reveal(scene, source, Direction.DOWN);
        fillTankAt(helper, new BlockPos(5, 1, 2), Fluids.WATER, 14000);
        scene.idle(10);
        reveal(scene, sinkTower, Direction.DOWN);
        narrate(scene, "This tank sits high above the supply", util.vector().topOf(0, 6, 2));

        reveal(scene, lowRun, Direction.WEST);
        scene.idle(5);
        reveal(scene, riser, Direction.DOWN);
        scene.idle(5);
        reveal(scene, topRun, Direction.EAST);
        scene.idle(10);

        highlightBox(scene, climb, PonderPalette.GREEN, 120);
        narrate(scene, "Only the height of the climb matters, not the length of the pipe", util.vector().centerOf(3, 3, 2));
        narrate(scene, "Pumps push far better than they pull, so keep them close to the supply", util.vector().topOf(pumpPos));

        // a slow pump stalls below the crest
        unfreezeEngine(scene);
        reveal(scene, mainDrive, Direction.SOUTH);
        scene.idle(10);
        applyKineticSpeedAt(helper, util, mainDrive, -8);
        applyKineticSpeedAt(helper, util, util.select().position(pumpPos), 8);
        helper.effects().rotationDirectionIndicator(util.grid().at(3, 3, 1));
        scene.idle(10);
        narrate(scene, "At low speed this pump cannot lift the fluid all the way", util.vector().topOf(pumpPos));
        waitSeconds(scene, 2);
        goggleTooltip(scene, util, util.grid().at(3, 4, 2), 110,
                goggleHeader("gui.goggles.pipe_stats"),
                goggleLine("gui.goggles.no_head", ChatFormatting.RED));

        // a second pump in series adds its lift
        reveal(scene, boosterCog, Direction.SOUTH);
        scene.world().setBlock(boosterPos,
                AllBlocks.MECHANICAL_PUMP.getDefaultState().setValue(PumpBlock.FACING, Direction.WEST), true);
        applyKineticSpeedAt(helper, util, boosterCog, -8);
        applyKineticSpeedAt(helper, util, util.select().position(boosterPos), 8);
        helper.effects().rotationDirectionIndicator(util.grid().at(4, 1, 1));
        scene.idle(10);
        narrate(scene, "A second pump on the same line adds its lift on top", util.vector().topOf(boosterPos), PonderPalette.GREEN);
        waitSeconds(scene, 3);
        narrate(scene, "Together they reach the top, faster rotation would as well", util.vector().topOf(0, 6, 2));

        waitSeconds(scene, 2);
    }


    public static void gravity(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("pipe_gravity", "Fluids Seek the Lowest Point");
        var helper = new CreateSceneBuilder(scene);
        setupScene(6, scene);
        freezeEngine(scene);

        var tower = util.select().fromTo(0, 1, 4, 0, 4, 4);
        var eastDown = util.select().fromTo(1, 3, 4, 3, 3, 4).add(util.select().fromTo(3, 1, 4, 4, 2, 4));
        var westDown = util.select().fromTo(0, 3, 3, 0, 3, 2).add(util.select().fromTo(0, 1, 2, 0, 2, 2));
        var groundTank = util.select().fromTo(5, 1, 4, 5, 2, 4);
        var smallTank = util.select().position(0, 1, 1);

        reveal(scene, tower, Direction.DOWN);
        fillTankAt(scene, new BlockPos(0, 3, 4), Fluids.WATER, 14000);
        scene.idle(10);
        narrate(scene, "Fluid stored high up wants to come down", util.vector().topOf(0, 4, 4));

        reveal(scene, eastDown, Direction.WEST);
        scene.idle(5);
        reveal(scene, groundTank, Direction.DOWN);
        scene.idle(5);
        reveal(scene, westDown, Direction.UP);
        scene.idle(5);
        reveal(scene, smallTank, Direction.DOWN);
        scene.idle(10);

        unfreezeEngine(scene);
        narrate(scene, "Through pipes it flows downhill on its own, no pump needed", util.vector().centerOf(2, 3, 4));
        waitSeconds(scene, 3);
        narrate(scene, "It rests at the lowest spot it can reach, until something pushes it", util.vector().topOf(5, 2, 4));

        rotateCamera(scene, -50);
        zoomTo(scene, 1.5f, 30);
        waitSeconds(scene, 2);

        highlightBox(scene, util.select().position(0, 3, 3), PonderPalette.RED, 100);
        narrate(scene, "A tank only drains while its fluid stands above the pipe opening", util.vector().centerOf(0, 3, 3));
        narrate(scene, "Fluid below the opening stays behind, so connect as low as you can", util.vector().centerOf(0, 3, 4));
        waitSeconds(scene, 2);
    }

}
