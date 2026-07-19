package de.devin.pipesnphysics.client.ponder.scenes;

import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import de.devin.pipesnphysics.client.ponder.PnpPonderScene;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.Direction;
import net.minecraft.world.level.material.Fluids;

public class PhysicScenes extends PnpPonderScene {

    public static void equalizingFluids(SceneBuilder scene, SceneBuildingUtil util) {
        var helper = new CreateSceneBuilder(scene);
        setupScene(5, scene);

        freezeEngine(scene);

        //reveal(scene, util.select().layersFrom(0), Direction.DOWN);

        var sourceTank = util.select().fromTo(4, 1, 2, 4, 3, 2);
        var sinkTank = util.select().fromTo(1, 1, 3, 1, 3, 3);

        reveal(scene, sourceTank, Direction.DOWN);
        fillTankAt(scene, util.grid().at(4,1,2), Fluids.WATER, 4000);

        waitDefaultDelay(scene);

        highlightBox(scene, sourceTank, PonderPalette.GREEN, TEXT_DURATION);
        narrateAbove(scene, util, "Fluids inside a tank will produce pressure. The denser the fluid, the more pressure it applies.", util.grid().at(4, 2, 2));


        var pipes = util.select().fromTo(3, 1, 2, 1, 1, 2);

        reveal(scene, pipes, Direction.DOWN);
        waitDefaultDelay(scene);

        highlightBox(scene, pipes, PonderPalette.GREEN, TEXT_DURATION);
        narrateAbove(scene, util, "When using pipes to connect two tanks", util.grid().at(2, 2, 2));

        reveal(scene, sinkTank, Direction.DOWN);

        narrateAbove(scene, util, "The pressure and therefore fluid level will equalize.", util.grid().at(2, 1, 2));
        unfreezeEngine(scene);

        waitSeconds(scene, 1);

        narrateAbove(scene, util, "It will take some time until the levels have settled.", util.grid().at(2, 1, 2));

        rotateCamera(scene, 35, 35);
        zoomTo(scene, 2, 20);

        narrateAbove(scene, util, "The pipes will display visually if any fluid is still moving.", util.grid().at(2, 1, 2));

        waitDefaultDelay(scene);
    }

}
