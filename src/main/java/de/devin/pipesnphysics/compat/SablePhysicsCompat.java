package de.devin.pipesnphysics.compat;

import de.devin.pipesnphysics.PipesNPhysics;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import dev.ryanhcode.sable.api.physics.force.ForceGroup;
import dev.ryanhcode.sable.api.physics.force.ForceGroups;
import dev.ryanhcode.sable.api.physics.mass.MassTracker;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.Map;

public class SablePhysicsCompat {

    private static final Map<String, Double> lastAppliedMass = new HashMap<>();

    public static void applyFluidWeight(ServerSubLevel subLevel, BlockPos controllerPos,
                                        double fillFraction, double massKg, double timeStep) {
        if (massKg == 0) return;

        // Buoyant lift (a lighter-than-air gas) is the ONE case applied as a force: it pushes UP, and a
        // negative mass in the tracker could drive the contraption's total mass through zero → an
        // inverse-mass singularity → NaN/launch. Drop any COG mass this controller applied while it
        // held a liquid so a liquid→gas swap doesn't leak it (cheap once the key is gone —
        // withdraw early-returns).
        if (massKg < 0) {
            withdraw(subLevel, controllerPos);
            applyBuoyantLift(subLevel, controllerPos, massKg, timeStep);
            return;
        }

        // Positive weight is a real MASS on the tracker, so a full tank actually shifts the
        // contraption's center of gravity — a downward force would sink it without tipping, which felt
        // wrong. EXPERIMENTAL_TANK_COG then only chooses WHERE the mass sits (fill-shifted vs centred).
        applyViaMassTracker(subLevel, controllerPos, fillFraction, massKg);
    }

    /**
     * Buoyant lift for a gas tank: an upward gravitational impulse (gravity·mass·dt — magnitude scales
     * with gravity and the substep, NOT the raw mass, which would over-scale by 1/(g·dt) and rocket the
     * ship off) applied AT the tank's position, so an off-centre gas cell tips that side up rather than
     * lifting the contraption evenly — the force counterpart of a liquid's COG-shifting mass. Routed
     * through Sable's LEVITATION force group exactly like Aeronautics' balloons; never a negative
     * tracker mass, which could zero the total mass → an inverse-mass singularity.
     */
    private static void applyBuoyantLift(ServerSubLevel subLevel, BlockPos tankPos, double massKg,
                                         double timeStep) {
        // gravity·mass·dt is the gravitational impulse as a vector; massKg < 0 (a gas) flips it to UP.
        Vector3d worldForce = DimensionPhysicsData.getGravity(subLevel.getLevel())
                .mul(massKg * timeStep, new Vector3d());

        // Sable's force accumulator works in the contraption's own (unrotated) frame, so rotate the
        // world impulse into it — the lift stays vertical as the ship pitches (identity when level).
        Pose3dc pose = subLevel.logicalPose();
        Vector3d localForce = pose != null
                ? pose.transformNormalInverse(worldForce, new Vector3d())
                : worldForce;

        ForceGroup group = levitationGroup();
        if (group == null) return;

        // Apply at the tank's block centre; applyImpulseAtPoint adds torque = (point − centre of mass) × F.
        Vector3d point = new Vector3d(tankPos.getX() + 0.5, tankPos.getY() + 0.5, tankPos.getZ() + 0.5);
        subLevel.getOrCreateQueuedForceGroup(group)
                .getForceTotal()
                .applyImpulseAtPoint(subLevel, point, localForce);
    }

    /**
     * The LEVITATION force group, resolved from the registry by id (cached). Avoids touching Sable's
     * Veil-based {@code RegistryObject}, which isn't on our compile classpath; the registry is populated
     * long before any physics tick runs.
     */
    private static ForceGroup levitationGroup;

    private static ForceGroup levitationGroup() {
        if (levitationGroup == null) {
            levitationGroup = ForceGroups.REGISTRY.get(ResourceLocation.fromNamespaceAndPath("sable", "levitation"));
        }
        return levitationGroup;
    }

    private static final Map<String, Vec3> lastAppliedOffset = new HashMap<>();

    private static void applyViaMassTracker(ServerSubLevel subLevel, BlockPos controllerPos, double fillFraction, double massKg) {
        MassTracker tracker = subLevel.getSelfMassTracker();
        if (tracker == null) return;

        String key = subLevel.getUniqueId() + ":" + controllerPos.toShortString();
        // Where the fluid mass sits within the block: fill-shifted (the fluid settles to the low side)
        // when the experimental COG is on, else the plain block centre — either way it is real mass.
        Vec3 offset = PipesNPhysicsConfig.EXPERIMENTAL_TANK_COG.get()
                ? tiltAwareOffset(subLevel, fillFraction)
                : new Vec3(0.5, 0.5, 0.5);

        Double prevMass = lastAppliedMass.get(key);
        if (prevMass != null && Math.abs(prevMass - massKg) < 0.001) return;

        try {
            var level = subLevel.getLevel();
            BlockState state = level.getBlockState(controllerPos);

            if (prevMass != null && prevMass > 0) {
                Vec3 prevOffset = lastAppliedOffset.getOrDefault(key, offset);
                tracker.addBlockMass(level, state, controllerPos, -prevMass, prevOffset);
            }

            tracker.addBlockMass(level, state, controllerPos, massKg, offset);
            lastAppliedMass.put(key, massKg);
            lastAppliedOffset.put(key, offset);
        } catch (Exception e) {
            // The tracker's true state is now unknown (the -prevMass withdraw may have run before the
            // +massKg add threw), so drop our bookkeeping: the next tick then re-applies from a clean
            // slate instead of trusting a stale prevMass and double-subtracting a mass never added.
            lastAppliedMass.remove(key);
            lastAppliedOffset.remove(key);
            PipesNPhysics.LOGGER.warn("Failed to apply fluid tank mass at {}", controllerPos, e);
        }
    }

    /** Remove any mass previously applied for a controller whose tank has drained to empty. */
    public static void withdraw(ServerSubLevel subLevel, BlockPos controllerPos) {
        MassTracker tracker = subLevel.getSelfMassTracker();
        if (tracker == null) return;
        String key = subLevel.getUniqueId() + ":" + controllerPos.toShortString();
        Double prevMass = lastAppliedMass.remove(key);
        Vec3 prevOffset = lastAppliedOffset.remove(key);
        if (prevMass == null || prevMass <= 0) return;
        try {
            var level = subLevel.getLevel();
            BlockState state = level.getBlockState(controllerPos);
            tracker.addBlockMass(level, state, controllerPos, -prevMass,
                    prevOffset != null ? prevOffset : tiltAwareOffset(subLevel, 0));
        } catch (Exception e) {
            PipesNPhysics.LOGGER.warn("Failed to withdraw fluid tank mass at {}", controllerPos, e);
        }
    }

    /** Drop the applied-mass bookkeeping — the tracker is rebuilt from blocks on world load. */
    public static void clear() {
        lastAppliedMass.clear();
        lastAppliedOffset.clear();
    }

    private static Vec3 tiltAwareOffset(ServerSubLevel subLevel, double fillFraction) {
        double cx = 0.5;
        double cy = fillFraction / 2.0;
        double cz = 0.5;

        Pose3dc pose = subLevel.logicalPose();
        if (pose == null) return new Vec3(cx, cy, cz);

        Vector3d localGrav = pose.transformNormalInverse(new Vector3d(0, -1, 0), new Vector3d());

        double emptyRoom = 1.0 - fillFraction;
        cx += localGrav.x * 0.5 * emptyRoom * 0.5;
        cy += localGrav.y * 0.5 * emptyRoom * 0.5;
        cz += localGrav.z * 0.5 * emptyRoom * 0.5;

        cx = Math.clamp(cx, 0.05, 0.95);
        cy = Math.clamp(cy, 0.05, 0.95);
        cz = Math.clamp(cz, 0.05, 0.95);

        return new Vec3(cx, cy, cz);
    }
}
