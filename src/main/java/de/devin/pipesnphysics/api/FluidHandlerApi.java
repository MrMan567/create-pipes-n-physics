package de.devin.pipesnphysics.api;

import net.minecraft.world.level.block.Block;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Declare, in code, what {@link FluidHandlerRole role} the engine should give a fluid-handler block —
 * the programmatic counterpart of the role block tags. Tell it "this block is a tank", "this is a
 * relay", "ignore this one", and so on, so the engine treats your block correctly without the caller
 * needing a datapack. Call from your mod's setup, after your blocks are registered.
 *
 * <p>A matching block <em>tag</em> takes precedence over a code role (a pack can override), and an
 * explicit role of either kind overrides the engine's automatic relay detection.
 *
 * <pre>{@code
 * FluidHandlerApi.setRole(MyMod.FLUID_DRUM.get(), FluidHandlerRole.RESERVOIR);
 * FluidHandlerApi.setRole(MyMod.DOCK_PORT.get(), FluidHandlerRole.RELAY);
 * }</pre>
 */
public final class FluidHandlerApi {
    private static final Map<Block, FluidHandlerRole> ROLES = new ConcurrentHashMap<>();

    private FluidHandlerApi() {}

    /** Declare {@code block}'s role in the pipe network; a later call for the same block overwrites it. */
    public static void setRole(Block block, FluidHandlerRole role) {
        ROLES.put(block, role);
    }

    /** The code-registered role for a block, or null if none — the engine merges this under the tags. */
    public static FluidHandlerRole role(Block block) {
        return ROLES.get(block);
    }
}
