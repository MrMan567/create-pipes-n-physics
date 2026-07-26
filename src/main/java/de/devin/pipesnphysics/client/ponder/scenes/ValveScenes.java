package de.devin.pipesnphysics.client.ponder.scenes;

import com.simibubi.create.content.fluids.pipes.valve.FluidValveBlock;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.WorldSectionElement;
import de.devin.pipesnphysics.client.ponder.PnpPonderScene;
import de.devin.pipesnphysics.engine.valve.ValveFlowMode;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.material.Fluids;

public class ValveScenes extends PnpPonderScene {

    private ValveScenes() {}

    public static void throttle(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("valve_throttle", "Controlling Flow with Valves");
        var helper = new CreateSceneBuilder(scene);
        setupScene(6, scene);
        freezeEngine(scene);

        var sourceTank = util.select().fromTo(5, 1, 2, 5, 2, 2);
        var sinkTank = util.select().fromTo(0, 1, 2, 0, 2, 2);
        var run = util.select().fromTo(4, 1, 2, 1, 1, 2);
        var handle = util.select().position(3, 1, 1);
        var valvePos = util.grid().at(3, 1, 2);
        var valveSel = util.select().position(3, 1, 2);

        setValveOpening(scene, valvePos, 90);

        reveal(scene, sourceTank, Direction.DOWN);
        fillTankAt(scene, new BlockPos(5, 1, 2), Fluids.WATER, 14000);
        scene.idle(10);
        reveal(scene, run, Direction.DOWN);
        scene.idle(5);
        reveal(scene, sinkTank, Direction.DOWN);
        scene.idle(10);


        unfreezeEngine(scene);
        narrate(scene, "A Fluid Valve sits in a pipe and starts fully open", util.vector().topOf(valvePos));
        waitSeconds(scene, 2);

        ElementLink<WorldSectionElement> handleLink =
                scene.world().showIndependentSection(handle, Direction.SOUTH);
        scene.idle(10);
        applyKineticSpeedAt(helper, util, valveSel, 16);
        scene.world().rotateSection(handleLink, 0, 0, 90, 19);
        helper.effects().rotationSpeedIndicator(util.grid().at(3, 1, 1));
        setValveOpening(scene, valvePos, 0);
        scene.world().modifyBlock(valvePos, s -> s.setValue(FluidValveBlock.ENABLED, false), false);
        scene.idle(19);
        applyKineticSpeedAt(helper, util, valveSel, 0);
        narrate(scene, "Turning the handle winds the valve shut, and the flow stops", util.vector().topOf(valvePos), PonderPalette.RED);
        narrate(scene, "It holds its position while the handle rests", util.vector().topOf(3, 1, 1));

        applyKineticSpeedAt(helper, util, valveSel, -16);
        scene.world().rotateSection(handleLink, 0, 0, -45, 9);
        helper.effects().rotationSpeedIndicator(util.grid().at(3, 1, 1));
        setValveOpening(scene, valvePos, 45);
        scene.world().modifyBlock(valvePos, s -> s.setValue(FluidValveBlock.ENABLED, true), false);
        scene.idle(9);
        applyKineticSpeedAt(helper, util, valveSel, 0);
        narrate(scene, "A valve stopped halfway lets only half the flow through", util.vector().topOf(valvePos), PonderPalette.GREEN);
        narrate(scene, "A Valve Handle turns the valve by a set amount with each use", util.vector().topOf(3, 1, 1));

        // the direction dial: one-way makes it a check valve
        applyKineticSpeedAt(helper, util, valveSel, 16);
        scene.world().rotateSection(handleLink, 0, 0, -45, 9);
        setValveOpening(scene, valvePos, 90);
        scene.idle(9);
        applyKineticSpeedAt(helper, util, valveSel, 0);
        scene.idle(10);
        setValveDirection(scene, valvePos, ValveFlowMode.ONE_WAY_REVERSE);
        scene.overlay().showControls(util.vector().blockSurface(valvePos, Direction.SOUTH), Pointing.RIGHT, 50).scroll();
        scene.idle(10);
        narrate(scene, "A dial on its side can restrict the direction, so fluid passes one way only", util.vector().topOf(valvePos));
        fillTankAt(scene, new BlockPos(0, 1, 2), Fluids.WATER, 16000);
        scene.idle(10);
        narrate(scene, "The far tank now stands higher, but cannot push back through the valve", util.vector().topOf(0, 2, 2), PonderPalette.RED);
        setValveDirection(scene, valvePos, ValveFlowMode.BOTH_WAYS);
        scene.idle(10);
        narrate(scene, "Allowed both ways again, the higher side drains back", util.vector().topOf(valvePos).add(0, 1, 0), PonderPalette.GREEN);

        goggleTooltip(scene, util, valvePos, 110,
                goggleHeader("gui.goggles.valve_stats"),
                goggleLine("gui.goggles.valve_opening", "100%", ChatFormatting.GRAY));

        waitSeconds(scene, 2);
    }

}
