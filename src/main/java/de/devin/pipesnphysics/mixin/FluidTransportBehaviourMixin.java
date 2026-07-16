package de.devin.pipesnphysics.mixin;

import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import de.devin.pipesnphysics.engine.store.PipeFluidCell;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the engine's per-cell fluid state to Create's pipe behaviour ({@link PipeFluidCell}).
 *
 * The CONTENT is real, conserved volume, so unlike the old render-only fields it is written on
 * BOTH serialization paths: the disk save (reload resumes with the exact in-transit fluid; a
 * contraption assembly captures it into its data and disassembly restores it) and the client
 * packet (the client renderer draws pipes directly from it). The FLOW stamp is cosmetic
 * (direction + rate for the scroll animation), re-derived every tick, and rides only the client
 * packet — a saved copy would be stale by the first solve.
 */
@Mixin(value = FluidTransportBehaviour.class, remap = false)
public class FluidTransportBehaviourMixin implements PipeFluidCell {
    @Unique
    private FluidStack pipesnphysics$content = FluidStack.EMPTY;

    @Unique
    private int pipesnphysics$flowData = 0;

    @Override
    public FluidStack pipesnphysics$content() {
        return pipesnphysics$content;
    }

    @Override
    public void pipesnphysics$setContent(FluidStack content) {
        pipesnphysics$content = content;
    }

    @Override
    public int pipesnphysics$flowData() {
        return pipesnphysics$flowData;
    }

    @Override
    public void pipesnphysics$setFlowData(int data) {
        pipesnphysics$flowData = data;
    }

    @Inject(method = "write", at = @At("TAIL"))
    private void pipesnphysics$writeContent(CompoundTag nbt, HolderLookup.Provider registries,
                                            boolean clientPacket, CallbackInfo ci) {
        if (!pipesnphysics$content.isEmpty()) {
            nbt.put("PnpContent", pipesnphysics$content.saveOptional(registries));
        }
        if (clientPacket && pipesnphysics$flowData != 0) {
            nbt.putInt("PnpFlow", pipesnphysics$flowData);
        }
    }

    @Inject(method = "read", at = @At("TAIL"))
    private void pipesnphysics$readContent(CompoundTag nbt, HolderLookup.Provider registries,
                                           boolean clientPacket, CallbackInfo ci) {
        pipesnphysics$content = nbt.contains("PnpContent")
                ? FluidStack.parseOptional(registries, nbt.getCompound("PnpContent"))
                : FluidStack.EMPTY;
        if (clientPacket) pipesnphysics$flowData = nbt.getInt("PnpFlow"); // 0 when absent
    }
}
