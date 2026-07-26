package de.devin.pipesnphysics.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * The shared baked-arrow emitter behind every in-world arrow overlay (the pump range paths,
 * the one-way valve direction): fetches the {@link ClientEvents#ARROW_MODEL}, orients it along
 * a world direction, and emits its quads tinted at a world-space center.
 */
public final class ArrowRender {
    public static final int FULL_BRIGHTNESS = 0xF000F0;

    private ArrowRender() {}

    /** The baked arrow model, or null while it is missing (resource reload). */
    public static BakedModel model(Minecraft mc) {
        BakedModel model = mc.getModelManager().getModel(ClientEvents.ARROW_MODEL);
        return model == null || model == mc.getModelManager().getMissingModel() ? null : model;
    }

    /** Emit the arrow model centered at world-space (x, y, z), pointing {@code dir}, tinted. */
    public static void emit(PoseStack poseStack, Minecraft mc, VertexConsumer consumer,
                            BakedModel model, Vec3 camera, double x, double y, double z,
                            Direction dir, float r, float g, float b, float alpha) {
        poseStack.pushPose();
        poseStack.translate(x - camera.x - 0.5, y - camera.y - 0.5, z - camera.z - 0.5);
        poseStack.translate(0.5, 0.5, 0.5);
        applyDirectionRotation(poseStack, dir);
        poseStack.translate(-0.5, -0.5, -0.5);

        for (BakedQuad quad : model.getQuads(null, null, mc.level.random)) {
            consumer.putBulkData(poseStack.last(), quad, r, g, b, alpha,
                    FULL_BRIGHTNESS, OverlayTexture.NO_OVERLAY);
        }
        poseStack.popPose();
    }

    private static void applyDirectionRotation(PoseStack poseStack, Direction dir) {
        switch (dir) {
            case NORTH -> poseStack.mulPose(Axis.YP.rotationDegrees(180));
            case WEST -> poseStack.mulPose(Axis.YP.rotationDegrees(-90));
            case EAST -> poseStack.mulPose(Axis.YP.rotationDegrees(90));
            case UP -> poseStack.mulPose(Axis.XP.rotationDegrees(-90));
            case DOWN -> poseStack.mulPose(Axis.XP.rotationDegrees(90));
            default -> {}
        }
    }
}
