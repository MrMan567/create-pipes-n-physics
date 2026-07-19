package de.devin.pipesnphysics.engine.store;

import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import de.devin.pipesnphysics.PipesNPhysics;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Access to a pipe cell's stored fluid ({@link PipeFluidCell}) plus the flow-data encoding shared
 * with the client renderer.
 *
 * All mutation goes through a {@link Store}, which clamps to the per-cell capacity, forbids mixing
 * (a cell holds ONE fluid), and batches the block-entity sync: {@link Store#flush} sends one update
 * per changed cell per tick. Capacity is the {@code PIPE_VOLUME_PER_CELL} server config; {@code 0}
 * turns every cell into a zero-volume wire, degenerating the transfer layer to the old instant
 * endpoint-to-endpoint behaviour.
 */
public final class PipeStore {
    /**
     * Rate jitter the flow stamp ignores (in 1/{@link #FLOW_RATE_SCALE} cells/tick): a steady flow's
     * rate wobbles with the solved mB/t, and without a deadband every wobble would re-sync the cell.
     */
    private static final int FLOW_RATE_EPS = 4;
    private static final int FLOW_RATE_SCALE = 256;

    private PipeStore() {}

    /** Per-cell fluid capacity in mB. 0 = pipes store nothing (instant transfers). */
    public static int capacityMb() {
        return PipesNPhysicsConfig.PIPE_VOLUME_PER_CELL.get();
    }

    /**
     * The store of the pipe cell at pos, or null when the block entity there does not hold pipe
     * fluid. Returns a fresh handle whose dirty flag batches only its own mutations — reuse one
     * handle per cell per tick ({@code FlowNetwork.cellAt} does this caching for the executor).
     */
    public static Store at(Level level, BlockPos pos) {
        FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pos);
        if (!(pipe instanceof PipeFluidCell cell)) return null;
        return new Store(pipe, cell);
    }

    // Flow-stamp layout (this class is the ONLY place that knows the bits): bits 0-2 hold
    // direction+1 (0 reserved for "at rest"), bits 3-10 the rate in 1/FLOW_RATE_SCALE
    // cells/tick, and the whole value is offset +1 so a zeroed field means "no stamp".

    /**
     * Encode a cell's cosmetic flow state: {@code dir} the downstream direction (null at rest),
     * {@code cellsPerTick} the advance rate the client scrolls/extrapolates at.
     */
    public static int encodeFlow(Direction dir, double cellsPerTick) {
        int rate = Math.clamp(Math.round(cellsPerTick * FLOW_RATE_SCALE), 0, 255);
        return (rate << 3 | ((dir == null ? -1 : dir.get3DDataValue()) + 1)) + 1;
    }

    /** The downstream direction of a flow stamp, or null at rest / when unset. */
    public static Direction flowDirection(int data) {
        int dir = data == 0 ? -1 : ((data - 1) & 7) - 1;
        return dir < 0 ? null : Direction.from3DDataValue(dir);
    }

    /** The advance rate of a flow stamp in cells/tick. */
    public static float flowRate(int data) {
        return rawRate(data) / (float) FLOW_RATE_SCALE;
    }

    private static int rawRate(int data) {
        return data == 0 ? 0 : (data - 1) >>> 3;
    }

    /** Whether two flow stamps differ only by rate jitter (same direction, rate within the deadband). */
    private static boolean onlyRateJitter(int stored, int fresh) {
        if (stored == 0 || fresh == 0) return stored == fresh;
        if (((stored - 1) & 7) != ((fresh - 1) & 7)) return false;
        return Math.abs(rawRate(stored) - rawRate(fresh)) <= FLOW_RATE_EPS;
    }

    /** One pipe cell's fluid store; mutations batch into a single {@link #flush} sync. */
    public static final class Store {
        private final FluidTransportBehaviour pipe;
        private final PipeFluidCell cell;
        private boolean dirty;

        private Store(FluidTransportBehaviour pipe, PipeFluidCell cell) {
            this.pipe = pipe;
            this.cell = cell;
        }

        public FluidStack fluid() {
            return cell.pipesnphysics$content();
        }

        public int amount() {
            return cell.pipesnphysics$content().getAmount();
        }

        /** Free capacity for {@code fluid}: the rest of the cell if empty or same fluid, else 0. */
        public int room(FluidStack fluid) {
            FluidStack current = cell.pipesnphysics$content();
            if (current.isEmpty()) return capacityMb();
            if (!FluidStack.isSameFluidSameComponents(current, fluid)) return 0;
            return Math.max(0, capacityMb() - current.getAmount());
        }

        /** Add up to {@code amount} of {@code fluid}, returning what actually fit (never mixes). */
        public int insert(FluidStack fluid, int amount) {
            int fit = Math.min(amount, room(fluid));
            if (fit <= 0) return 0;
            FluidStack current = cell.pipesnphysics$content();
            cell.pipesnphysics$setContent(current.isEmpty()
                    ? fluid.copyWithAmount(fit)
                    : current.copyWithAmount(current.getAmount() + fit));
            dirty = true;
            return fit;
        }

        /**
         * The canonical cell-to-cell move: shift up to {@code amount} of this cell's fluid into
         * {@code to} (same fluid or empty destination), returning the mB moved. This is the ONE
         * place the extract/insert pair is clamped and executed, so a conservation bug cannot
         * hide in a caller that forgot the room pre-clamp.
         */
        public int moveInto(Store to, int amount) {
            if (amount() <= 0) return 0;
            int move = Math.min(amount, Math.min(amount(), to.room(fluid())));
            if (move <= 0) return 0;
            int inserted = to.insert(extract(move), move);
            if (inserted != move) {
                PipesNPhysics.LOGGER.warn("Pipe cell move mismatch: extracted {} but inserted {}",
                        move, inserted);
            }
            return move;
        }

        /** Remove up to {@code amount} of the stored fluid, returning what came out. */
        public FluidStack extract(int amount) {
            FluidStack current = cell.pipesnphysics$content();
            int take = Math.min(amount, current.getAmount());
            if (take <= 0) return FluidStack.EMPTY;
            FluidStack out = current.copyWithAmount(take);
            cell.pipesnphysics$setContent(take == current.getAmount()
                    ? FluidStack.EMPTY
                    : current.copyWithAmount(current.getAmount() - take));
            dirty = true;
            return out;
        }

        /** Stamp the cosmetic flow direction (null = rest) + rate, deadbanded so steady flow stays quiet. */
        public void setFlow(Direction dir, double cellsPerTick) {
            int fresh = dir == null && cellsPerTick <= 0 ? 0 : encodeFlow(dir, cellsPerTick);
            int stored = cell.pipesnphysics$flowData();
            if (stored == fresh || onlyRateJitter(stored, fresh)) return;
            cell.pipesnphysics$setFlowData(fresh);
            dirty = true;
        }

        /** The packed cosmetic flow stamp (0 = at rest). */
        public int flowData() {
            return cell.pipesnphysics$flowData();
        }

        /** Clear the flow stamp (cell at rest). */
        public void clearFlow() {
            if (cell.pipesnphysics$flowData() == 0) return;
            cell.pipesnphysics$setFlowData(0);
            dirty = true;
        }

        /** Send one sync/save for all changes since the last flush. */
        public void flush() {
            if (!dirty) return;
            dirty = false;
            pipe.blockEntity.notifyUpdate();
        }
    }
}
