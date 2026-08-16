package de.devin.pipesnphysics.mixin;

import com.simibubi.create.content.fluids.pipes.valve.FluidValveBlockEntity;
import net.createmod.catnip.animation.LerpedFloat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the valve's handle position, so a test can assert that the needle a player sees matches
 * the opening the solver flows at. The server ticks the chaser too, so it is observable there.
 */
@Mixin(value = FluidValveBlockEntity.class, remap = false)
public interface FluidValveAccessor {
    @Accessor("pointer")
    LerpedFloat pipesnphysics$pointer();
}
