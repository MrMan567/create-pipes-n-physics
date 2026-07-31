package de.devin.pipesnphysics.engine.motion;

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
 * same-tick lookups agree and do not corrupt the delta. Only cells ON a sub-level are measured or
 * remembered at all — nothing else can move — and the cache is swept of cells that stopped being
 * solved, so a disassembled contraption leaves nothing behind.
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
    /** Drop a cell's measurement once nothing has solved it for this long (disassembled/gone). */
    private static final int STALE_TICKS = 1200;

    private static final Map<String, Cell> LAST_MEASUREMENTS = new HashMap<>();

    private CentrifugeField() {}

    /** One cell's last measurement: world position, the displacement that produced it, and both speeds. */
    private record Cell(double worldX, double worldY, double worldZ,
                        double displacementX, double displacementY, double displacementZ,
                        long tick, double orbitalSpeed, double angularSpeed) {}

    /** A cell that cannot move: no displacement, no speeds, and nothing worth remembering. */
    private static final Cell STILL = new Cell(0, 0, 0, 0, 0, 0, 0, 0, 0);

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
        // A cell off a sub-level rides no rigid body, so it cannot orbit anything — the same
        // by-construction inertness MomentumField gets from its per-sub-level frame key. Bailing
        // BEFORE the cache is what keeps this map to the cells that actually move: measuring every
        // main-level tank on the server stored a permanent zero apiece, for a solve-time projection
        // whose answer is always "it did not move".
        if (!SableCompat.isOnSubLevel(level, pos)) return STILL;

        String key = level.dimension().location() + ":" + pos.asLong();
        Cell last = LAST_MEASUREMENTS.get(key);
        if (last != null && last.tick() == gameTime) return last; // already measured this tick

        Vec3 world = SableCompat.getWorldPos(level, pos);
        double displacementX = 0, displacementY = 0, displacementZ = 0, orbital = 0, angular = 0;
        if (last != null && gameTime > last.tick()) {
            double dt = (gameTime - last.tick()) / 20.0;
            displacementX = world.x - last.worldX();
            displacementY = world.y - last.worldY();
            displacementZ = world.z - last.worldZ();
            double dist = Math.sqrt(displacementX * displacementX + displacementY * displacementY
                    + displacementZ * displacementZ);
            orbital = dist / dt;

            double prevDist = Math.sqrt(last.displacementX() * last.displacementX()
                    + last.displacementY() * last.displacementY()
                    + last.displacementZ() * last.displacementZ());
            if (dist > MIN_DISPLACEMENT && prevDist > MIN_DISPLACEMENT) {
                double dot = displacementX * last.displacementX() + displacementY * last.displacementY()
                        + displacementZ * last.displacementZ();
                double cos = Math.min(1.0, Math.max(-1.0, dot / (dist * prevDist)));
                angular = Math.acos(cos) / dt;
            }
        }
        Cell cell = new Cell(world.x, world.y, world.z, displacementX, displacementY, displacementZ,
                gameTime, orbital, angular);
        LAST_MEASUREMENTS.put(key, cell);
        return cell;
    }

    /**
     * Reclaim the measurements of cells nothing solves any more — a disassembled contraption fires
     * no block event, so its cells are simply never asked again and would hold their entry for the
     * session. Runs on the same slow server-tick cadence as the momentum-frame sweep. The threshold
     * sits far above the idle heartbeat, so a sleeping contraption keeps its delta; a dropped cell
     * just re-seeds (one tick reading zero motion) the next time it is measured.
     */
    public static void sweep(long now) {
        LAST_MEASUREMENTS.values().removeIf(cell -> now - cell.tick() > STALE_TICKS);
    }

    /** Drops every cached per-cell measurement; called on server stop so a new world never reads a stale delta. */
    public static void clear() {
        LAST_MEASUREMENTS.clear();
    }
}
