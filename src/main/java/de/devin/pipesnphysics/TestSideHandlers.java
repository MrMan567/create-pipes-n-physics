package de.devin.pipesnphysics;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * DEV/TEST-ONLY side-specific fluid handler, so the per-face endpoint path (one block serving a
 * DIFFERENT fluid on each side, CLAUDE.md §2 / {@link de.devin.pipesnphysics.engine.HandlerRoles})
 * can be GameTested — no block in the pack is genuinely side-specific (the docking connector is
 * side-agnostic), so the feature is otherwise untestable.
 *
 * It registers a fluid capability on {@link Blocks#SPONGE} — inert in normal play — that exposes a
 * separate {@link FluidTank} on each HORIZONTAL face and NOTHING on the {@code null} side, which is
 * exactly the shape {@code GraphBuilder} treats as side-specific. Backing tanks are per BlockPos+face
 * so a test can pre-fill one side and read it back through the engine. Registration is gated to
 * {@code !production} by the caller, so it never ships.
 */
public final class TestSideHandlers {
    public static final int TANK_CAPACITY = 16000;
    private static final Map<BlockPos, EnumMap<Direction, FluidTank>> TANKS = new HashMap<>();

    private TestSideHandlers() {}

    public static void register(RegisterCapabilitiesEvent event) {
        event.registerBlock(Capabilities.FluidHandler.BLOCK, (level, pos, state, be, side) -> {
            if (side == null || side.getAxis().isVertical()) return null; // side-specific: N/E/S/W only
            return tankAt(pos, side);
        }, Blocks.SPONGE);
    }

    /** The backing tank for one face — a test fills this, then reads it through the engine. */
    public static FluidTank tankAt(BlockPos pos, Direction side) {
        return TANKS.computeIfAbsent(pos.immutable(), p -> new EnumMap<>(Direction.class))
                .computeIfAbsent(side, s -> new FluidTank(TANK_CAPACITY));
    }

    public static void clear() {
        TANKS.clear();
    }
}
