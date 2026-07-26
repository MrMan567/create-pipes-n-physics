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
import net.minecraft.world.level.material.Fluids;

public class PhysicScenes extends PnpPonderScene {

    public static void equalizingFluids(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("equalizing_fluids", "Fluid Levels Equalize");
        var helper = new CreateSceneBuilder(scene);
        setupScene(5, scene);
        freezeEngine(scene);

        var sourceTank = util.select().fromTo(4, 1, 2, 4, 3, 2);
        var sinkTank = util.select().fromTo(1, 1, 3, 1, 3, 3);
        var pipes = util.select().fromTo(3, 1, 2, 1, 1, 2);

        reveal(scene, sourceTank, Direction.DOWN);
        fillTankAt(scene, util.grid().at(4, 1, 2), Fluids.WATER, 20000);
        scene.idle(10);
        narrate(scene, "The higher the fluid stands, the harder it presses at the bottom", util.vector().topOf(4, 3, 2));

        reveal(scene, pipes, Direction.EAST);
        scene.idle(5);
        reveal(scene, sinkTank, Direction.DOWN);
        scene.idle(10);
        narrate(scene, "Connected by a pipe, the higher level pushes fluid toward the lower one", util.vector().centerOf(2, 1, 2));

        unfreezeEngine(scene);
        waitSeconds(scene, 2);

        rotateCamera(scene, 35, 35);
        zoomTo(scene, 2, 20);
        narrate(scene, "The flow slows down as the levels approach each other", util.vector().centerOf(2, 1, 2));
        narrate(scene, "and stops once both surfaces stand at the same height", util.vector().topOf(1, 3, 3));
        waitSeconds(scene, 2);
    }

    public static void siphon(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("siphon", "Draining over an Obstacle: The Siphon");
        var helper = new CreateSceneBuilder(scene);
        setupScene(6, scene);
        freezeEngine(scene);

        var ridge = util.select().fromTo(2, 1, 2, 2, 3, 2);
        var source = util.select().fromTo(5, 1, 2, 5, 2, 2);
        var sink = util.select().position(0, 1, 2);
        var drawPipes = util.select().fromTo(4, 1, 2, 3, 1, 2);
        var riser = util.select().fromTo(3, 2, 2, 3, 3, 2);
        var crest = util.select().fromTo(3, 4, 2, 1, 4, 2);
        var descent = util.select().fromTo(1, 3, 2, 1, 1, 2);
        var drive = util.select().fromTo(3, 1, 1, 3, 2, 1);
        var pumpSpot = util.grid().at(3, 2, 2);

        // the problem: a full tank, a ridge in the way, an empty tank beyond
        reveal(scene, ridge, Direction.DOWN);
        waitDefaultDelay(scene);
        reveal(scene, source, Direction.DOWN);
        fillTankAt(scene, new BlockPos(5, 1, 2), Fluids.WATER, 12000);
        scene.idle(10);
        reveal(scene, sink, Direction.DOWN);
        narrate(scene, "This full tank must drain past the ridge", util.vector().topOf(5, 2, 2));

        // the pipe arrives along its own path
        reveal(scene, drawPipes, Direction.WEST);
        scene.idle(5);
        reveal(scene, riser, Direction.DOWN);
        scene.idle(5);
        reveal(scene, crest, Direction.EAST);
        scene.idle(5);
        reveal(scene, descent, Direction.UP);
        scene.idle(10);
        narrate(scene, "The pipe climbs over the ridge. Its highest point is called the crest", util.vector().centerOf(2, 4, 2));

        // failure first: nothing flows through a dry crest
        unfreezeEngine(scene);
        waitDefaultDelay(scene);
        highlightBox(scene, crest, PonderPalette.RED, 100);
        narrate(scene, "Nothing flows yet, because the pipe over the crest is dry", util.vector().centerOf(2, 4, 2), PonderPalette.RED);
        narrate(scene, "Fluid cannot climb into an empty pipe on its own", util.vector().centerOf(3, 3, 2));
        goggleTooltip(scene, util, util.grid().at(3, 3, 2), 110,
                goggleHeader("gui.goggles.pipe_stats"),
                goggleLine("gui.goggles.detail.crest", ChatFormatting.GOLD));

        // priming: machinery arrives, a pump establishes the column
        reveal(scene, drive, Direction.SOUTH);
        scene.idle(10);
        scene.world().setBlock(pumpSpot,
                AllBlocks.MECHANICAL_PUMP.getDefaultState().setValue(PumpBlock.FACING, Direction.UP), true);
        applyKineticSpeedAt(helper, util, drive, -16);
        applyKineticSpeedAt(helper, util, util.select().position(pumpSpot), 16);
        helper.effects().rotationDirectionIndicator(util.grid().at(3, 2, 1));
        scene.idle(10);
        chase(scene, PonderPalette.INPUT, util.vector().centerOf(3, 1, 2), util.vector().centerOf(3, 4, 2), 40);
        narrate(scene, "A pump whose lift reaches the crest can fill the line. This is called priming", util.vector().topOf(pumpSpot));
        chase(scene, PonderPalette.OUTPUT, util.vector().centerOf(1, 4, 2), util.vector().centerOf(1, 1, 2), 40);
        waitSeconds(scene, 2);

        // the payoff: remove the pump, the siphon carries on
        scene.world().restoreBlocks(util.select().position(pumpSpot));
        hide(scene, drive, Direction.SOUTH);
        scene.idle(10);
        narrate(scene, "Once primed, the pump is no longer needed", util.vector().topOf(pumpSpot), PonderPalette.GREEN);
        narrate(scene, "This is a siphon. The height difference keeps it flowing on its own", util.vector().centerOf(2, 4, 2));
        narrate(scene, "It runs until the surfaces level out, or air enters the pipe", util.vector().topOf(0, 1, 2));

        waitSeconds(scene, 3);
    }

}
