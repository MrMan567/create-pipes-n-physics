package de.devin.pipesnphysics.engine;

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
 * <p>Two spin numbers are shown because a bearing-driven contraption spins KINEMATICALLY: the bearing
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

    private static final Map<String, Sample> SAMPLES = new ConcurrentHashMap<>();
    private static final Map<String, double[]> LAST_QUAT = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST_TICK = new ConcurrentHashMap<>();
    private static final Map<String, Double> POSE_SPEED = new ConcurrentHashMap<>();

    private SublevelSpinProbe() {}

    /** One physics tick's raw state: body spin, orientation quat, reporting tank world pos, tick stamp. */
    public record Sample(double bodyAngularSpeed, double linearSpeed,
                         double quatX, double quatY, double quatZ, double quatW,
                         double worldX, double worldY, double worldZ,
                         long recordedTick, ResourceKey<Level> levelKey) {}

    /** Store the latest sample for a sub-level; called from the (possibly off-thread) physics tick. */
    public static void record(String subLevelId, double angX, double angY, double angZ,
                              double linX, double linY, double linZ,
                              double quatX, double quatY, double quatZ, double quatW,
                              double worldX, double worldY, double worldZ,
                              long recordedTick, ResourceKey<Level> levelKey) {
        double bodyAngularSpeed = Math.sqrt(angX * angX + angY * angY + angZ * angZ);
        double linearSpeed = Math.sqrt(linX * linX + linY * linY + linZ * linZ);
        SAMPLES.put(subLevelId, new Sample(bodyAngularSpeed, linearSpeed, quatX, quatY, quatZ, quatW,
                worldX, worldY, worldZ, recordedTick, levelKey));
    }

    /** Latest pose-derived angular speed (rad/s) for a sub-level, or 0 if none seen — the real spin. */
    public static double angularSpeed(String subLevelId) {
        return POSE_SPEED.getOrDefault(subLevelId, 0.0);
    }

    /** Main-thread: derive pose-delta spin, drop stale samples, action-bar the nearest sub-level. */
    public static void tick(MinecraftServer server) {
        if (SAMPLES.isEmpty()) return;
        for (Map.Entry<String, Sample> entry : SAMPLES.entrySet()) {
            String key = entry.getKey();
            Sample sample = entry.getValue();
            ServerLevel level = server.getLevel(sample.levelKey());
            if (level == null) continue;
            long now = level.getGameTime();
            if (now - sample.recordedTick() > STALE_TICKS) {
                forget(key);
                continue;
            }
            POSE_SPEED.put(key, poseAngularSpeed(key, sample, now));
        }

        for (ServerLevel level : server.getAllLevels()) {
            List<Map.Entry<String, Sample>> here = new ArrayList<>();
            for (Map.Entry<String, Sample> e : SAMPLES.entrySet()) {
                if (e.getValue().levelKey().equals(level.dimension())) here.add(e);
            }
            if (here.isEmpty() || level.players().isEmpty()) continue;
            for (ServerPlayer player : level.players()) {
                announceNearest(player, here);
            }
        }
    }

    /** Action-bar the spin of whichever active sub-level's reporting tank is closest to the player. */
    private static void announceNearest(ServerPlayer player, List<Map.Entry<String, Sample>> here) {
        Map.Entry<String, Sample> nearest = null;
        double best = Double.MAX_VALUE;
        for (Map.Entry<String, Sample> e : here) {
            Sample s = e.getValue();
            double d = player.position().distanceToSqr(s.worldX(), s.worldY(), s.worldZ());
            if (d < best) {
                best = d;
                nearest = e;
            }
        }
        if (nearest == null) return;

        Sample s = nearest.getValue();
        double poseSpeed = POSE_SPEED.getOrDefault(nearest.getKey(), 0.0);
        double rpm = poseSpeed * 60.0 / (2 * Math.PI);
        String id = nearest.getKey().substring(0, Math.min(6, nearest.getKey().length()));
        Component line = Component.literal(String.format(Locale.ROOT,
                "spin: pose %.2f rad/s (%.1f RPM) | body %.2f | vel %.2f m/s | [%s, %d subs]",
                poseSpeed, rpm, s.bodyAngularSpeed(), s.linearSpeed(), id, here.size()))
                .withStyle(ChatFormatting.AQUA);
        player.displayClientMessage(line, true);
    }

    /** Angular speed (rad/s) from the orientation change since the last game tick we saw this sub-level. */
    private static double poseAngularSpeed(String key, Sample sample, long now) {
        double[] prev = LAST_QUAT.get(key);
        Long prevTick = LAST_TICK.get(key);
        double speed = 0;
        if (prev != null && prevTick != null && now > prevTick) {
            // relative rotation = current * conjugate(previous); its half-angle is acos|w|.
            double lx = -prev[0], ly = -prev[1], lz = -prev[2], lw = prev[3];
            double rw = sample.quatW() * lw - sample.quatX() * lx - sample.quatY() * ly - sample.quatZ() * lz;
            double halfAngle = Math.acos(Math.min(1.0, Math.abs(rw)));
            double dt = (now - prevTick) / 20.0;
            speed = (2 * halfAngle) / dt;
        }
        LAST_QUAT.put(key, new double[]{sample.quatX(), sample.quatY(), sample.quatZ(), sample.quatW()});
        LAST_TICK.put(key, now);
        return speed;
    }

    private static void forget(String key) {
        SAMPLES.remove(key);
        LAST_QUAT.remove(key);
        LAST_TICK.remove(key);
        POSE_SPEED.remove(key);
    }

    public static void clear() {
        SAMPLES.clear();
        LAST_QUAT.clear();
        LAST_TICK.clear();
        POSE_SPEED.clear();
    }
}
