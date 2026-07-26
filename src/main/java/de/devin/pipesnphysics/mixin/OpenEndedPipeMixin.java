package de.devin.pipesnphysics.mixin;

import com.simibubi.create.content.fluids.OpenEndedPipe;
import com.simibubi.create.content.fluids.pipes.VanillaFluidTargets;
import com.simibubi.create.foundation.fluid.FluidHelper;
import com.simibubi.create.foundation.mixin.accessor.FlowingFluidAccessor;
import com.simibubi.create.infrastructure.config.AllConfigs;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.compat.SableCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Redirects an open-ended pipe's fluid spill/intake to the projected REAL-WORLD
 * position when the pipe sits on a Sable sub-level.
 *
 * Sable stores a contraption's blocks at logical "plot" coordinates inside the host
 * level and renders/simulates it at a posed physical position in that same level, so
 * Create's {@code outputPos} is a plot coordinate the player never sees.
 *
 * We do the world interaction OURSELVES at the projected position and CANCEL Create's
 * method (HEAD inject, {@code cancellable}). Do NOT "simplify" this into swapping
 * {@code outputPos} and delegating back into Create's {@code provideFluidToSpace}:
 * Sable installs its own mixin on this class whose {@code sable$preventInWorldPlace}
 * is a {@code @Redirect} on the {@code world.setBlock(...)} INSIDE provideFluidToSpace,
 * which eats the placement for sub-level pipes (Sable keeps the fluid in the sub-level).
 * Letting Create's method run therefore drops the spill silently — the exact bug that
 * regressed this feature. Cancelling at HEAD means Sable's redirect never executes, so
 * our real-world placement wins.
 *
 * Off a sub-level (or with the companion absent, or the feature disabled)
 * {@link #pipesnphysics$getWorldOutputPos} returns null and Create's stock behavior
 * (and Sable's redirects) run untouched.
 *
 * Because we take over the placement, we must also mirror Create's placement POLICY that
 * lives past the {@code setBlock}: the {@code pipesPlaceFluidSourceBlocks} server config and
 * the ultra-warm evaporation of water (a Nether open end must hiss, not leave a water block).
 */
@Mixin(value = OpenEndedPipe.class, remap = false)
public class OpenEndedPipeMixin {
    @Shadow private Level world;
    @Shadow private BlockPos outputPos;

    @Inject(method = "provideFluidToSpace", at = @At("HEAD"), cancellable = true)
    private void pipesnphysics$provideFluidToWorld(FluidStack fluid, boolean simulate,
                                                   CallbackInfoReturnable<Boolean> cir) {
        BlockPos worldBlockPos = pipesnphysics$getWorldOutputPos();

        // Force-output: keep draining OUT even when the mouth already faces a fluid SOURCE block (its
        // own earlier spill, or a natural pool). Create normally returns false there and the network
        // backs up; when enabled we accept and DISCARD the fluid (the space is already full, so nothing
        // is placed). Runs on both the main level (outputPos is the real block) and a sub-level (the
        // projected world block), since Create's fill drives this method on either.
        if (PipesNPhysicsConfig.FORCE_OPEN_END_OUTPUT.get() && world != null
                && !fluid.isEmpty() && fluid.getFluid() instanceof FlowingFluid) {
            BlockPos occupied = worldBlockPos != null ? worldBlockPos : outputPos;
            if (occupied != null && world.isLoaded(occupied)
                    && world.getBlockState(occupied).getFluidState().isSource()) {
                cir.setReturnValue(true);
                return;
            }
        }

        if (worldBlockPos == null) return;

        if (world == null || !world.isLoaded(worldBlockPos)) {
            cir.setReturnValue(false);
            return;
        }
        if (fluid.isEmpty() || !(fluid.getFluid() instanceof FlowingFluid)) {
            cir.setReturnValue(false);
            return;
        }

        BlockState state = world.getBlockState(worldBlockPos);
        FluidState fluidState = state.getFluidState();
        boolean waterlog = state.hasProperty(BlockStateProperties.WATERLOGGED);
        if (!waterlog && !state.canBeReplaced()) {
            cir.setReturnValue(false);
            return;
        }
        if (fluidState.isSource()) {
            cir.setReturnValue(false);
            return;
        }
        if (waterlog && fluid.getFluid() != Fluids.WATER) {
            cir.setReturnValue(false); // a waterloggable target only accepts water
            return;
        }
        if (simulate) {
            cir.setReturnValue(true);
            return;
        }

        // We cancelled Create's provideFluidToSpace to place at the projected world pos, so we
        // must also honour the two placement POLICIES it applies past this point — otherwise a
        // contraption open end mints blocks the base game would never allow. Both branches still
        // ACCEPT the fluid (return true) so the source tank drains; only the world block differs.
        if (!AllConfigs.server().fluids.pipesPlaceFluidSourceBlocks.get()) {
            cir.setReturnValue(true); // server forbids source placement: consume, place nothing
            return;
        }
        if (world.dimensionType().ultraWarm() && FluidHelper.isTag(fluid, FluidTags.WATER)) {
            // Water evaporates in the Nether (and any ultra-warm dimension) — hiss, don't place.
            world.playSound(null, worldBlockPos.getX(), worldBlockPos.getY(), worldBlockPos.getZ(),
                    SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.5F,
                    2.6F + (world.random.nextFloat() - world.random.nextFloat()) * 0.8F);
            cir.setReturnValue(true);
            return;
        }
        if (waterlog) {
            // Waterlog the target instead of overwriting it, as Create does.
            world.setBlock(worldBlockPos, state.setValue(BlockStateProperties.WATERLOGGED, true),
                    Block.UPDATE_ALL);
            world.scheduleTick(worldBlockPos, Fluids.WATER, 1);
            cir.setReturnValue(true);
            return;
        }

        world.setBlock(worldBlockPos, fluid.getFluid()
                .defaultFluidState()
                .createLegacyBlock(), Block.UPDATE_ALL);
        cir.setReturnValue(true);
    }

    @Inject(method = "removeFluidFromSpace", at = @At("HEAD"), cancellable = true)
    private void pipesnphysics$removeFluidFromWorld(boolean simulate,
                                                    CallbackInfoReturnable<FluidStack> cir) {
        if (world == null || outputPos == null) return;
        BlockPos worldBlockPos = pipesnphysics$getWorldOutputPos(); // null off a sub-level

        // Sub-level mouth: the source may be a block ON the contraption (the raw plot-coords outputPos)
        // OR a block in the host world under the projected mouth — mirroring spill, which goes to the
        // world. Try the sub-level block first, then the world.
        if (worldBlockPos != null) {
            FluidStack drained = pipesnphysics$drainSourceAt(outputPos, simulate);
            if (drained.isEmpty()) drained = pipesnphysics$drainSourceAt(worldBlockPos, simulate);
            if (!drained.isEmpty()) {
                cir.setReturnValue(drained);
                return;
            }
        }

        // Cross-level piping: consume from another Sable level whose bounds overlap this mouth. This
        // must run even for a MAIN-LEVEL mouth (worldBlockPos == null) that a contraption passes over —
        // else the solver reads a source there but the drain moves nothing. Kept in step with the read
        // side (BoundaryColumn.intakeFluid), which visits the same overlapping blocks in the same order.
        if (PipesNPhysicsConfig.ENABLE_CROSS_LEVEL_PIPING.get()) {
            FluidStack other = SableCompat.atOverlappingContraptions(world, outputPos, (l, p) -> {
                FluidStack found = pipesnphysics$drainSourceAt(p, simulate);
                return found.isEmpty() ? null : found; // null keeps Sable's traversal searching
            });
            if (other != null) {
                cir.setReturnValue(other);
                return;
            }
        }

        // A sub-level mouth with nothing to drink anywhere: cancel with EMPTY, since we own its placement
        // (letting Create's stock method run would consume the raw plot outputPos). A main-level mouth
        // with no overlapping contraption falls through untouched to Create's stock behaviour.
        if (worldBlockPos != null) cir.setReturnValue(FluidStack.EMPTY);
    }

    /**
     * Drain a fluid source at {@code pos}, CONSUMING it: a cauldron/honey block drains itself; a
     * WATERLOGGED block is un-waterlogged IN PLACE (the fence/slab/stairs stays, its water leaves);
     * a finite source drops to level 14 (flows away); a self-regenerating lake is left as the source
     * (its getNewLiquid would re-source level 14 anyway). Mirrors Create's own consume — the old mixin
     * left EVERY source in place (level 0), which minted infinite fluid from a finite one (so
     * finite/sub-level intake had to be gated off), and had no waterlog branch, so draining a
     * waterlogged block OVERWROTE it with flowing water (destroying the block). EMPTY if there is no
     * drainable source here.
     */
    @Unique
    private FluidStack pipesnphysics$drainSourceAt(BlockPos pos, boolean simulate) {
        if (!world.isLoaded(pos)) return FluidStack.EMPTY;
        BlockState state = world.getBlockState(pos);
        FluidStack drainable = VanillaFluidTargets.drainBlock(world, pos, state, simulate);
        if (!drainable.isEmpty()) return drainable;
        FluidState fluidState = state.getFluidState();
        if (!fluidState.isSource()) return FluidStack.EMPTY;

        FluidStack stack = new FluidStack(fluidState.getType(), 1000);
        if (simulate) return stack;
        if (state.hasProperty(BlockStateProperties.WATERLOGGED)) {
            // Clear the waterlogging and leave the block itself, exactly as Create's removeFluidFromSpace
            // does — never overwrite the waterloggable block with a flowing-water block.
            world.setBlock(pos, state.setValue(BlockStateProperties.WATERLOGGED, false), Block.UPDATE_ALL);
            world.scheduleTick(pos, Fluids.WATER, 1);
            return stack;
        }
        BlockState drainedState = fluidState.createLegacyBlock().setValue(LiquidBlock.LEVEL, 14);
        boolean regenerates = drainedState.getFluidState().getType() instanceof FlowingFluidAccessor flowing
                && flowing.create$getNewLiquid(world, pos, drainedState).equals(fluidState);
        if (!regenerates) world.setBlock(pos, drainedState, Block.UPDATE_ALL);
        return stack;
    }

    /**
     * The projected real-world block this open end should interact with, or null to
     * leave Create's stock behavior in place: off a sub-level (the projection equals
     * the logical position), without the Sable companion, or when the feature is
     * disabled in config.
     */
    @Unique
    private BlockPos pipesnphysics$getWorldOutputPos() {
        if (world == null || outputPos == null) return null;
        if (!SableCompat.isCompanionLoaded()) return null;
        if (!PipesNPhysicsConfig.ENABLE_OPEN_END_WORLD_PLACEMENT.get()) return null;

        Vec3 worldPos = SableCompat.getWorldPos(world, outputPos);
        BlockPos worldBlockPos = BlockPos.containing(worldPos);

        if (worldBlockPos.equals(outputPos)) return null;
        return worldBlockPos;
    }
}
