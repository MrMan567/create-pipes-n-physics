package de.devin.pipesnphysics.handler;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Carries a pipe cell's stored fluid across a same-position block swap. The shift-swap
 * ({@link PipeSwapHandler}), Create's wrench window toggle, and encasing all REPLACE the block
 * entity — and the stored content rides the block entity — so the replacement pipe used to come
 * up empty and the fluid was voided ("switching a pipe to the glassed view loses its content").
 *
 * The dying cell STASHES its content keyed by position ({@code PipeContentCarryMixin} on
 * {@code destroy()}); the replacement cell CLAIMS it as it initializes, at most a tick later. An
 * unclaimed stash simply expires — the pipe was genuinely broken and the break-spill owns that
 * fluid; {@code NetworkEditHandler.spillBrokenPipe} CONSUMES the stash when it spills, so a
 * quickly re-placed pipe can never adopt fluid that already left. Server-side, memory-only.
 */
public final class PipeContentCarry {
    /** Ticks a stash stays claimable: destroy and the replacement's initialize sit 0–1 apart. */
    private static final int CLAIM_WINDOW_TICKS = 2;

    private record Stash(FluidStack fluid, long stashedAt) {}

    private static final Map<ResourceKey<Level>, Map<BlockPos, Stash>> STASHES = new HashMap<>();

    private PipeContentCarry() {}

    /** Remember a dying pipe cell's content so the block replacing it can adopt it. */
    public static void stash(Level level, BlockPos pos, FluidStack fluid) {
        long now = level.getGameTime();
        Map<BlockPos, Stash> stashes = STASHES.computeIfAbsent(level.dimension(), k -> new HashMap<>());
        prune(stashes, now);
        stashes.put(pos.immutable(), new Stash(fluid, now));
    }

    /** The fluid stashed at pos within the claim window, removed from the stash; EMPTY otherwise. */
    public static FluidStack claim(Level level, BlockPos pos) {
        Map<BlockPos, Stash> stashes = STASHES.get(level.dimension());
        if (stashes == null) return FluidStack.EMPTY;
        Stash stash = stashes.remove(pos);
        return stash == null || level.getGameTime() - stash.stashedAt() >= CLAIM_WINDOW_TICKS
                ? FluidStack.EMPTY : stash.fluid();
    }

    /** Drop everything — server stop. */
    public static void clear() {
        STASHES.clear();
    }

    private static void prune(Map<BlockPos, Stash> stashes, long now) {
        Iterator<Stash> it = stashes.values().iterator();
        while (it.hasNext()) {
            if (now - it.next().stashedAt() >= CLAIM_WINDOW_TICKS) it.remove();
        }
    }
}
