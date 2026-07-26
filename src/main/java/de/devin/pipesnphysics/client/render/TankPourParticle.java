package de.devin.pipesnphysics.client.render;

import com.simibubi.create.content.fluids.particle.FluidStackParticle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * A pour droplet CLIPPED to a tank's interior. Create's stock pour particle keeps block physics,
 * and inside a tank block's collision shape vanilla physics clips every move to zero — the droplet
 * would freeze at the wall instead of falling. So this one turns physics OFF and clips itself:
 * horizontally clamped to the fed block's hull interior (it can never poke through the glass,
 * a droplet reaching a wall runs down it), removed the moment it reaches {@code floorY} — the
 * tank's VISIBLE fluid surface — so the stream ends exactly at the waterline (an empty tank's
 * puddle floor) and never falls below it. Lifetime is sized to the drop height, so a droplet
 * high in a tall tank does not vanish mid-air on the stock 4-tick roll.
 *
 * Client-only; spawned straight through the particle engine (never sent over the network, so it
 * needs no registered type/provider).
 */
public class TankPourParticle extends FluidStackParticle {
    private final double floorY;
    private final double minX, maxX, minZ, maxZ;

    private TankPourParticle(ClientLevel level, FluidStack fluid, double x, double y, double z,
                             double vx, double vy, double vz, double floorY,
                             double minX, double maxX, double minZ, double maxZ) {
        super(level, fluid, x, y, z, vx, vy, vz);
        this.floorY = floorY;
        this.minX = minX;
        this.maxX = maxX;
        this.minZ = minZ;
        this.maxZ = maxZ;
        this.hasPhysics = false;
        this.lifetime = Mth.clamp((int) (7 * Math.sqrt(Math.max(0, y - floorY))) + 10, 15, 60);
    }

    /** Spawn one clipped droplet; a no-op off the client level (defensive — callers are client-side). */
    public static void add(Level level, FluidStack fluid, double x, double y, double z,
                           double vx, double vy, double vz, double floorY,
                           double minX, double maxX, double minZ, double maxZ) {
        if (!(level instanceof ClientLevel clientLevel)) return;
        Minecraft.getInstance().particleEngine.add(new TankPourParticle(clientLevel, fluid,
                x, y, z, vx, vy, vz, floorY, minX, maxX, minZ, maxZ));
    }

    @Override
    public void tick() {
        super.tick();
        double clampedX = Mth.clamp(x, minX, maxX);
        double clampedZ = Mth.clamp(z, minZ, maxZ);
        if (clampedX != x) {
            x = clampedX;
            xd = 0;
        }
        if (clampedZ != z) {
            z = clampedZ;
            zd = 0;
        }
        if (y <= floorY) remove();
    }
}
