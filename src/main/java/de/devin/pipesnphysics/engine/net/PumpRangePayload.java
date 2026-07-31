package de.devin.pipesnphysics.engine.net;

import de.devin.pipesnphysics.PipesNPhysics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

/**
 * Server → client answer to a {@link PumpRangeRequest}: how far the queried pump can
 * reach. Each path is a run of cells starting at the pump, every cell carrying the
 * reach MARGIN in blocks at its elevation, and whether the path is on the pump's pull
 * (suction) side.
 *
 */
public record PumpRangePayload(BlockPos pumpPos, List<RangePath> paths)
        implements CustomPacketPayload {
    public static final Type<PumpRangePayload> TYPE =
            new Type<>(PipesNPhysics.asResource("pump_range"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PumpRangePayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, PumpRangePayload::pumpPos,
                    RangePath.CODEC.apply(ByteBufCodecs.list()), PumpRangePayload::paths,
                    PumpRangePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    /**
     * One run of cells out from the pump, ordered away from it. {@code pull} marks a path on
     * the pump's pull (suction) side.
     */
    public record RangePath(List<RangeCell> cells, boolean pull) {
        public static final StreamCodec<RegistryFriendlyByteBuf, RangePath> CODEC =
                StreamCodec.composite(
                        RangeCell.CODEC.apply(ByteBufCodecs.list()), RangePath::cells,
                        ByteBufCodecs.BOOL, RangePath::pull,
                        RangePath::new);
    }

    /**
     * One visited cell: its packed {@link BlockPos#asLong} position and the reach MARGIN in
     * blocks there — how much lift is left under the pump's push ceiling, or how much suction
     * is left above its pull floor. Negative means the pump's head does not reach this
     * elevation. A continuous quantity, not a reachable/starved flip, so the client can paint
     * how much margin is being spent rather than only where it runs out.
     *
     * {@code pipe} marks a cell the reach sleeve may paint: the pump itself and a tank or open
     * end at the far end of a run are graph nodes, not pipes, and would put a colored bar
     * inside the block.
     */
    public record RangeCell(long pos, float margin, boolean pipe) {
        public static final StreamCodec<RegistryFriendlyByteBuf, RangeCell> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_LONG, RangeCell::pos,
                        ByteBufCodecs.FLOAT, RangeCell::margin,
                        ByteBufCodecs.BOOL, RangeCell::pipe,
                        RangeCell::new);
    }
}
