package de.devin.pipesnphysics.client;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.content.equipment.goggles.GogglesItem;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.foundation.model.BakedQuadHelper;
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
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelDataManager;

import java.util.Arrays;
import java.util.List;

/**
 * Colors the pipes a pump can reach, while a goggle-wearing player looks at it (and for a
 * configurable grace window afterwards). The paths come from the server
 * ({@link PumpRangeClient}); a cell is painted when its reach MARGIN is still positive — the
 * push ceiling above the pump, the drawable floor below it.
 *
 * The paint spans what the pump's head is PAYING FOR: from the supply surface up to the limit.
 * So the painted EXTENT is the answer to "how far does this pump go" (owner's call, 2026-07-31):
 * where the green stops is the limit. Both bounds are ELEVATIONS and almost never land on a
 * block boundary, so the paint is CUT at them part-way through a pipe rather than rounded to
 * whole blocks.
 *
 * The lower bound is what keeps flat runs honest. Horizontal distance costs no head at all, so
 * against the limit ALONE every pipe at or below the waterline was reachable and a whole ground-
 * level network lit up green along its entire length, saying nothing. Below the supply surface
 * gravity does the moving and none of the pump's reach is spent, so that pipe now stays bare.
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
            // Both bounds are ELEVATIONS, so they are horizontal planes through the pipe, placed
            // relative to the cell's own centre (block-local 0.5).
            boolean push = !path.pull();
            for (PumpRangePayload.RangeCell cell : path.cells()) {
                // The pump itself and a tank or open end at the far end of a run are graph nodes,
                // not pipes — painting those would color a block that is not part of the run.
                if (!cell.pipe()) continue;

                // The paint spans what the pump's head is PAYING FOR: from the supply surface up
                // to the limit. Pushing, the limit is the ceiling overhead; pulling, it is the
                // floor below, which then joins the supply surface as a second lower bound.
                float supply = 0.5f - cell.aboveSupply();
                float limit = push ? 0.5f + cell.margin() : 0.5f - cell.margin();
                float low = push ? supply : Math.max(limit, supply);
                float high = push ? limit : Float.POSITIVE_INFINITY;
                if (low >= 1 || high <= 0) continue; // the band misses this cell entirely
                tint(poseStack, mc, buf, BlockPos.of(cell.pos()), low, high, alpha);
            }
        }
        OWN_BUFFER.endBatch();
        poseStack.popPose();
    }

    /** Re-emits one block's baked model, tinted and cut down to the band between two planes. */
    private static void tint(PoseStack poseStack, Minecraft mc, VertexConsumer buf,
                             BlockPos pos, float low, float high, float alpha) {
        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir()) return;
        BakedModel model = mc.getBlockRenderer().getBlockModel(state);
        // A Create pipe's rim ATTACHMENTS, bracket and casing are not in its blockstate model at
        // all: PipeAttachmentModel builds them in getModelData and appends them in getQuads. So
        // the model has to be asked to ENRICH the block entity's data the way the chunk renderer
        // does — handing it the raw ModelDataManager entry leaves its PIPE_PROPERTY absent and
        // renders the bare core, tinting every pipe's middle and leaving its joints copper.
        // A null render type likewise takes ALL of the model's layers, not just one.
        ModelData data = model.getModelData(mc.level, pos, state, blockEntityData(mc, pos));

        poseStack.pushPose();
        poseStack.translate(pos.getX(), pos.getY(), pos.getZ());

        // Seeded exactly as the block itself renders, so a multi-variant model tints the variant
        // actually on screen rather than a different roll of the same model.
        RandomSource random = RandomSource.create();
        for (Direction face : Direction.values()) {
            random.setSeed(state.getSeed(pos));
            emit(poseStack, buf, model.getQuads(state, face, random, data, null), low, high, alpha);
        }
        random.setSeed(state.getSeed(pos));
        emit(poseStack, buf, model.getQuads(state, null, random, data, null), low, high, alpha);
        poseStack.popPose();
    }

    /** The block entity's own model data — the raw input a model enriches into its real data. */
    private static ModelData blockEntityData(Minecraft mc, BlockPos pos) {
        ModelDataManager manager = mc.level.getModelDataManager();
        if (manager == null) return ModelData.EMPTY;
        ModelData data = manager.getAt(pos);
        return data == null ? ModelData.EMPTY : data;
    }

    private static void emit(PoseStack poseStack, VertexConsumer buf, List<BakedQuad> quads,
                             float low, float high, float alpha) {
        for (BakedQuad quad : quads) {
            BakedQuad shown = cutAt(quad, low, false);
            if (shown != null) shown = cutAt(shown, high, true);
            if (shown == null) continue;
            buf.putBulkData(poseStack.last(), shown,
                    REACH_COLOR.r() / 255f, REACH_COLOR.g() / 255f, REACH_COLOR.b() / 255f, alpha,
                    ArrowRender.FULL_BRIGHTNESS, OverlayTexture.NO_OVERLAY);
        }
    }

    /**
     * The quad cut down to the painted side of a horizontal plane at block-local {@code plane}:
     * the quad itself when it lies wholly inside, null when wholly outside, and a cut copy when
     * it straddles — each vertex on the wrong side slides along its OWN edge to the plane and
     * carries its texture coordinate with it, so the cut face keeps the pipe's texture instead of
     * stretching it.
     *
     * This is what lets the paint stop part-way THROUGH a pipe: reach is an elevation, and it
     * almost never lands on a block boundary, so whole-block tinting quantized the answer to the
     * nearest metre and hid a limit that fell just short of the next pipe entirely.
     */
    private static BakedQuad cutAt(BakedQuad quad, float plane, boolean keepBelow) {
        int[] source = quad.getVertices();
        boolean[] outside = new boolean[4];
        int cut = 0;
        for (int i = 0; i < 4; i++) {
            double y = BakedQuadHelper.getXYZ(source, i).y;
            outside[i] = keepBelow ? y > plane : y < plane;
            if (outside[i]) cut++;
        }
        if (cut == 0) return quad;
        if (cut == 4) return null;

        int[] data = Arrays.copyOf(source, source.length);
        for (int i = 0; i < 4; i++) {
            if (!outside[i]) continue;
            int before = (i + 3) % 4;
            int after = (i + 1) % 4;
            int anchor = !outside[before] ? before : (!outside[after] ? after : -1);
            Vec3 from = BakedQuadHelper.getXYZ(source, i);
            if (anchor < 0) {
                // No neighbour left to slide along (never happens on the cuboid faces a pipe is
                // built from); pin the height so the quad at least stops at the plane.
                BakedQuadHelper.setXYZ(data, i, new Vec3(from.x, plane, from.z));
                continue;
            }
            Vec3 to = BakedQuadHelper.getXYZ(source, anchor);
            double rise = to.y - from.y;
            if (Math.abs(rise) < 1e-6) { // coincident after an earlier cut; nothing to slide along
                BakedQuadHelper.setXYZ(data, i, new Vec3(from.x, plane, from.z));
                continue;
            }
            double t = (plane - from.y) / rise;
            BakedQuadHelper.setXYZ(data, i, from.add(to.subtract(from).scale(t)));
            BakedQuadHelper.setU(data, i, (float) Mth.lerp(t,
                    BakedQuadHelper.getU(source, i), BakedQuadHelper.getU(source, anchor)));
            BakedQuadHelper.setV(data, i, (float) Mth.lerp(t,
                    BakedQuadHelper.getV(source, i), BakedQuadHelper.getV(source, anchor)));
        }
        return BakedQuadHelper.cloneWithCustomGeometry(quad, data);
    }
}
