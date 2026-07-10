package de.devin.pipesnphysics.api;

import net.minecraft.world.level.block.Block;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Declare in code what role the engine should give a fluid-handler block — the programmatic
 * counterpart of the role block tags. Say a block is a tank, a relay, or one to ignore, and the engine
 * treats it that way without the caller needing a datapack. Call from your mod's setup, after your
 * blocks are registered. A matching block tag still takes precedence, so a pack can override; either
 * kind of explicit role overrides the engine's automatic relay detection.
 */
public final class FluidHandlerApi {
    private static final Map<Block, FluidHandlerRole> ROLES = new ConcurrentHashMap<>();

    private FluidHandlerApi() {}

    /** Declare a block's role in the pipe network; a later call for the same block overwrites it. */
    public static void setRole(Block block, FluidHandlerRole role) {
        ROLES.put(block, role);
    }

    /** The code-registered role for a block, or null if none — the engine merges this under the tags. */
    public static FluidHandlerRole role(Block block) {
        return ROLES.get(block);
    }
}
