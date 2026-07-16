package de.devin.pipesnphysics.engine.net;

import de.devin.pipesnphysics.PipesNPhysics;
import de.devin.pipesnphysics.client.PumpRangeClient;
import de.devin.pipesnphysics.compat.SableCompat;
import de.devin.pipesnphysics.engine.command.PipeGraphCommand;
import de.devin.pipesnphysics.engine.probe.PipeProbe;
import de.devin.pipesnphysics.engine.probe.PumpRangeProbe;
import de.devin.pipesnphysics.engine.render.GraphOverlay;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * One-stop registration for all engine packets.
 */
public final class EnginePackets {
    /** Goggle probes run a full network solve; cap how often one player may ask. */
    private static final int PROBE_THROTTLE_TICKS = 4;
    private static final double MAX_PROBE_DISTANCE_SQ = 64 * 64;

    private EnginePackets() {}

    /**
     * Range-gate a probe by the pipe's REAL world position, not its raw BlockPos. A pipe on a
     * Sable sub-level lives at far-away plot coordinates (~30M blocks out), so a raw distSqr
     * always exceeds the range and the goggle never updates — projecting through the sub-level
     * pose puts it back where the player actually sees it.
     */
    private static boolean isTooFar(ServerLevel level, BlockPos pos, ServerPlayer player) {
        return SableCompat.getWorldPos(level, pos).distanceToSqr(player.position()) > MAX_PROBE_DISTANCE_SQ;
    }

    /**
     * The guard every probe request passes: throttle per player (tracked under the given
     * persistent-data key, so each request kind throttles independently), range-gate by real
     * world position, and require the queried chunk to be loaded. The throttle stamp is
     * written first, so a too-far or unloaded request still consumes the window.
     */
    private static boolean allowRequest(ServerPlayer player, String throttleKey, BlockPos pos) {
        ServerLevel level = player.serverLevel();
        long now = level.getGameTime();
        if (now - player.getPersistentData().getLong(throttleKey) < PROBE_THROTTLE_TICKS) return false;
        player.getPersistentData().putLong(throttleKey, now);
        if (isTooFar(level, pos, player)) return false;
        return level.isLoaded(pos);
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(PipesNPhysics.ID).versioned("2");
        registrar.playToClient(
                GraphOverlayPayload.TYPE,
                GraphOverlayPayload.STREAM_CODEC,
                EnginePackets::onGraphOverlay);
        registrar.playToClient(
                PipeStatusPayload.TYPE,
                PipeStatusPayload.STREAM_CODEC,
                EnginePackets::onPipeStatus);
        registrar.playToServer(
                PipeStatusRequest.TYPE,
                PipeStatusRequest.STREAM_CODEC,
                EnginePackets::onPipeStatusRequest);
        registrar.playToServer(
                GraphOverlayRequest.TYPE,
                GraphOverlayRequest.STREAM_CODEC,
                EnginePackets::onGraphOverlayRequest);
        registrar.playToClient(
                PumpRangePayload.TYPE,
                PumpRangePayload.STREAM_CODEC,
                EnginePackets::onPumpRange);
        registrar.playToServer(
                PumpRangeRequest.TYPE,
                PumpRangeRequest.STREAM_CODEC,
                EnginePackets::onPumpRangeRequest);
    }

    private static void onPumpRange(PumpRangePayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> PumpRangeClient.receive(
                payload, ctx.player().level().getGameTime()));
    }

    private static void onPumpRangeRequest(PumpRangeRequest request, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (!allowRequest(player, "pipesnphysics_range_at", request.pos())) return;
            PacketDistributor.sendToPlayer(player,
                    PumpRangeProbe.probe(player.serverLevel(), request.pos()));
        });
    }

    private static void onGraphOverlay(GraphOverlayPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> GraphOverlay.receive(payload));
    }

    private static void onGraphOverlayRequest(GraphOverlayRequest request, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            BlockPos seed = BlockPos.of(request.seed());
            if (!allowRequest(player, "pipesnphysics_graph_at", seed)) return;
            GraphOverlayPayload payload = PipeGraphCommand.buildOverlay(player.serverLevel(), seed);
            if (payload != null) PacketDistributor.sendToPlayer(player, payload);
        });
    }

    private static void onPipeStatus(PipeStatusPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> PipeStatusClient.receive(payload, ctx.player().level().getGameTime()));
    }

    private static void onPipeStatusRequest(PipeStatusRequest request, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (!allowRequest(player, "pipesnphysics_probe_at", request.pos())) return;
            PacketDistributor.sendToPlayer(player,
                    PipeProbe.probe(player.serverLevel(), request.pos()));
        });
    }
}
