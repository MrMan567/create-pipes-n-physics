package de.devin.pipesnphysics.engine.boundary;

import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import de.devin.pipesnphysics.compat.SableCompat;
import de.devin.pipesnphysics.mixin.FluidTankAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * Multiblock footprint and hydraulic column geometry for Create fluid tanks and Create: Connected
 * horizontal fluid vessels. Vessels reuse {@link FluidTankBlockEntity}'s {@code width}/{@code height}
 * fields but lay out along {@link FluidTankBlockEntity#getMainConnectionAxis()} instead of world-up.
 */
public final class FluidTankGeometry {
    private FluidTankGeometry() {}

    /**
     * Whether the multiblock lays along a horizontal axis — fill then rises across its
     * cross-section, not along its length.
     */
    public static boolean isHorizontal(FluidTankBlockEntity controller) {
        return controller.getMainConnectionAxis() != Direction.Axis.Y;
    }

    /** Every block cell in a multiblock tank or fluid vessel. */
    public static List<BlockPos> footprint(FluidTankBlockEntity controller) {
        int width = ((FluidTankAccessor) (Object) controller).pipesnphysics$getWidth();
        int length = ((FluidTankAccessor) (Object) controller).pipesnphysics$getHeight();
        BlockPos base = controller.getBlockPos();
        Direction.Axis axis = controller.getMainConnectionAxis();

        List<BlockPos> blocks = new ArrayList<>(width * width * length);
        if (axis == Direction.Axis.Y) {
            for (int dx = 0; dx < width; dx++) {
                for (int dy = 0; dy < length; dy++) {
                    for (int dz = 0; dz < width; dz++) {
                        blocks.add(base.offset(dx, dy, dz));
                    }
                }
            }
            return blocks;
        }

        for (int y = 0; y < width; y++) {
            for (int len = 0; len < length; len++) {
                for (int across = 0; across < width; across++) {
                    blocks.add(base.offset(
                            axis == Direction.Axis.X ? len : across,
                            y,
                            axis == Direction.Axis.Z ? len : across));
                }
            }
        }
        return blocks;
    }

    /** The multiblock footprint at {@code pos}, or just {@code pos} when no resolvable tank is there. */
    public static List<BlockPos> footprint(Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof FluidTankBlockEntity tank)) return List.of(pos);
        FluidTankBlockEntity controller = tank.getControllerBE();
        return controller != null ? footprint(controller) : List.of(pos);
    }

    /**
     * Vertical extent in blocks along which fill rises for head equalization. For a vertical tank this
     * is its height; for a horizontal fluid vessel it is the cross-section height ({@code width}).
     */
    public static int columnHeightBlocks(FluidTankBlockEntity controller) {
        int width = ((FluidTankAccessor) (Object) controller).pipesnphysics$getWidth();
        int length = ((FluidTankAccessor) (Object) controller).pipesnphysics$getHeight();
        return isHorizontal(controller) ? width : length;
    }

    /** World-Y of the bottom of the hydraulic column used by the solver. */
    public static double columnBaseY(Level level, BlockPos controllerPos, FluidTankBlockEntity controller) {
        int width = ((FluidTankAccessor) (Object) controller).pipesnphysics$getWidth();
        int length = ((FluidTankAccessor) (Object) controller).pipesnphysics$getHeight();
        int vertical = columnHeightBlocks(controller);
        Direction.Axis axis = controller.getMainConnectionAxis();

        if (axis == Direction.Axis.Y) {
            return SableCompat.getColumnBaseY(level, controllerPos, width, vertical);
        }

        double halfX = axis == Direction.Axis.X ? length / 2.0 : width / 2.0;
        double halfY = vertical / 2.0;
        double halfZ = axis == Direction.Axis.Z ? length / 2.0 : width / 2.0;
        return SableCompat.getColumnBaseYAtCenter(level, controllerPos, halfX, halfY, halfZ, vertical);
    }
}
