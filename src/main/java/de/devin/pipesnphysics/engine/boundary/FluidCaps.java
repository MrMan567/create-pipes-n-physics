package de.devin.pipesnphysics.engine.boundary;

import de.devin.pipesnphysics.PipesNPhysics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one place the engine asks a block for its fluid capability. Foreign providers are not
 * trusted: one that THROWS on a lookup must degrade to "no handler on that side", never crash the
 * server tick through the graph BFS — TFMG's blast stove NPEs on the side-agnostic (null-side)
 * query its lambda never expected, though NeoForge explicitly allows null there. A provider that
 * rejects the null side this way reads as having no null cap, which is exactly the SIDE-SPECIFIC
 * shape (§2): the block still joins the network per-face through the faces it does serve. Each
 * offending block type is logged once per session.
 */
public final class FluidCaps {
    private static final Set<ResourceLocation> WARNED = ConcurrentHashMap.newKeySet();

    private FluidCaps() {}

    /** The block's fluid handler on {@code side} (null = side-agnostic), or null — never throws. */
    public static IFluidHandler at(Level level, BlockPos pos, Direction side) {
        try {
            return level.getCapability(Capabilities.FluidHandler.BLOCK, pos, side);
        } catch (RuntimeException e) {
            warnOnce(level, pos, side, e);
            return null;
        }
    }

    private static void warnOnce(Level level, BlockPos pos, Direction side, RuntimeException e) {
        ResourceLocation type = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
        if (!WARNED.add(type)) return;
        PipesNPhysics.LOGGER.warn(
                "Fluid capability provider of {} threw on a {} query at {} — treating that side as "
                        + "having no handler (the block still connects through its working faces)",
                type, side == null ? "side-agnostic (null side)" : side + "-side", pos.toShortString(), e);
    }
}
