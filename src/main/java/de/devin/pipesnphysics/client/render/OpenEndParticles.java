package de.devin.pipesnphysics.client.render;

import com.simibubi.create.content.fluids.FluidFX;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.PipeConnection;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import de.devin.pipesnphysics.engine.boundary.BoundaryColumn;
import de.devin.pipesnphysics.engine.store.PipeFluidCell;
import de.devin.pipesnphysics.engine.store.PipeStore;
import de.devin.pipesnphysics.engine.store.PipeWindow;
import de.devin.pipesnphysics.mixin.FluidTankAccessor;
import net.createmod.catnip.animation.LerpedFloat;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Spawns Create's pouring-liquid particles at open pipe mouths from the engine's own synced
 * state. Create spawned them in {@code PipeConnection.tickFlowProgress}, driven by client
 * {@code Flow} objects — a tick {@code GravityFlowMixin} cancels, and the engine writes no
 * Flows at all — so mouths poured fluid with no FX. The replacement reads what the engine
 * DOES sync: a cell whose flow stamp points out an open mouth is pouring (outbound), one
 * whose stamp points away from a mouth behind it is drinking (inbound). Same cadence as
 * Create (one particle per pouring mouth per client tick, camera within
 * {@code MAX_PARTICLE_RENDER_DISTANCE}) through the same {@link FluidFX} helper.
 *
 * Beyond Create parity, a stamp pointing into a Create TANK whose visible fluid stands BELOW
 * the pipe's opening lip pours too — the stream falls through the tank's head space until the
 * fluid covers the aperture, then goes quiet (a submerged feed splashes nothing). The
 * "covered" test rebuilds the tank renderer's own surface (the eased {@code LerpedFloat} fill
 * over the {@link BoundaryColumn#TANK_RENDER_FLOOR inset} range) against the same
 * {@link PipeWindow#lipY aperture datum} every engine gate uses, so the splash stops exactly
 * where the pixels meet. These droplets are CLIPPED to the tank ({@link TankPourParticle}):
 * spawned in the aperture window just inside the wall, clamped to the hull interior, and
 * removed at that same visible surface — Create's stock pour keeps block physics, which
 * freezes a particle inside the tank's collision shape and lets its spread poke through the
 * glass. Liquids only (a buoyant gas does not fall), delivery only (the lip rule means nothing
 * ever drains from above the waterline), and never through the tank's BOTTOM face (fluid
 * pushed up from below wells, it does not pour).
 *
 * A PUMP flush against a tank or a mouth delivers across a ZERO-CELL edge and owns no cell to
 * carry a stamp, so the executor stamps the pump itself ({@code FlowNetwork.stampPumps}) — a
 * sync-only direction+rate, no volume. It still stores no fluid, so what it pours is read off its
 * upstream flank ({@link #arrivingFrom}). Remaining gaps, stamp-less by construction: a
 * settle-phase pour (dregs draining out a mouth with no solved flow) and a zero-cell edge with no
 * pump on it (a junction flush against the mouth/tank).
 *
 * Called from the heartbeat mixin's client branch each pipe tick. Deliberately free of
 * client-only imports (the {@code ValveArrowClient} pattern), so the common mixin may call it
 * behind a plain {@code isClientSide} check: particle spawning goes through
 * {@code Level.addAlwaysVisibleParticle} (a no-op outside the client level) and the distance
 * gate reads {@code level.players()} instead of the camera entity. The one client-importing
 * class it references, {@link TankPourParticle}, sits behind a method call the JVM resolves
 * lazily on first execution — client-side by construction — so this class itself stays
 * loadable on a dedicated server and the mixin never touches the client class.
 */
public final class OpenEndParticles {
    /** Half-width of the 4×4 px connection aperture the tank stream jitters within. */
    private static final float APERTURE_RADIUS = 2 / 16f;
    /** Hull clamp inset for droplets inside a tank — the tank wall plus a droplet's half-width. */
    private static final double INTERIOR_INSET = 0.1;

    private OpenEndParticles() {}

    /** Spawn this tick's pour particles for one pipe cell, if its flow crosses an open mouth. */
    public static void spawnAt(Level level, FluidTransportBehaviour pipe) {
        if (!(pipe instanceof PipeFluidCell cell)) return;
        Direction dir = PipeStore.flowDirection(cell.pipesnphysics$flowData());
        if (dir == null) return;
        BlockPos pos = pipe.blockEntity.getBlockPos();
        FluidStack content = cell.pipesnphysics$content();
        // A stamped cell holding NOTHING is a pump (it stores no fluid): what it pours is whatever
        // stands behind it, so the stream still draws in the right liquid.
        if (content.isEmpty()) content = arrivingFrom(level, pos, dir);
        if (content.isEmpty()) return;
        if (!playerNearby(level, pos)) return;
        BlockState state = pipe.blockEntity.getBlockState();
        if (mouthOn(level, state, pipe, pos, dir)) {
            pour(level, pos, content, dir, false);
        } else if (mouthOn(level, state, pipe, pos, dir.getOpposite())) {
            pour(level, pos, content, dir.getOpposite(), true);
        } else {
            double tankSurface = pouredTankSurface(level, state, pipe, pos, dir, content);
            if (!Double.isNaN(tankSurface)) pourIntoTank(level, pos, dir, content, tankSurface);
        }
    }

    /**
     * The visible fluid surface of a Create tank this cell's stamped flow discharges into ABOVE
     * that surface — a falling stream through the head space — or NaN when there is nothing to
     * pour: a submerged feed, a gas (nothing falls), the tank's bottom face (fluid wells up, it
     * does not pour), or a horizontal vessel / anything whose rendered surface we cannot rebuild.
     */
    private static double pouredTankSurface(Level level, BlockState state, FluidTransportBehaviour pipe,
                                            BlockPos pos, Direction face, FluidStack content) {
        if (face == Direction.UP) return Double.NaN;
        if (content.getFluid().getFluidType().isLighterThanAir()) return Double.NaN;
        if (!pipe.canHaveFlowToward(state, face)) return Double.NaN;
        if (!(level.getBlockEntity(pos.relative(face)) instanceof FluidTankBlockEntity tank)) {
            return Double.NaN;
        }
        double surface = visibleTankSurface(tank);
        return surface < PipeWindow.lipY(level, pos) ? surface : Double.NaN;
    }

    /**
     * One clipped droplet ({@link TankPourParticle}) out of the connection aperture: spawned in
     * the 4×4 px window just inside the tank wall, drifting gently inward, clamped to the fed
     * block's hull interior and removed at the tank's visible surface.
     */
    private static void pourIntoTank(Level level, BlockPos pos, Direction face, FluidStack fluid,
                                     double surfaceY) {
        BlockPos tankPos = pos.relative(face);
        double minX = tankPos.getX() + INTERIOR_INSET;
        double maxX = tankPos.getX() + 1 - INTERIOR_INSET;
        double minZ = tankPos.getZ() + INTERIOR_INSET;
        double maxZ = tankPos.getZ() + 1 - INTERIOR_INSET;
        Vec3 dirVec = Vec3.atLowerCornerOf(face.getNormal());
        Vec3 spawn = VecHelper.offsetRandomly(Vec3.ZERO, level.random, APERTURE_RADIUS)
                .multiply(VecHelper.axisAlingedPlaneOf(dirVec))
                .add(dirVec.scale(0.5 + level.random.nextFloat() * 0.1))
                .add(Vec3.atCenterOf(pos));
        Vec3 motion = dirVec.scale(0.03 + level.random.nextFloat() * 0.03);
        TankPourParticle.add(level, fluid, Mth.clamp(spawn.x, minX, maxX), spawn.y,
                Mth.clamp(spawn.z, minZ, maxZ), motion.x, motion.y, motion.z,
                surfaceY, minX, maxX, minZ, maxZ);
    }

    /**
     * The world-Y of the tank fluid the player SEES — the tank renderer's eased fill level over
     * Create's inset render range (the {@code BoundaryColumn.renderedSurface} formula, rebuilt
     * here from client state so it tracks the animation, not the last server tick). NaN when
     * there is nothing to read (no controller yet, a horizontal vessel's sideways fill).
     */
    private static double visibleTankSurface(FluidTankBlockEntity tank) {
        FluidTankBlockEntity controller = tank.getControllerBE();
        if (controller == null || controller.getMainConnectionAxis() != Direction.Axis.Y) return Double.NaN;
        LerpedFloat fill = controller.getFluidLevel();
        if (fill == null) return Double.NaN;
        int height = ((FluidTankAccessor) (Object) controller).pipesnphysics$getHeight();
        return controller.getBlockPos().getY() + BoundaryColumn.TANK_RENDER_FLOOR
                + fill.getValue() * (height - BoundaryColumn.TANK_RENDER_LOSS);
    }

    /**
     * The fluid arriving at a stamped cell that stores none of its own — a PUMP, which owns no
     * cell volume yet drives a delivery straight across a zero-cell edge into the tank or mouth it
     * sits against. Read what stands on its upstream flank: the pipe cell feeding it, or the tank
     * it lifts from. EMPTY when neither can say, and the pour is then skipped rather than guessed.
     */
    private static FluidStack arrivingFrom(Level level, BlockPos pos, Direction dir) {
        BlockPos behind = pos.relative(dir.getOpposite());
        if (FluidPropagator.getPipe(level, behind) instanceof PipeFluidCell feed) {
            return feed.pipesnphysics$content();
        }
        if (level.getBlockEntity(behind) instanceof FluidTankBlockEntity tank) {
            FluidTankBlockEntity controller = tank.getControllerBE();
            if (controller != null) {
                return ((FluidTankAccessor) (Object) controller).pipesnphysics$getTankInventory()
                        .getFluid();
            }
        }
        return FluidStack.EMPTY;
    }

    /** Whether this cell's {@code face} opens into a mouth — Create's own open-end test. */
    private static boolean mouthOn(Level level, BlockState state, FluidTransportBehaviour pipe,
                                   BlockPos pos, Direction face) {
        return pipe.canHaveFlowToward(state, face) && FluidPropagator.isOpenEnd(level, pos, face);
    }

    private static void pour(Level level, BlockPos pos, FluidStack fluid, Direction face,
                             boolean inbound) {
        FluidFX.spawnPouringLiquid(level, pos, 1, FluidFX.getFluidParticle(fluid),
                PipeConnection.RIM_RADIUS, Vec3.atLowerCornerOf(face.getNormal()), inbound);
    }

    private static boolean playerNearby(Level level, BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        for (Player player : level.players()) {
            if (player.position().closerThan(center, PipeConnection.MAX_PARTICLE_RENDER_DISTANCE)) {
                return true;
            }
        }
        return false;
    }
}
