package de.devin.pipesnphysics.engine.probe;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Debug spike: surfaces a Sable sub-level's server-read spin so it can be watched in world. The
 * physics tick ({@code FluidTankMassMixin}) drops a {@link Sample} here — the rigid body's own
 * angular velocity, its orientation, and the reporting tank's world position — and the main server
 * tick derives spin and action-bars the sub-level NEAREST each player.
 *
 * Two spin numbers are shown because a bearing-driven contraption spins KINEMATICALLY: the bearing
 * sets the orientation each tick rather than giving the body an angular velocity, so
 * {@code getAngularVelocity} reads ~0 while the thing visibly turns. The truth lives in the pose delta,
 * so we integrate the orientation quaternion tick-over-tick; {@link #angularSpeed} returns that value —
 * the one the eventual centrifuge feature triggers on. Reporting the NEAREST sub-level (plus a short id
 * and an active count) keeps a multi-contraption or static-base setup from showing the wrong body, and
 * stale samples expire so a disassembled contraption stops ghosting. Type-agnostic (no Sable imports,
 * string keys) so the always-loaded engine can call {@link #tick} whether or not Sable is present.
 */
public final class SublevelSpinProbe {
    /** Drop a sub-level's sample if no physics tick has refreshed it for this long (disassembled/gone). */
    private static final int STALE_TICKS = 40;

    private static final Map<String, PerLevelState> STATES = new ConcurrentHashMap<>();

    private SublevelSpinProbe() {}

    /** One physics tick's raw state: body spin, orientation quat, reporting tank world pos, tick stamp. */
    public record Sample(double bodyAngularSpeed, double linearSpeed,
                         double quatX, double quatY, double quatZ, double quatW,
                         double worldX, double worldY, double worldZ,
                         long recordedTick, ResourceKey<Level> levelKey) {}

    /**
     * Everything tracked for one sub-level: the physics-thread {@link Sample} plus the main-thread
     * pose-derivation memory (previous orientation, its tick, the derived spin). Fields are volatile
     * because the sample is written off-thread and the derived spin is read via {@link #angularSpeed}
     * from anywhere — the same visibility the former per-field concurrent maps gave.
     */
    private static final class PerLevelState {
        volatile Sample sample;
        volatile double[] lastOrientationQuat;
        volatile Long lastDerivedTick;
        volatile double poseSpeed;
    }

    /** Store the latest sample for a sub-level; called from the (possibly off-thread) physics tick. */
    public static void record(String subLevelId, double angX, double angY, double angZ,
                              double linX, double linY, double linZ,
                              double quatX, double quatY, double quatZ, double quatW,
                              double worldX, double worldY, double worldZ,
                              long recordedTick, ResourceKey<Level> levelKey) {
        double bodyAngularSpeed = Math.sqrt(angX * angX + angY * angY + angZ * angZ);
        double linearSpeed = Math.sqrt(linX * linX + linY * linY + linZ * linZ);
        Sample sample = new Sample(bodyAngularSpeed, linearSpeed, quatX, quatY, quatZ, quatW,
                worldX, worldY, worldZ, recordedTick, levelKey);
        // compute (not computeIfAbsent-then-assign) so the create+store is atomic against a concurrent
        // staleness removal in tick(), exactly like the plain put the sample map used to get.
        STATES.compute(subLevelId, (id, state) -> {
            PerLevelState next = state != null ? state : new PerLevelState();
            next.sample = sample;
            return next;
        });
    }

    /** Latest pose-derived angular speed (rad/s) for a sub-level, or 0 if none seen — the real spin. */
    public static double angularSpeed(String subLevelId) {
        PerLevelState state = STATES.get(subLevelId);
        return state == null ? 0.0 : state.poseSpeed;
    }

    /** Main-thread: derive pose-delta spin, drop stale samples, action-bar the nearest sub-level. */
    public static void tick(MinecraftServer server) {
        if (STATES.isEmpty()) return;
        for (Map.Entry<String, PerLevelState> entry : STATES.entrySet()) {
            PerLevelState state = entry.getValue();
            Sample sample = state.sample;
            ServerLevel level = server.getLevel(sample.levelKey());
            if (level == null) continue;
            long now = level.getGameTime();
            if (now - sample.recordedTick() > STALE_TICKS) {
                STATES.remove(entry.getKey());
                continue;
            }
            state.poseSpeed = poseAngularSpeed(state, sample, now);
        }

        for (ServerLevel level : server.getAllLevels()) {
            List<Map.Entry<String, PerLevelState>> here = new ArrayList<>();
            for (Map.Entry<String, PerLevelState> entry : STATES.entrySet()) {
                if (entry.getValue().sample.levelKey().equals(level.dimension())) here.add(entry);
            }
            if (here.isEmpty() || level.players().isEmpty()) continue;
            for (ServerPlayer player : level.players()) {
                announceNearest(player, here);
            }
        }
    }

    /** Action-bar the spin of whichever active sub-level's reporting tank is closest to the player. */
    private static void announceNearest(ServerPlayer player, List<Map.Entry<String, PerLevelState>> here) {
        Map.Entry<String, PerLevelState> nearest = null;
        Sample nearestSample = null;
        double bestDistanceSq = Double.MAX_VALUE;
        for (Map.Entry<String, PerLevelState> entry : here) {
            Sample sample = entry.getValue().sample;
            double distanceSq = player.position().distanceToSqr(sample.worldX(), sample.worldY(), sample.worldZ());
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                nearest = entry;
                nearestSample = sample;
            }
        }
        if (nearest == null) return;

        double poseSpeed = nearest.getValue().poseSpeed;
        double rpm = poseSpeed * 60.0 / (2 * Math.PI);
        String id = nearest.getKey().substring(0, Math.min(6, nearest.getKey().length()));
        Component line = Component.literal(String.format(Locale.ROOT,
                "spin: pose %.2f rad/s (%.1f RPM) | body %.2f | vel %.2f m/s | [%s, %d subs]",
                poseSpeed, rpm, nearestSample.bodyAngularSpeed(), nearestSample.linearSpeed(), id, here.size()))
                .withStyle(ChatFormatting.AQUA);
        player.displayClientMessage(line, true);
    }

    /** Angular speed (rad/s) from the orientation change since the last game tick we saw this sub-level. */
    private static double poseAngularSpeed(PerLevelState state, Sample sample, long now) {
        double[] prevQuat = state.lastOrientationQuat;
        Long prevTick = state.lastDerivedTick;
        double speed = 0;
        if (prevQuat != null && prevTick != null && now > prevTick) {
            // relative rotation = current * conjugate(previous); its half-angle is acos|w|.
            double conjX = -prevQuat[0], conjY = -prevQuat[1], conjZ = -prevQuat[2], conjW = prevQuat[3];
            double relativeW = sample.quatW() * conjW - sample.quatX() * conjX
                    - sample.quatY() * conjY - sample.quatZ() * conjZ;
            double halfAngle = Math.acos(Math.min(1.0, Math.abs(relativeW)));
            double dt = (now - prevTick) / 20.0;
            speed = (2 * halfAngle) / dt;
        }
        state.lastOrientationQuat = new double[]{sample.quatX(), sample.quatY(), sample.quatZ(), sample.quatW()};
        state.lastDerivedTick = now;
        return speed;
    }

    public static void clear() {
        STATES.clear();
    }
}
