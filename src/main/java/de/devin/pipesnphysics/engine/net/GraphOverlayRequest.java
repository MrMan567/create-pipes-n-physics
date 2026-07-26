package de.devin.pipesnphysics.engine.net;

import de.devin.pipesnphysics.PipesNPhysics;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client → server: "re-solve the /pipegraph network seeded here and send a fresh overlay."
 * Sent (throttled) by the client while a /pipegraph overlay is alive, so the in-world graph
 * tracks a live — and bursty — flow instead of freezing on the single tick the command ran.
 * {@code seed} is the packed BlockPos the overlay was built from.
 */
public record GraphOverlayRequest(long seed) implements CustomPacketPayload {
    public static final Type<GraphOverlayRequest> TYPE =
            new Type<>(PipesNPhysics.asResource("graph_overlay_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GraphOverlayRequest> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeLong(payload.seed),
                    buf -> new GraphOverlayRequest(buf.readLong()));

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
