package de.devin.pipesnphysics.engine;

import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.compat.SableCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * Adds a centrifugal term to a cell's effective elevation on a spinning contraption, so fluid is
 * flung toward the faster-moving (outer) end and settles there instead of equalizing by height.
 *
 * <p>The centrifugal potential per unit mass is {@code ½·v²} where {@code v} is the cell's orbital
 * speed, so the head offset (in blocks, matching the engine's elevation-as-head units) is
 * {@code ½·v²/g}. Speed is measured directly from how far the cell's WORLD position moves per tick —
 * so no spin axis, radius, or center of mass is needed, and it is self-gating: a stationary block
 * never moves (offset 0), and a uniformly translating (flying) contraption moves every cell equally,
 * so the offset cancels and only differential (rotational) motion drives flow. The solver subtracts
 * this from {@code baseY}, so a lower effective elevation pulls fluid in exactly like a downhill slope.
 *
 * <p>Main-thread only (called from the solve); a single last-position sample per cell, cached per tick
 * so repeated same-tick lookups agree and do not corrupt the delta.
 */
public final class CentrifugeField {
    /** Real gravity, converting the ½v² potential (m²/s²) into blocks of head (m). */
    private static final double GRAVITY = 9.8;
    /** Ignore orbital speeds below this (m/s) as measurement noise on a nearly-still contraption. */
    private static final double MIN_SPEED = 0.5;
    /** Clamp, so a pathological reading can never mint an absurd head. */
    private static final double MAX_OFFSET = 64.0;

    /** Per cell: {worldX, worldY, worldZ, gameTick, cachedOffset}. */
    private static final Map<String, double[]> LAST = new HashMap<>();

    private CentrifugeField() {}

    /** Blocks to SUBTRACT from a cell's elevation for the centrifugal pull, 0 when not spinning. */
    public static double headOffset(Level level, BlockPos pos, long gameTime) {
        if (!PipesNPhysicsConfig.ENABLE_CENTRIFUGE.get()) return 0;
        double speed = orbitalSpeed(level, pos, gameTime);
        if (speed <= MIN_SPEED) return 0;
        return Math.min(MAX_OFFSET,
                0.5 * speed * speed / GRAVITY * PipesNPhysicsConfig.CENTRIFUGE_STRENGTH.get());
    }

    /** A cell's world-space orbital speed (m/s), measured from its position delta since last tick. */
    public static double orbitalSpeed(Level level, BlockPos pos, long gameTime) {
        String key = level.dimension().location() + ":" + pos.asLong();
        double[] last = LAST.get(key);
        if (last != null && (long) last[3] == gameTime) return last[4]; // already measured this tick

        Vec3 world = SableCompat.getWorldPos(level, pos);
        double speed = 0;
        if (last != null && gameTime > last[3]) {
            double dt = (gameTime - last[3]) / 20.0;
            double dx = world.x - last[0], dy = world.y - last[1], dz = world.z - last[2];
            speed = Math.sqrt(dx * dx + dy * dy + dz * dz) / dt;
        }
        LAST.put(key, new double[]{world.x, world.y, world.z, gameTime, speed});
        return speed;
    }

    public static void clear() {
        LAST.clear();
    }
}
