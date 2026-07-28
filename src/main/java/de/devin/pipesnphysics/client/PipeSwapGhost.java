package de.devin.pipesnphysics.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import dev.engine_room.flywheel.lib.model.baked.EmptyVirtualBlockGetter;
import de.devin.pipesnphysics.PipesNPhysics;
import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.handler.PipeSwapHandler;
import net.createmod.catnip.client.render.model.BakedModelBufferer;
import net.createmod.catnip.ghostblock.GhostBlocks;
import net.createmod.catnip.lang.LangBuilder;
import net.createmod.catnip.impl.client.render.ColoringVertexConsumer;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.DefaultSuperRenderTypeBuffer;
import net.createmod.catnip.render.SuperRenderTypeBuffer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Draws Create's translucent placement ghost over a pipe element the player aims at while sneaking with
 * another pipe element in hand — a live preview of the shift-swap ({@link PipeSwapHandler}) — and shows an
 * action-bar hint naming the key that performs it. Ponder's own ghost system can't do the ghost: it
 * suppresses every ghost while the player sneaks (the very key the swap needs) and zeroes its alpha, so we
 * render one ourselves, gated to the exact swap conditions so the preview never promises a placement that
 * wouldn't happen.
 */
@EventBusSubscriber(modid = PipesNPhysics.ID, value = Dist.CLIENT)
public final class PipeSwapGhost {
    private static SuperRenderTypeBuffer ghostBuffer;

    private PipeSwapGhost() {}

    /**
     * The ghost's buffer, built on the first frame that draws one — NEVER in a static initializer. FML
     * force-initializes every {@link EventBusSubscriber} class during mod CONSTRUCTION, and catnip's buffer
     * class-loads {@code Sheets}, which class-loads {@code RenderType}. NeoForge rejects that outright
     * ("Sheets loaded too early, modded registry-based materials may not work correctly") and it freezes the
     * chunk render-type ids before mods that add their own have registered them — which crashed their client
     * setup rather than ours. Render thread only, so no locking.
     */
    private static SuperRenderTypeBuffer ghostBuffer() {
        if (ghostBuffer == null) ghostBuffer = new DefaultSuperRenderTypeBuffer();
        return ghostBuffer;
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        // AFTER_PARTICLES matches ponder's own ghost stage, so the ghost sorts against the world like theirs.
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;

        Minecraft mc = Minecraft.getInstance();
        if (!previewActive(mc)) return;
        Swap swap = findSwap(mc);
        if (swap != null) renderGhost(event.getPoseStack(), mc, swap.pos(), swap.state());
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        // The action-bar hint rides the tick (20 Hz) rather than the render frame. Same gate as the ghost,
        // so it names the swap key exactly when — and only when — a right-click would replace the block.
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || !previewActive(mc)) return;
        if (findSwap(mc) == null) return;

        Component useKey = mc.options.keyUse.getTranslatedKeyMessage();
        Component hint = new LangBuilder(PipesNPhysics.ID).translate("gui.swap.hint", useKey)
                .style(ChatFormatting.YELLOW).component();
        mc.gui.setOverlayMessage(hint, false);
    }

    /** The shared gate: a swap is only previewed while sneaking, with the feature enabled. */
    private static boolean previewActive(Minecraft mc) {
        return mc.player != null && mc.level != null
                && mc.player.isShiftKeyDown()
                && PipesNPhysicsConfig.ENABLE_PIPE_SWAP.get();
    }

    /** The block a right-click would place at the aimed position, or null when nothing there is swappable. */
    private static Swap findSwap(Minecraft mc) {
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return null;

        BlockPos pos = hit.getBlockPos();
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack held = mc.player.getItemInHand(hand);
            UseOnContext ctx = new UseOnContext(mc.player, hand, hit);
            BlockState placed = PipeSwapHandler.swapResultState(mc.level, pos, held, mc.player, ctx);
            if (placed != null) return new Swap(pos, placed);
        }
        return null;
    }

    private record Swap(BlockPos pos, BlockState state) {}

    private static void renderGhost(PoseStack ms, Minecraft mc, BlockPos pos, BlockState state) {
        BakedModel model = mc.getBlockRenderer().getBlockModel(state);
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        float alpha = (float) GhostBlocks.getBreathingAlpha() * 0.75f;
        SuperRenderTypeBuffer buffer = ghostBuffer();
        VertexConsumer raw = buffer.getEarlyBuffer(RenderType.translucent());
        VertexConsumer vb = new ColoringVertexConsumer(raw, 1, 1, 1, alpha);

        ms.pushPose();
        ms.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
        // Shrink slightly around the block centre so the ghost sits inside the outline, like Create's own.
        ms.translate(0.5, 0.5, 0.5);
        ms.scale(0.85f, 0.85f, 0.85f);
        ms.translate(-0.5, -0.5, -0.5);
        // An EMPTY world getter (no block entity, air neighbours) — NOT mc.level. Create's pipe/pump models
        // gather model data from the block at pos, and reading the real pipe BE with a mismatched ghost state
        // crashes (a missing `down` property on the pump). This is exactly how Catnip renders its own ghosts.
        BakedModelBufferer.bufferModel(model, pos, EmptyVirtualBlockGetter.FULL_BRIGHT, state, ms, (layer, shade) -> vb);
        renderKineticExtras(ms, state, raw, alpha);
        ms.popPose();

        buffer.draw();
    }

    /**
     * A block's baked model omits the kinetic parts its block-entity renderer draws each frame, so the
     * ghost of one would be missing them. Add them at rest — the pump's cogwheel is the conspicuous case.
     */
    private static void renderKineticExtras(PoseStack ms, BlockState state, VertexConsumer consumer, float alpha) {
        if (state.getBlock() instanceof PumpBlock) {
            CachedBuffers.partialFacing(AllPartialModels.MECHANICAL_PUMP_COG, state)
                    .color(255, 255, 255, (int) (alpha * 255))
                    .light(LightTexture.FULL_BRIGHT)
                    .renderInto(ms, consumer);
        }
    }
}
