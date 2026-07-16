package de.devin.pipesnphysics.engine.motion;

import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.compat.SableCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Adds a momentum term to a cell's effective elevation on an ACCELERATING contraption: when the frame's
 * linear velocity changes, fluid feels a pseudo-gravity opposite the acceleration and piles toward the
 * back — water sloshing in a braking cart. This is the linear (d'Alembert) counterpart of the rotational
 * CentrifugeField, and the two add cleanly because this one is sourced from the rigid body's
 * TRANSLATIONAL velocity, so a spin never leaks into it and gets double-counted.
 *
 * The frame acceleration A is the Sable rigid body's linear velocity differentiated over the physics
 * step and lightly smoothed (differentiating amplifies jitter). The potential is A·r, so the head offset
 * in blocks is (A·r)/g, ADDED to a cell's baseY. r is measured from the contraption's reporting tank,
 * not world origin, so the offset stays bounded — A is uniform across a rigid body, only differences
 * along an edge matter, and that reference cancels out of them.
 *
 * A frame is keyed per sub-level (the write side records under {@code ServerSubLevel.getUniqueId()}), and
 * the read side looks a cell up by its CONTAINING sub-level's id — the SAME identity, resolved through
 * Sable's plot-grid containment. So a frame acts only on its own contraption: a cell on a DIFFERENT ship
 * or on the main level resolves a different id (or none) and is inert by construction — the linear
 * acceleration being uniform across a body, there is no per-cell signal to fall back on the way the
 * centrifuge measures orbital speed, so getting the identity right is the whole correctness of the term.
 * Stale frames expire so a disassembled contraption stops acting; constant velocity gives A=0, so cruising
 * or sitting still does nothing.
 */
public final class MomentumField {
    /** Real gravity, converting the A·r potential (m²/s²) into blocks of head (m). */
    private static final double GRAVITY = 9.8;
    /** Clamp, so a pathological reading can never mint an absurd head. */
    private static final double MAX_OFFSET = 64.0;
    /** Ignore accelerations below this (m/s²) as derivative noise on a barely-moving body. */
    private static final double NOISE_FLOOR = 0.1;
    /** Exponential smoothing on the acceleration, since differentiating a velocity amplifies jitter. */
    private static final double SMOOTHING = 0.3;
    /** Drop a contraption's frame if no physics tick has refreshed it for this long (disassembled/gone). */
    private static final int STALE_TICKS = 40;

    private record Frame(double accelX, double accelY, double accelZ,
                         double originX, double originY, double originZ, long tick) {}
    private record Velocity(double velocityX, double velocityY, double velocityZ) {}

    private static final Map<String, Frame> FRAMES = new ConcurrentHashMap<>();
    private static final Map<String, Velocity> LAST_VELOCITY = new ConcurrentHashMap<>();

    private MomentumField() {}

    /**
     * Record a contraption's rigid-body linear velocity and reference position for this physics step;
     * called (possibly off-thread) from the tank physics tick. The frame acceleration is the smoothed
     * derivative of the velocity over {@code timeStep} — the physics step length in SECONDS — keyed by
     * the sub-level's unique id. The origin is the reporting tank's world position, the reference the
     * A·r potential is measured from: acceleration is uniform across the rigid body, so any fixed
     * reference yields the same head differences along an edge — it only keeps the offsets bounded.
     */
    public static void record(String subLevelId, double velocityX, double velocityY, double velocityZ,
                              double originX, double originY, double originZ, double timeStep, long tick) {
        Velocity prev = LAST_VELOCITY.put(subLevelId, new Velocity(velocityX, velocityY, velocityZ));
        if (prev == null || timeStep <= 1e-6) return;
        double accelX = (velocityX - prev.velocityX()) / timeStep;
        double accelY = (velocityY - prev.velocityY()) / timeStep;
        double accelZ = (velocityZ - prev.velocityZ()) / timeStep;
        Frame last = FRAMES.get(subLevelId);
        if (last != null) {
            accelX = last.accelX() + (accelX - last.accelX()) * SMOOTHING;
            accelY = last.accelY() + (accelY - last.accelY()) * SMOOTHING;
            accelZ = last.accelZ() + (accelZ - last.accelZ()) * SMOOTHING;
        }
        FRAMES.put(subLevelId, new Frame(accelX, accelY, accelZ, originX, originY, originZ, tick));
    }

    /**
     * Blocks to ADD to a cell's elevation for the momentum tilt, 0 when its own frame is not accelerating.
     * Positive raises the cell's effective elevation, so fluid drains away from it toward the low side.
     */
    public static double headOffset(Level level, BlockPos pos) {
        if (!PipesNPhysicsConfig.ENABLE_MOMENTUM_HEAD.get() || FRAMES.isEmpty()) return 0;
        String id = SableCompat.getSubLevelId(level, pos);
        if (id == null) return 0; // a main-level cell rides no rigid body — inert by construction

        Frame frame = FRAMES.get(id);
        if (frame == null) return 0;
        if (level.getGameTime() - frame.tick() > STALE_TICKS) {
            // Conditional remove: only evict the exact stale frame we read, so a concurrent physics-tick
            // record() that just revived this sub-level isn't clobbered by our staleness sweep.
            if (FRAMES.remove(id, frame)) LAST_VELOCITY.remove(id);
            return 0;
        }

        double accel = Math.sqrt(sq(frame.accelX()) + sq(frame.accelY()) + sq(frame.accelZ()));
        if (accel < NOISE_FLOOR || accel < PipesNPhysicsConfig.MOMENTUM_MIN_ACCEL.get()) return 0;

        Vec3 world = SableCompat.getWorldPos(level, pos); // projected only once a live, above-noise frame is found
        double potential = frame.accelX() * (world.x - frame.originX())
                + frame.accelY() * (world.y - frame.originY())
                + frame.accelZ() * (world.z - frame.originZ());
        return Math.clamp(
            potential / GRAVITY * PipesNPhysicsConfig.MOMENTUM_STRENGTH.get(), -MAX_OFFSET, MAX_OFFSET);
    }

    /**
     * Reclaim frames of contraptions that stopped reporting (disassembled) and whose cells are never
     * queried again — the read side already evicts a stale frame it happens to look up, this backstops
     * the ones nothing looks up. Runs on the same slow server-tick cadence as the graph-cache sweep.
     */
    public static void sweep(long now) {
        FRAMES.entrySet().removeIf(entry -> now - entry.getValue().tick() > STALE_TICKS);
        LAST_VELOCITY.keySet().removeIf(id -> !FRAMES.containsKey(id));
    }

    private static double sq(double value) {
        return value * value;
    }

    public static void clear() {
        FRAMES.clear();
        LAST_VELOCITY.clear();
    }
}
