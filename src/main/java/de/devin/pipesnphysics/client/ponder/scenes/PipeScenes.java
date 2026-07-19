package de.devin.pipesnphysics.client.ponder.scenes;

import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import de.devin.pipesnphysics.client.ponder.PnpPonderScene;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

public class PipeScenes extends PnpPonderScene {

    private PipeScenes() {}

    public static void basics(SceneBuilder scene, SceneBuildingUtil util) {
        var helper = new CreateSceneBuilder(scene);
        setupScene(6, scene);

        reveal(scene, util.select().layersFrom(1), Direction.DOWN);
        waitDefaultDelay(scene);

        narrateAbove(scene, util, "A Mechanical Pump moves fluid through the network", util.grid().at(2, 1, 2));
        waitDefaultDelay(scene);

        var pumpPos = util.select().position(2,1,2);

        applyKineticSpeedAt(helper, util, pumpPos, 32.f);

        narrateAbove(scene, util, "To move fluids you need to speed up your pipes", util.grid().at(2, 1, 2));

        waitDefaultDelay(scene);

        fillTankAt(helper, new BlockPos(5,1,4), Fluids.WATER, 8000);

        narrateAbove(scene, util, "A pump rotating at fast speeds can push more fluid but will drain more SU.", util.grid().at(2, 1, 2));

        waitDefaultDelay(scene);
    }


    public static void gravity(SceneBuilder scene, SceneBuildingUtil util) {
        var helper = new CreateSceneBuilder(scene);
        setupScene(6, scene);

        waitDefaultDelay(scene);

        freezeEngine(scene);

        var highTank = util.select().fromTo(new BlockPos(0,1,4), new BlockPos(0,4,4));

        reveal(scene, highTank, Direction.DOWN);
        waitDefaultDelay(scene);

        narrateAbove(scene, util, "Fluids will flow down to the lowest point they can reach", util.grid().at(0, 3, 4));

        fillTankAt(scene, new BlockPos(0,3,4), Fluids.WATER, 8000);
        waitDefaultDelay(scene);

        var lowTank1 = util.select().fromTo(new BlockPos(0,1,1), new BlockPos(0,3,3));
        var lowTank2 = util.select().fromTo(new BlockPos(5,1,4), new BlockPos(1,3,4));

        reveal(scene, lowTank1, Direction.DOWN);
        reveal(scene, lowTank2, Direction.DOWN);

        waitDefaultDelay(scene);
        unfreezeEngine(scene);


        narrateAbove(scene, util, "When fluids are at the lowest position they will rest, until they are pushed by a pump.", util.grid().at(0, 3, 4));


    }

}
