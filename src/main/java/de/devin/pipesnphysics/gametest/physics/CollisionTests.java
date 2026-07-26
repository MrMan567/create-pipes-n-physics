package de.devin.pipesnphysics.gametest.physics;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.PipeConnection;
import com.simibubi.create.content.fluids.pipes.valve.FluidValveBlock;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.api.FluidHandlerApi;
import de.devin.pipesnphysics.api.FluidHandlerRole;
import de.devin.pipesnphysics.client.PipeStatusText;
import de.devin.pipesnphysics.display.PipeDisplayMetric;
import de.devin.pipesnphysics.engine.EdgeFlow;
import de.devin.pipesnphysics.engine.EngineTickHandler;
import de.devin.pipesnphysics.engine.FlowSolver;
import de.devin.pipesnphysics.engine.FluidEngine;
import de.devin.pipesnphysics.engine.PipeFlowExecutor;
import de.devin.pipesnphysics.engine.Solution;
import de.devin.pipesnphysics.engine.boundary.BoundaryColumn;
import de.devin.pipesnphysics.engine.boundary.FluidCaps;
import de.devin.pipesnphysics.engine.boundary.HandlerRoles;
import de.devin.pipesnphysics.engine.boundary.OpenEndPipes;
import de.devin.pipesnphysics.engine.boundary.RelayDetector;
import de.devin.pipesnphysics.engine.flow.FlowNetwork;
import de.devin.pipesnphysics.engine.graph.Edge;
import de.devin.pipesnphysics.engine.graph.Graph;
import de.devin.pipesnphysics.engine.graph.GraphBuilder;
import de.devin.pipesnphysics.engine.graph.Node;
import de.devin.pipesnphysics.engine.net.PipeStatusPayload;
import de.devin.pipesnphysics.engine.probe.PipeProbe;
import de.devin.pipesnphysics.engine.store.PipeStore;
import de.devin.pipesnphysics.engine.valve.ValveCharacteristic;
import de.devin.pipesnphysics.engine.valve.ValveThrottle;
import de.devin.pipesnphysics.handler.NetworkEditHandler;
import de.devin.pipesnphysics.mixin.FluidTankAccessor;
import de.devin.pipesnphysics.mixin.PipeConnectionAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.server.level.ServerLevel;
import net.createmod.catnip.lang.LangNumberFormat;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import de.devin.pipesnphysics.PipesNPhysics;
import de.devin.pipesnphysics.TestSideHandlers;
import static de.devin.pipesnphysics.gametest.GameTestSupport.*;

/**
 * Crossing the streams: two fluids driven/pressed together break the pipe (Create parity).
 */
@GameTestHolder(PipesNPhysics.ID)
@PrefixGameTestTemplate(false)
public class CollisionTests {

    /**
     * Two different fluids driven together in one pipe must react exactly like Create's crossing the
     * streams: the pipe BREAKS and a reactive pair leaves its block (water + lava → cobblestone). Our
     * transport-cancel mixin removed Create's own {@code FluidReactions.handlePipeFlowCollision}; the
     * executor restores it. Rig: a full water tank communicating with an empty sink through a flat
     * run whose MIDDLE cell is pre-filled with lava — the water flow reaches the lava cell, and the
     * cell must turn to stone instead of pistoning the lava into the (water) sink.
     */
    @GameTest(template = "physics/collision_flat_run", templateNamespace = PipesNPhysics.ID, timeoutTicks = 120)
    public static void crossingTheStreamsBreaksThePipe(GameTestHelper helper) {
        BlockPos source = new BlockPos(1, 1, 1); // tank—pipe×3—tank flat run (collision_flat_run)
        BlockPos lavaCell = new BlockPos(3, 1, 1);

        helper.runAfterDelay(5, () -> {
            fillFluid(helper, source, Fluids.WATER, 8000); // full → drives flow toward the empty sink
            PipeStore.Store cell = PipeStore.at(helper.getLevel(), helper.absolutePos(lavaCell));
            if (cell == null) {
                helper.fail("no pipe store at " + lavaCell.toShortString());
                return;
            }
            cell.insert(new FluidStack(Fluids.LAVA, PipeStore.capacityMb()), PipeStore.capacityMb());
            cell.flush();
            EngineTickHandler.markChanged(helper.getLevel(), helper.absolutePos(lavaCell));
        });
        helper.runAfterDelay(80, () -> {
            if (FluidPropagator.getPipe(helper.getLevel(), helper.absolutePos(lavaCell)) != null) {
                helper.fail("the pipe survived the fluid collision — it must break (crossing the streams)");
                return;
            }
            var state = helper.getBlockState(lavaCell);
            if (!state.is(Blocks.COBBLESTONE)) {
                helper.fail("water + lava collided but left " + state + " instead of cobblestone");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Crossing the streams with NO flow: two tanks of different fluids joined by a pipe must still
     * react. The brigade never catches this (the water pass and lava pass each bail with a single
     * participant — the opposite tank walls the other fluid — so the run is idle, solved=0), yet a
     * lava tank joined to a water-filled pipe cell is incompatible with it exactly as Create pulls
     * both fluids into the pipe. The idle settle must break the mouth cell to cobblestone. Rig: a
     * FULL water tank and a FULL lava tank at the ends of a flat water-filled run — nothing flows,
     * and the cell touching the lava tank must turn to stone.
     */
    @GameTest(template = "physics/collision_flat_run", templateNamespace = PipesNPhysics.ID, timeoutTicks = 120)
    public static void restingTanksOfDifferentFluidsCollide(GameTestHelper helper) {
        BlockPos waterTank = new BlockPos(1, 1, 1); // tank—pipe×3—tank flat run (collision_flat_run)
        BlockPos lavaTank = new BlockPos(5, 1, 1);
        BlockPos mouthCell = new BlockPos(4, 1, 1); // the pipe cell touching the lava tank

        helper.runAfterDelay(5, () -> {
            fillFluid(helper, waterTank, Fluids.WATER, 8000);
            fillFluid(helper, lavaTank, Fluids.LAVA, 8000); // full → its surface clears the pipe lip
            for (int x = 2; x <= 4; x++) {
                BlockPos rel = new BlockPos(x, 1, 1);
                PipeStore.Store cell = PipeStore.at(helper.getLevel(), helper.absolutePos(rel));
                if (cell == null) {
                    helper.fail("no pipe store at " + rel.toShortString());
                    return;
                }
                cell.insert(new FluidStack(Fluids.WATER, PipeStore.capacityMb()), PipeStore.capacityMb());
                cell.flush();
            }
            EngineTickHandler.markChanged(helper.getLevel(), helper.absolutePos(mouthCell));
        });
        helper.runAfterDelay(100, () -> {
            if (FluidPropagator.getPipe(helper.getLevel(), helper.absolutePos(mouthCell)) != null) {
                helper.fail("the pipe touching the lava tank survived — resting cross-streams must break it");
                return;
            }
            if (!helper.getBlockState(mouthCell).is(Blocks.COBBLESTONE)) {
                helper.fail("resting water + lava collided but left "
                        + helper.getBlockState(mouthCell) + " instead of cobblestone");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * The exact live report: a nearly-empty water tank and a LOW lava tank sit ABOVE the pipe, a
     * U-run drops between them, and the run rests FULL of water — an idle edge (solved=0). The old
     * driven-only detection did nothing; the resting-boundary check must still break the vertical
     * riser touching the lava tank. Faithful to the /pipegraph dump: tanks above (lip at the riser
     * block bottom), lava at 500/8000, water pre-filling the run — no fill-level gate, since the
     * lava tank is simply incompatible with the water in its mouth cell.
     */
    @GameTest(template = "physics/collision_u_below", templateNamespace = PipesNPhysics.ID, timeoutTicks = 120,
            batch = "collisionResting")
    public static void restingWaterPipeBelowALavaTankCollides(GameTestHelper helper) {
        // Built at runtime on a blank canvas (collision_u_below is an empty template): this
        // resting-collision fires on the exact settle tick a runtime setBlock produces, whereas a
        // PRE-PLACED structure lets the run's water redistribute into the tanks before it can react.
        Block pipe = AllBlocks.FLUID_PIPE.get();
        BlockState riserY = AllBlocks.GLASS_FLUID_PIPE.get().defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS,
                        Direction.Axis.Y);
        BlockPos waterTank = new BlockPos(0, 3, 0);
        BlockPos lavaTank = new BlockPos(3, 3, 0);
        BlockPos lavaMouth = new BlockPos(3, 2, 0); // vertical glass riser under the lava tank
        helper.setBlock(waterTank, AllBlocks.FLUID_TANK.get());
        helper.setBlock(new BlockPos(0, 2, 0), riserY);
        helper.setBlock(new BlockPos(0, 1, 0), pipeState(pipe, Direction.UP, Direction.EAST));
        helper.setBlock(new BlockPos(1, 1, 0), pipeState(pipe, Direction.WEST, Direction.EAST));
        helper.setBlock(new BlockPos(2, 1, 0), pipeState(pipe, Direction.WEST, Direction.EAST));
        helper.setBlock(new BlockPos(3, 1, 0), pipeState(pipe, Direction.WEST, Direction.UP));
        helper.setBlock(lavaMouth, riserY);
        helper.setBlock(lavaTank, AllBlocks.FLUID_TANK.get());

        List<BlockPos> run = List.of(new BlockPos(0, 2, 0), new BlockPos(0, 1, 0),
                new BlockPos(1, 1, 0), new BlockPos(2, 1, 0), new BlockPos(3, 1, 0), lavaMouth);
        helper.runAfterDelay(5, () -> {
            fillFluid(helper, waterTank, Fluids.WATER, 250);  // nearly empty, like the report
            fillFluid(helper, lavaTank, Fluids.LAVA, 500);    // 500/8000 — a LOW tank, no reach gate
            for (BlockPos rel : run) {
                PipeStore.Store cell = PipeStore.at(helper.getLevel(), helper.absolutePos(rel));
                if (cell == null) {
                    helper.fail("no pipe store at " + rel.toShortString());
                    return;
                }
                cell.insert(new FluidStack(Fluids.WATER, PipeStore.capacityMb()), PipeStore.capacityMb());
                cell.flush();
            }
            EngineTickHandler.markChanged(helper.getLevel(), helper.absolutePos(lavaMouth));
        });
        helper.runAfterDelay(100, () -> {
            if (FluidPropagator.getPipe(helper.getLevel(), helper.absolutePos(lavaMouth)) != null) {
                helper.fail("the water riser under the low lava tank survived — it must break (cross-streams)");
                return;
            }
            if (!helper.getBlockState(lavaMouth).is(Blocks.COBBLESTONE)) {
                helper.fail("water riser + low lava tank left "
                        + helper.getBlockState(lavaMouth) + " instead of cobblestone");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * The live report: a water tank and a lava tank joined by ONE idle run whose cells have settled
     * SPLIT — water drawn in from the water end, lava from the lava end, meeting deep in the run
     * (holds "250:Water 250:Water 250:Water 250:Lava 250:Lava"). Each MOUTH cell matches its own
     * tank, so the old end-cell-only boundary check saw no collision and the two fluids just sat
     * there touching mid-run. The press-the-column check must follow each tank's column inward to
     * the interface and break it. Rig: full water + full lava tanks at the ends of a 5-cell flat
     * run pre-filled water|water|water|lava|lava — the water cell touching the lava column must
     * turn to stone.
     */
    @GameTest(template = "physics/collision_split_run", templateNamespace = PipesNPhysics.ID, timeoutTicks = 120)
    public static void restingSplitFluidRunCollidesMidRun(GameTestHelper helper) {
        BlockPos waterTank = new BlockPos(1, 1, 1); // tank—pipe×5—tank flat run (collision_split_run)
        BlockPos lavaTank = new BlockPos(7, 1, 1);
        BlockPos interfaceCell = new BlockPos(4, 1, 1); // last WATER cell, touching the lava column

        helper.runAfterDelay(5, () -> {
            fillFluid(helper, waterTank, Fluids.WATER, 8000);
            fillFluid(helper, lavaTank, Fluids.LAVA, 8000);
            // The settled split: water in cells 2-4 (the water end), lava in cells 5-6 (the lava end).
            for (int x = 2; x <= 6; x++) {
                BlockPos rel = new BlockPos(x, 1, 1);
                PipeStore.Store cell = PipeStore.at(helper.getLevel(), helper.absolutePos(rel));
                if (cell == null) {
                    helper.fail("no pipe store at " + rel.toShortString());
                    return;
                }
                Fluid fluid = x <= 4 ? Fluids.WATER : Fluids.LAVA;
                cell.insert(new FluidStack(fluid, PipeStore.capacityMb()), PipeStore.capacityMb());
                cell.flush();
            }
            EngineTickHandler.markChanged(helper.getLevel(), helper.absolutePos(interfaceCell));
        });
        helper.runAfterDelay(100, () -> {
            if (FluidPropagator.getPipe(helper.getLevel(), helper.absolutePos(interfaceCell)) != null) {
                helper.fail("the water/lava interface mid-run survived — a split run must break there");
                return;
            }
            if (!helper.getBlockState(interfaceCell).is(Blocks.COBBLESTONE)) {
                helper.fail("split water|lava run collided but left "
                        + helper.getBlockState(interfaceCell) + " instead of cobblestone");
                return;
            }
            helper.succeed();
        });
    }
}
