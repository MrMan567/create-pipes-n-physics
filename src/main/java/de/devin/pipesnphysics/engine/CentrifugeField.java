package de.devin.pipesnphysics.engine;

import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.compat.SableCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * Adds a centrifugal term to a cell's effective elevation on a spinning contraption, so fluid is flung
 * toward the faster-moving (outer) end and settles there instead of equalizing by height.
 *
 * The centrifugal potential per unit mass is ½·v² where v is the cell's orbital speed, so the head
 * offset (in blocks, matching the engine's elevation-as-head units) is ½·v²/g. Speed is measured
 * directly from how far the cell's world position moves per tick — no spin axis, radius, or centre of
 * mass needed — and it is self-gating: a stationary block never moves (offset 0), and a uniformly
 * translating (flying) contraption moves every cell equally, so the offset cancels and only rotational
 * motion drives flow. The solver subtracts this from baseY, so a lower effective elevation pulls fluid
 * in exactly like a downhill slope.
 *
 * Angular speed (radians/second) is read the same way, from the rate the cell's displacement vector
 * turns tick-over-tick: for rigid rotation the tangent rotates at the spin rate regardless of radius,
 * and pure translation leaves it unturned. It gates the whole effect on a minimum spin, so a barely
 * creeping contraption does nothing.
 *
 * Main-thread only (called from the solve); one measurement per cell, cached per tick so repeated
 * same-tick lookups agree and do not corrupt the delta.
 */
public final class CentrifugeField {
    /** Real gravity, converting the ½v² potential (m²/s²) into blocks of head (m). */
    private static final double GRAVITY = 9.8;
    /** Ignore orbital speeds below this (m/s) as measurement noise on a nearly-still contraption. */
    private static final double MIN_SPEED = 0.5;
    /** Clamp, so a pathological reading can never mint an absurd head. */
    private static final double MAX_OFFSET = 64.0;
    /** Below this displacement (blocks/tick) the direction is too small to read a reliable spin angle from. */
    private static final double MIN_DISPLACEMENT = 1e-4;

    private static final Map<String, Cell> LAST = new HashMap<>();

    private CentrifugeField() {}

    /** One cell's last measurement: world position, the displacement that produced it, and both speeds. */
    private record Cell(double worldX, double worldY, double worldZ,
                        double dispX, double dispY, double dispZ,
                        long tick, double orbitalSpeed, double angularSpeed) {}

    /** Blocks to SUBTRACT from a cell's elevation for the centrifugal pull, 0 when not spinning fast enough. */
    public static double headOffset(Level level, BlockPos pos, long gameTime) {
        if (!PipesNPhysicsConfig.ENABLE_CENTRIFUGE.get()) return 0;
        Cell cell = measure(level, pos, gameTime);
        if (cell.orbitalSpeed() <= MIN_SPEED) return 0;
        if (cell.angularSpeed() < PipesNPhysicsConfig.CENTRIFUGE_MIN_ANGULAR_SPEED.get()) return 0;
        double speed = cell.orbitalSpeed();
        return Math.min(MAX_OFFSET,
                0.5 * speed * speed / GRAVITY * PipesNPhysicsConfig.CENTRIFUGE_STRENGTH.get());
    }

    /** A cell's world-space orbital speed (m/s), measured from its position delta since last tick. */
    public static double orbitalSpeed(Level level, BlockPos pos, long gameTime) {
        return measure(level, pos, gameTime).orbitalSpeed();
    }

    /** A cell's spin rate (rad/s), from how far its displacement vector rotated since last tick. */
    public static double angularSpeed(Level level, BlockPos pos, long gameTime) {
        return measure(level, pos, gameTime).angularSpeed();
    }

    private static Cell measure(Level level, BlockPos pos, long gameTime) {
        String key = level.dimension().location() + ":" + pos.asLong();
        Cell last = LAST.get(key);
        if (last != null && last.tick() == gameTime) return last; // already measured this tick

        Vec3 world = SableCompat.getWorldPos(level, pos);
        double dispX = 0, dispY = 0, dispZ = 0, orbital = 0, angular = 0;
        if (last != null && gameTime > last.tick()) {
            double dt = (gameTime - last.tick()) / 20.0;
            dispX = world.x - last.worldX();
            dispY = world.y - last.worldY();
            dispZ = world.z - last.worldZ();
            double dist = Math.sqrt(dispX * dispX + dispY * dispY + dispZ * dispZ);
            orbital = dist / dt;

            double prevDist = Math.sqrt(last.dispX() * last.dispX() + last.dispY() * last.dispY()
                    + last.dispZ() * last.dispZ());
            if (dist > MIN_DISPLACEMENT && prevDist > MIN_DISPLACEMENT) {
                double dot = dispX * last.dispX() + dispY * last.dispY() + dispZ * last.dispZ();
                double cos = Math.min(1.0, Math.max(-1.0, dot / (dist * prevDist)));
                angular = Math.acos(cos) / dt;
            }
        }
        Cell cell = new Cell(world.x, world.y, world.z, dispX, dispY, dispZ, gameTime, orbital, angular);
        LAST.put(key, cell);
        return cell;
    }

    public static void clear() {
        LAST.clear();
    }
}
