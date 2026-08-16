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
 * The paint spans what the pump's head is PAYING FOR: everything above the supply surface, up to
 * the limit. So the painted EXTENT is the answer to "how far does this pump go" (owner's call,
 * 2026-07-31): where the green stops is the limit. That limit is an ELEVATION and almost never
 * lands on a block boundary, so where the run CLIMBS through it the paint is CUT part-way through
 * a pipe rather than rounded to whole blocks. Along a LEVEL stretch there is nothing to cut — one
 * elevation, so the limit clears every cell of it or none — and each cell is painted whole or not
 * at all ({@link #climbsThrough}).
 *
 * The supply surface underneath keeps PUSH runs honest — horizontal distance costs no head at
 * all, so against the ceiling ALONE every pipe at or below the waterline was reachable and a
 * whole ground-level network lit up green along its entire length, saying nothing — but it is a
 * WHOLE-CELL test, never a second cut: a pipe standing entirely below the surface is gravity's
 * work and stays bare, while one the surface merely passes THROUGH is painted whole. Cutting
 * there answered no question asked at a pump, and left every run sitting at its supply's own
 * level painted along its top half, cell after cell — which reads as a broken overlay rather
 * than an answer.
 *
 * PULLING is bounded by the drawable floor ALONE. The supply surface has no business on that
 * side: how deep a pump can draw is a question about pipe BELOW the surface, so testing against
 * it hides the very answer — and where the solve has no supply to anchor at it self-anchors the
 * field at the node's own centre (§6), a fiction that sits at the pump and capped every suction
 * run at one block however far the pump could really reach.
 *
 * The paint is each pipe's OWN baked model re-drawn a hair larger and tinted, rather than a
 * shell built around it — so elbows, connection stubs and encasing are all followed exactly,
 * which no hand-built geometry can manage. BOTH sides ramp green→amber→red toward their limit
 * ({@link Ramp}), shaded per vertex so the colour runs smoothly up the run rather than stepping
 * once per pipe. That ramp measures the reach LEFT — still the "how far does this pump go"
 * question, read at each elevation instead of only where the paint stops. It is not the encoding
 * reverted on 2026-07-31: those ramped by how hard the pump was WORKING, competed with the
 * in-pipe fluid, and read as a promise about throughput.
 */
@EventBusSubscriber(modid = PipesNPhysics.ID, value = Dist.CLIENT)
public final class PumpRangeRenderer {
    /** Strong enough to read at a glance, light enough that the pipe still shows through it. */
    private static final float TINT_ALPHA = 0.55f;

    /** At the pump, with the whole reach still ahead. */
    private static final Rgb REACH_COLOR = new Rgb(60, 255, 90);
    /** Halfway through it: warm enough to read as "using it up", not as alarm. */
    private static final Rgb HALFWAY_COLOR = new Rgb(255, 200, 50);
    /** At the limit — the ceiling it can push to, or the floor it can draw from. */
    private static final Rgb LIMIT_COLOR = new Rgb(255, 70, 55);

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
            // Both quantities arrive as ELEVATIONS measured from the cell's own centre, so they
            // read as block-local heights around 0.5.
            boolean push = !path.pull();
            List<PumpRangePayload.RangeCell> cells = path.cells();
            // The margin at the pump — the path's first entry — is this run's WHOLE reach, and so
            // the span the colour ramp normalizes over (see Ramp).
            float reach = cells.get(0).margin();
            for (int i = 0; i < cells.size(); i++) {
                PumpRangePayload.RangeCell cell = cells.get(i);
                // The pump itself and a tank or open end at the far end of a run are graph nodes,
                // not pipes — painting those would color a block that is not part of the run.
                if (!cell.pipe()) continue;

                // PUSHING, a cell standing wholly below the supply surface is gravity's work and
                // stays bare. PULLING, that same surface would hide the answer — how deep the
                // pump can draw is a question about pipe BELOW it — so the floor alone bounds
                // that side. (The surface only ever decides WHETHER a cell is painted either
                // way, never where its paint starts; see the class comment.)
                if (push && 0.5f - cell.aboveSupply() >= 1) continue;

                // The limit — the ceiling overhead when pushing, the drawable floor below when
                // pulling — cuts the pipe only where the run CLIMBS through it. On a level
                // stretch it is the cell's own margin that answers the question, whole cell at a
                // time; see climbsThrough.
                float limit = push ? 0.5f + cell.margin() : 0.5f - cell.margin();
                float low = Float.NEGATIVE_INFINITY;
                float high = Float.POSITIVE_INFINITY;
                if (climbsThrough(cells, i)) {
                    low = push ? Float.NEGATIVE_INFINITY : limit;
                    high = push ? limit : Float.POSITIVE_INFINITY;
                    if (low >= 1 || high <= 0) continue; // the band misses this cell entirely
                } else if (cell.margin() < 0) {
                    continue; // level stretch, past the limit: out of reach, all of it
                }
                Ramp ramp = new Ramp(limit, reach, push);
                tint(poseStack, mc, buf, BlockPos.of(cell.pos()), low, high, ramp, alpha);
            }
        }
        OWN_BUFFER.endBatch();
        poseStack.popPose();
    }

    /**
     * The colour ramp over one cell: green where the pump stands, warming through amber to red at
     * the limit — how much of the reach is spent by the time fluid is up (or down) here. Both
     * bounds are elevations, so the colour is a function of ELEVATION and is evaluated PER VERTEX:
     * the ramp runs continuously up the run instead of stepping once per pipe, and a cut cell
     * fades into its cut edge rather than ending on a flat block of colour.
     *
     * {@code limit} is the limit's height in the cell's own block-local frame and {@code reach}
     * the blocks of margin at the pump — the path's first entry, which is THIS run's whole reach.
     * Normalizing over that, never a fixed band, is the trap the first ramp fell into (2026-07-31):
     * every cell more than the band's width clear of the limit clamped to full saturation, the
     * overlay read as one flat colour, and it silently swallowed a correct fix to the pull-side
     * quantity ("it looks exactly the same").
     *
     * A consequence worth expecting: a LEVEL stretch is one uniform colour, because every cell of
     * it really is equally far from the limit. The ramp develops as the run climbs or descends,
     * which is exactly where the reach is being spent.
     */
    private record Ramp(float limit, float reach, boolean push) {
        Rgb colorAt(double blockLocalY) {
            if (reach <= 1e-3) return LIMIT_COLOR;
            double margin = push ? limit - blockLocalY : blockLocalY - limit;
            double spent = 1 - Math.clamp(margin / reach, 0, 1);
            return spent < 0.5
                    ? mix(REACH_COLOR, HALFWAY_COLOR, spent * 2)
                    : mix(HALFWAY_COLOR, LIMIT_COLOR, (spent - 0.5) * 2);
        }
    }

    /** Two colours blended {@code t} of the way from one to the other, per channel. */
    private static Rgb mix(Rgb from, Rgb to, double t) {
        return new Rgb((int) Mth.lerp(t, from.r(), to.r()),
                (int) Mth.lerp(t, from.g(), to.g()),
                (int) Mth.lerp(t, from.b(), to.b()));
    }

    /**
     * Whether the run CLIMBS through this cell — a neighbour along the path stands at a different
     * elevation. Only there does cutting the cell at the limit answer anything: the run passes
     * through the limit INSIDE that block, and the green stops exactly where it does, which is
     * the whole reason the paint is cut rather than rounded to whole blocks.
     *
     * Along a LEVEL stretch every cell shares one elevation, so the limit either clears all of
     * them or none. A plane through such a cell is an artifact of the pipe being 8/16 thick, not
     * of the run's path, and cutting there paints the identical partial slice on cell after cell
     * down the whole stretch — which reads as a broken overlay rather than as an answer (reported
     * twice: the supply surface slicing at the midline, then the drawable floor at the pipe lip).
     */
    private static boolean climbsThrough(List<PumpRangePayload.RangeCell> cells, int index) {
        int y = BlockPos.of(cells.get(index).pos()).getY();
        return (index > 0 && BlockPos.of(cells.get(index - 1).pos()).getY() != y)
                || (index + 1 < cells.size() && BlockPos.of(cells.get(index + 1).pos()).getY() != y);
    }

    /** Re-emits one block's baked model, tinted and cut down to the band between two planes. */
    private static void tint(PoseStack poseStack, Minecraft mc, VertexConsumer buf,
                             BlockPos pos, float low, float high, Ramp ramp, float alpha) {
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
            emit(poseStack, buf, model.getQuads(state, face, random, data, null),
                    low, high, ramp, alpha);
        }
        random.setSeed(state.getSeed(pos));
        emit(poseStack, buf, model.getQuads(state, null, random, data, null),
                low, high, ramp, alpha);
        poseStack.popPose();
    }

    /** The block entity's own model data — the raw input a model enriches into its real data. */
    private static ModelData blockEntityData(Minecraft mc, BlockPos pos) {
        ModelDataManager manager = mc.level.getModelDataManager();
        if (manager == null) return ModelData.EMPTY;
        ModelData data = manager.getAt(pos);
        return data == null ? ModelData.EMPTY : data;
    }

    /**
     * Emits the quads of one cell, each cut to the band and shaded PER VERTEX off the ramp, so the
     * colour runs smoothly up the run instead of stepping once per pipe. Written vertex by vertex
     * rather than through {@code putBulkData}, which takes ONE colour for the whole quad; the
     * geometry, UVs and face normal are the model's own, only the colour is ours.
     */
    private static void emit(PoseStack poseStack, VertexConsumer buf, List<BakedQuad> quads,
                             float low, float high, Ramp ramp, float alpha) {
        for (BakedQuad quad : quads) {
            BakedQuad shown = cutAt(quad, low, false);
            if (shown != null) shown = cutAt(shown, high, true);
            if (shown == null) continue;
            int[] vertices = shown.getVertices();
            Direction facing = shown.getDirection();
            for (int i = 0; i < 4; i++) {
                Vec3 at = BakedQuadHelper.getXYZ(vertices, i);
                Rgb color = ramp.colorAt(at.y);
                buf.addVertex(poseStack.last(), (float) at.x, (float) at.y, (float) at.z)
                        .setColor(color.r() / 255f, color.g() / 255f, color.b() / 255f, alpha)
                        .setUv(BakedQuadHelper.getU(vertices, i), BakedQuadHelper.getV(vertices, i))
                        .setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(ArrowRender.FULL_BRIGHTNESS)
                        .setNormal(poseStack.last(),
                                facing.getStepX(), facing.getStepY(), facing.getStepZ());
            }
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
