package de.devin.pipesnphysics.mixin;

import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.compat.SableCompat;
import de.devin.pipesnphysics.compat.SablePhysicsCompat;
import de.devin.pipesnphysics.engine.motion.MomentumField;
import de.devin.pipesnphysics.engine.probe.SublevelSpinProbe;
import de.devin.pipesnphysics.physics.TankMassFormulas;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = FluidTankBlockEntity.class, remap = false)
public class FluidTankMassMixin implements BlockEntitySubLevelActor {

    @Override
    public void sable$physicsTick(ServerSubLevel subLevel, RigidBodyHandle handle, double timeStep) {
        FluidTankBlockEntity self = (FluidTankBlockEntity) (Object) this;

        // Debug spike: read the contraption's spin server-side and surface it in world, independent
        // of the mass feature. Both the body's angular velocity AND its orientation are recorded — a
        // bearing spins the body KINEMATICALLY (it sets the pose, so getAngularVelocity reads ~0), so
        // the real spin is derived from the pose delta (one controller reports per sub-level).
        if (self.isController() && PipesNPhysicsConfig.DEBUG_SUBLEVEL_SPIN.get()) {
            Vector3dc angular = handle.getAngularVelocity();
            Vector3dc linear = handle.getLinearVelocity();
            var pose = subLevel.logicalPose();
            double qx = 0, qy = 0, qz = 0, qw = 1;
            if (pose != null) {
                var orientation = pose.orientation();
                qx = orientation.x();
                qy = orientation.y();
                qz = orientation.z();
                qw = orientation.w();
            }
            Vec3 world = SableCompat.getWorldPos(subLevel.getLevel(), self.getBlockPos());
            SublevelSpinProbe.record(subLevel.getUniqueId().toString(),
                    angular.x(), angular.y(), angular.z(),
                    linear.x(), linear.y(), linear.z(),
                    qx, qy, qz, qw,
                    world.x, world.y, world.z,
                    subLevel.getLevel().getGameTime(), subLevel.getLevel().dimension());
        }

        // Momentum-driven head: record the rigid body's linear velocity and a reference position each
        // physics step so the engine can differentiate it into a frame acceleration and tilt the fluid
        // opposite it (the linear counterpart of the centrifugal push). Controller reports once per body.
        if (self.isController() && PipesNPhysicsConfig.ENABLE_MOMENTUM_HEAD.get()) {
            Vector3dc velocity = handle.getLinearVelocity();
            Vec3 origin = SableCompat.getWorldPos(subLevel.getLevel(), self.getBlockPos());
            MomentumField.record(subLevel.getUniqueId().toString(),
                    velocity.x(), velocity.y(), velocity.z(),
                    origin.x, origin.y, origin.z, timeStep, subLevel.getLevel().getGameTime());
        }

        if (!PipesNPhysicsConfig.ENABLE_DYNAMIC_TANK_MASS.get()) return;

        if (!self.isController()) return;

        FluidTank tank = ((FluidTankAccessor) self).pipesnphysics$getTankInventory();
        int fluidAmount = tank.getFluidAmount();
        if (fluidAmount <= 0) {
            // Drained empty: withdraw the mass applied while it held fluid, else the contraption
            // stays permanently heavy as if the tank were still full.
            SablePhysicsCompat.withdraw(subLevel, self.getBlockPos());
            return;
        }

        int capacity = tank.getCapacity();
        if (capacity <= 0) return;

        FluidStack fluidStack = tank.getFluid();
        int density = fluidStack.getFluid().getFluidType().getDensity(fluidStack);
        double massKg = TankMassFormulas.fluidMassKg(
                fluidAmount, density, PipesNPhysicsConfig.FLUID_MASS_PER_BUCKET.get());
        double fillFraction = TankMassFormulas.fillFraction(
                fluidAmount, capacity);

        SablePhysicsCompat.applyFluidWeight(subLevel, self.getBlockPos(), fillFraction, massKg);
    }
}
