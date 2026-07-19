package de.devin.pipesnphysics.client.ponder;

import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.element.InputElementBuilder;
import net.createmod.ponder.api.element.TextElementBuilder;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.createmod.ponder.foundation.instruction.RotateSceneInstruction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

/**
 * Shared authoring helpers for the mod's ponder scenes: base-plate setup, timing shortcuts, and
 * keyframed text callouts. Subclasses extend this so the helpers are callable unqualified. All take
 * the base {@link SceneBuilder}, so a raw {@code scene} or a {@code CreateSceneBuilder} both work.
 */
public abstract class PnpPonderScene {
    /** One second of scene time. */
    public static final int DEFAULT_DELAY = 20;
    /** How long a narrated callout stays on screen by default. */
    public static final int TEXT_DURATION = 80;

    /** Configure and reveal the base plate for a scene of the given footprint. */
    public static void setupScene(int size, SceneBuilder scene) {
        scene.configureBasePlate(0, 0, size);
        scene.addInstruction(s -> PonderEngineDriver.setFrozen(s.getWorld(), false)); // run unfrozen each replay
        scene.idle(5);
        scene.showBasePlate();
    }

    /** Idle for one second. */
    public static void waitDefaultDelay(SceneBuilder scene) {
        scene.idle(DEFAULT_DELAY);
    }

    /** Idle for the given number of seconds. */
    public static void waitSeconds(SceneBuilder scene, int seconds) {
        scene.idleSeconds(seconds);
    }

    /** Reveal a section of the structure, animating in from the given side. */
    public static void reveal(SceneBuilder scene, Selection section, Direction from) {
        scene.world().showSection(section, from);
    }

    /** Pan the camera to focus on a world position (smooth). */
    public static void focusOn(SceneBuilder scene, Vec3 point) {
        scene.special().movePointOfInterest(point);
    }

    /** Pan the camera to focus on a block (smooth). */
    public static void focusOn(SceneBuilder scene, BlockPos pos) {
        scene.special().movePointOfInterest(pos);
    }

    /** Orbit the camera horizontally (around Y) by the given angle; animates smoothly. */
    public static void rotateCamera(SceneBuilder scene, float degrees) {
        scene.rotateCameraY(degrees);
    }

    /** Tilt the camera up/down (around X) by the given angle; animates like {@link #rotateCamera}. */
    public static void tiltCamera(SceneBuilder scene, float degrees) {
        scene.addInstruction(new RotateSceneInstruction(degrees, 0f, true));
    }

    /** Rotate the camera by (pitch around X, yaw around Y) at once; both animate. Roll (Z) is unsupported. */
    public static void rotateCamera(SceneBuilder scene, float pitchDegrees, float yawDegrees) {
        scene.addInstruction(new RotateSceneInstruction(pitchDegrees, yawDegrees, true));
    }

    /** Set the scene zoom (larger = closer). A whole-scene constant — set near the start. */
    public static void zoom(SceneBuilder scene, float scale) {
        scene.scaleSceneView(scale);
    }

    /** Smoothly dolly the view to a target zoom over the given ticks (a LIVE zoom, unlike {@link #zoom}). */
    public static void zoomTo(SceneBuilder scene, float targetScale, int ticks) {
        scene.addInstruction(new ZoomInstruction(targetScale, ticks));
    }

    /** Shift the whole scene vertically in the view; configure near the start of a scene. */
    public static void shiftView(SceneBuilder scene, float offsetY) {
        scene.setSceneOffsetY(offsetY);
    }

    /** Add a navigation keyframe — a bookmark the viewer can skip to with the arrow keys. */
    public static void keyframe(SceneBuilder scene) {
        scene.addKeyframe();
    }

    /** Keyframed text callout pointing at a world position; chain e.g. {@code .colored(...)} on the result. */
    public static TextElementBuilder showText(SceneBuilder scene, int duration, String text, Vec3 target) {
        return scene.overlay().showText(duration)
                .text(text)
                .pointAt(target)
                .placeNearTarget()
                .attachKeyFrame();
    }

    /** Text callout above a block, for the default duration. */
    public static TextElementBuilder showTextAbove(SceneBuilder scene, SceneBuildingUtil util, String text, BlockPos pos) {
        return showText(scene, TEXT_DURATION, text, util.vector().topOf(pos));
    }

    /** Text callout at a block's face, for the default duration. */
    public static TextElementBuilder showTextAt(SceneBuilder scene, SceneBuildingUtil util, String text, BlockPos pos, Direction face) {
        return showText(scene, TEXT_DURATION, text, util.vector().blockSurface(pos, face));
    }

    /** Highlight a section and label it, pointing at a world position. */
    public static TextElementBuilder highlight(SceneBuilder scene, String text, Selection area, Vec3 target) {
        return scene.overlay().showOutlineWithText(area, TEXT_DURATION)
                .text(text)
                .pointAt(target)
                .placeNearTarget()
                .colored(PonderPalette.MEDIUM)
                .attachKeyFrame();
    }

    /** Draw a colored box outline around a region (no text) for the given duration. */
    public static void highlightBox(SceneBuilder scene, Selection area, PonderPalette color, int duration) {
        scene.overlay().showOutline(color, new Object(), area, duration);
    }

    /** Draw a green box outline around a region for the default duration. */
    public static void highlightBox(SceneBuilder scene, Selection area) {
        highlightBox(scene, area, PonderPalette.GREEN, TEXT_DURATION);
    }

    /** Draw a green box outline around a single block for the default duration. */
    public static void highlightBox(SceneBuilder scene, SceneBuildingUtil util, BlockPos pos) {
        highlightBox(scene, util.select().position(pos));
    }

    /** Show a callout above a block, then idle long enough for it to be read. */
    public static void narrateAbove(SceneBuilder scene, SceneBuildingUtil util, String text, BlockPos pos) {
        showTextAbove(scene, util, text, pos);
        scene.idle(TEXT_DURATION);
    }

    /** Spin the kinetics at a position (e.g. drive a pump); needs the Create scene builder. */
    public static void applyKineticSpeedAt(CreateSceneBuilder scene, SceneBuildingUtil util, Selection pos, float speed) {
        scene.world().setKineticSpeed(pos, speed);
    }

    /** Fill the fluid tank at a position with an amount of a fluid, clamped to its capacity. */
    public static void fillTankAt(SceneBuilder scene, BlockPos pos, Fluid fluid, int amount) {
        scene.world().modifyBlockEntity(pos, FluidTankBlockEntity.class,
                tank -> tank.getTankInventory().fill(new FluidStack(fluid, amount), FluidAction.EXECUTE));
    }

    /** Freeze the fluid engine from this point in the scene — fluid holds in place until unfrozen. */
    public static void freezeEngine(SceneBuilder scene) {
        scene.addInstruction(s -> PonderEngineDriver.setFrozen(s.getWorld(), true));
    }

    /** Resume the fluid engine after a {@link #freezeEngine} hold. */
    public static void unfreezeEngine(SceneBuilder scene) {
        scene.addInstruction(s -> PonderEngineDriver.setFrozen(s.getWorld(), false));
    }

    /** Right-click prompt holding an item above a block; chain e.g. {@code .whileSneaking()} on the result. */
    public static InputElementBuilder showClickWithItemAt(SceneBuilder scene, SceneBuildingUtil util, BlockPos pos,
                                                          ItemStack item, Pointing pointing, int duration) {
        return scene.overlay().showControls(util.vector().topOf(pos), pointing, duration)
                .rightClick()
                .withItem(item);
    }

    /** Right-click prompt holding an item above a block, pointing down for the default duration. */
    public static InputElementBuilder showClickWithItemAt(SceneBuilder scene, SceneBuildingUtil util, BlockPos pos, ItemStack item) {
        return showClickWithItemAt(scene, util, pos, item, Pointing.DOWN, DEFAULT_DELAY);
    }
}
