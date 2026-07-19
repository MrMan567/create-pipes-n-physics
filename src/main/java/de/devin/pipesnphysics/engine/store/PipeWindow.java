package de.devin.pipesnphysics.engine.store;

import com.simibubi.create.content.fluids.pipes.AxisPipeBlock;
import de.devin.pipesnphysics.compat.SableCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The vertical WINDOW a pipe cell holds — and renders — its fluid in: the geometry the settle
 * ({@code FlowNetwork}) uses for its rest targets, matched by the client renderer
 * ({@code PipeFluidRenderer}). A HORIZONTAL (X/Z) straight cell fills only the 6/16 BORE (floor at
 * {@code cellBottom + 3/16}); a VERTICAL (Y-axis) riser fills the FULL block. Every
 * stored-volume↔height conversion must use this ONE window, or a settled pipe's surface sits off
 * the tank it equalized with.
 */
public final class PipeWindow {
    /** Bore floor as a block-local offset — matches the renderer's PIPE_RADIUS (3/16). */
    public static final double BORE_BOTTOM = 0.5 - 3.0 / 16;
    /** Bore height — the 6/16 window a horizontal pipe draws its fluid in. */
    public static final double BORE_HEIGHT = 2 * (3.0 / 16);
    /**
     * Opening LIP as a block-local offset — the pipe's INNER bore floor (owner-specified: the
     * outer shell bottom at 4/16 plus the 1-pixel wall; the opening itself is 2 pixels shorter
     * than the shell, one wall each side, but only its bottom gates flow). Compared against the
     * RENDERED surface — the fluid the player sees — never the liquid head (§5a lip rule).
     */
    public static final double LIP_BOTTOM = 5.0 / 16;

    private PipeWindow() {}

    /** World-Y of the draw LIP of an opening through this cell: the inner bore floor for a bore cell, the block bottom for a riser. */
    public static double lipY(Level level, BlockPos pos) {
        double cellBottom = SableCompat.getWorldY(level, pos) - 0.5;
        return fillsFullBlock(level, pos) ? cellBottom : cellBottom + LIP_BOTTOM;
    }

    /** Whether this cell is a VERTICAL straight pipe — its fluid fills the full block height. */
    public static boolean fillsFullBlock(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.hasProperty(AxisPipeBlock.AXIS)
                && state.getValue(AxisPipeBlock.AXIS) == Direction.Axis.Y;
    }

    /** World-Y of the bottom of a cell's fluid window — bore floor horizontal, block bottom vertical. */
    public static double bottomY(Level level, BlockPos pos) {
        double cellBottom = SableCompat.getWorldY(level, pos) - 0.5;
        return fillsFullBlock(level, pos) ? cellBottom : cellBottom + BORE_BOTTOM;
    }

    /** Height of a cell's fluid window: the full block for a riser, else the 6/16 bore. */
    public static double height(Level level, BlockPos pos) {
        return fillsFullBlock(level, pos) ? 1.0 : BORE_HEIGHT;
    }

    /** Fraction of a cell's window sitting below the surface line, clamped 0..1. */
    public static double fill(Level level, BlockPos pos, double line) {
        return Math.clamp((line - bottomY(level, pos)) / height(level, pos), 0, 1);
    }
}
