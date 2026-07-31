package de.devin.pipesnphysics.client;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.content.equipment.goggles.GogglesItem;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import de.devin.pipesnphysics.PipesNPhysics;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.engine.net.PumpRangePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.List;

/**
 * Colors the pipes a pump can reach, while a goggle-wearing player looks at it (and for a
 * configurable grace window afterwards). The paths come from the server
 * ({@link PumpRangeClient}); a cell is painted when its reach MARGIN is still positive — the
 * push ceiling above the pump, the drawable floor below it.
 *
 * Only the reachable pipe is colored; everything past the limit is left alone, so the painted
 * EXTENT is the answer to "how far does this pump go" (owner's call, 2026-07-31): where the
 * green stops is the limit.
 *
 * The paint is each pipe's OWN baked model re-drawn a hair larger and tinted, rather than a
 * shell built around it — so elbows, connection stubs and encasing are all followed exactly,
 * which no hand-built geometry can manage. Every earlier attempt to encode reach as a GRADIENT
 * along the run failed the same way: it competed with the in-pipe fluid for attention and read
 * as a promise about how well the pump was working, when the only question asked at a pump is
 * how far it goes.
 */
@EventBusSubscriber(modid = PipesNPhysics.ID, value = Dist.CLIENT)
public final class PumpRangeRenderer {
    /** Grown about the block centre so the tint sits just off the pipe's faces, not z-fighting them. */
    private static final float SWELL = 1.02f;
    /** Strong enough to read at a glance, light enough that the pipe still shows through it. */
    private static final float TINT_ALPHA = 0.55f;

    /** Within reach — the only thing painted. */
    private static final Rgb REACH_COLOR = new Rgb(60, 255, 90);

    private static final MultiBufferSource.BufferSource OWN_BUFFER =
            MultiBufferSource.immediate(new ByteBufferBuilder(2048));

    private PumpRangeRenderer() {}

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        // AFTER_PARTICLES (not AFTER_TRANSLUCENT_BLOCKS) so the tint draws over the in-pipe
        // fluid's own translucency instead of fighting it for sort order.
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!PipesNPhysicsConfig.SHOW_PUMP_REACH_OVERLAY.get()) return;
        if (!GogglesItem.isWearingGoggles(mc.player)) return;

        long now = mc.level.getGameTime();
        if (mc.hitResult instanceof BlockHitResult blockHit
                && mc.hitResult.getType() == HitResult.Type.BLOCK
                && mc.level.getBlockState(blockHit.getBlockPos()).getBlock() instanceof PumpBlock) {
            PumpRangeClient.looking(blockHit.getBlockPos(), now);
        }

        boolean preserve = PipesNPhysicsConfig.PRESERVE_PUMP_RANGE.get();
        int preserveTicks = PipesNPhysicsConfig.PUMP_RANGE_PRESERVE_SECONDS.get() * 20;
        PumpRangePayload payload = PumpRangeClient.active(now, preserve, preserveTicks);
        if (payload == null || payload.paths().isEmpty()) return;

        float fade = PumpRangeClient.preserveFraction(now, preserveTicks);
        paintReach(event.getPoseStack(), mc, payload.paths(), Math.max(0.15f, fade));
    }

    private static void paintReach(PoseStack poseStack, Minecraft mc,
                                   List<PumpRangePayload.RangePath> paths, float fade) {
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        float alpha = TINT_ALPHA * fade;

        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        VertexConsumer buf = OWN_BUFFER.getBuffer(PnpRenderTypes.REACH_TINT);
        for (PumpRangePayload.RangePath path : paths) {
            for (PumpRangePayload.RangeCell cell : path.cells()) {
                // The pump itself and a tank or open end at the far end of a run are graph nodes,
                // not pipes — painting those would color a block that is not part of the run.
                if (!cell.pipe() || cell.margin() < 0) continue;
                tint(poseStack, mc, buf, BlockPos.of(cell.pos()), alpha);
            }
        }
        OWN_BUFFER.endBatch();
        poseStack.popPose();
    }

    /** Re-emits one block's baked model, swollen about its centre and tinted. */
    private static void tint(PoseStack poseStack, Minecraft mc, VertexConsumer buf,
                             BlockPos pos, float alpha) {
        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir()) return;
        BakedModel model = mc.getBlockRenderer().getBlockModel(state);

        poseStack.pushPose();
        poseStack.translate(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        poseStack.scale(SWELL, SWELL, SWELL);
        poseStack.translate(-0.5, -0.5, -0.5);

        // Seeded exactly as the block itself renders, so a multi-variant model tints the variant
        // actually on screen rather than a different roll of the same model.
        RandomSource random = RandomSource.create();
        for (Direction face : Direction.values()) {
            random.setSeed(state.getSeed(pos));
            emit(poseStack, buf, model.getQuads(state, face, random), alpha);
        }
        random.setSeed(state.getSeed(pos));
        emit(poseStack, buf, model.getQuads(state, null, random), alpha);
        poseStack.popPose();
    }

    private static void emit(PoseStack poseStack, VertexConsumer buf,
                             List<BakedQuad> quads, float alpha) {
        for (BakedQuad quad : quads) {
            buf.putBulkData(poseStack.last(), quad,
                    REACH_COLOR.r() / 255f, REACH_COLOR.g() / 255f, REACH_COLOR.b() / 255f, alpha,
                    ArrowRender.FULL_BRIGHTNESS, OverlayTexture.NO_OVERLAY);
        }
    }
}
