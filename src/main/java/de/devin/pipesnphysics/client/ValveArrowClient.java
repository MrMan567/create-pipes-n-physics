package de.devin.pipesnphysics.client;

import net.minecraft.core.BlockPos;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side registry of valves configured ONE-WAY, fed by {@code ValveDirectionBehaviour}'s
 * client sync — the renderer has no cheap way to find block entities near the camera, so the
 * behaviour announces itself. Positions are HINTS, not truth: {@link ValveArrowRenderer}
 * re-resolves the behaviour every frame and untracks entries that no longer exist or dialed
 * back to both ways, so breaking/unloading needs no explicit hook. Deliberately free of
 * client-only imports, so the common behaviour code may call it behind a plain
 * {@code isClientSide} check without dist gymnastics.
 */
public final class ValveArrowClient {
    private static final Set<BlockPos> ONE_WAY_VALVES = ConcurrentHashMap.newKeySet();

    private ValveArrowClient() {}

    public static void track(BlockPos pos) {
        ONE_WAY_VALVES.add(pos.immutable());
    }

    public static void untrack(BlockPos pos) {
        ONE_WAY_VALVES.remove(pos);
    }

    public static Iterable<BlockPos> positions() {
        return ONE_WAY_VALVES;
    }

    public static void clear() {
        ONE_WAY_VALVES.clear();
    }
}
