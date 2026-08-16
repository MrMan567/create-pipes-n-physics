package de.devin.pipesnphysics.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.foundation.gui.AllIcons;
import de.devin.pipesnphysics.PipesNPhysics;
import net.createmod.catnip.theme.Color;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

/**
 * The mod's own value-box icons, drawn from {@code assets/pipesnphysics/icon/atlas.png} (4x4 cells
 * of 16px).
 *
 * It EXTENDS {@link AllIcons} rather than standing beside it because Create's
 * {@code INamedIconOptions.getIcon()} returns that concrete class — a scroll option cannot hand
 * back any other icon type. The atlas and its size are static there, so every draw path has to be
 * overridden to point at ours; {@code iconX}/{@code iconY} are private in the superclass, hence the
 * second copy here. Everything else (the stencil element, the value box plumbing) works unchanged
 * because it goes through these methods.
 */
public class PnpIcons extends AllIcons {
    public static final ResourceLocation ATLAS = PipesNPhysics.asResource("icon/atlas.png");
    public static final int ATLAS_SIZE = 64;

    /** Flow driven along a pipe — a pump doing its normal job. */
    public static final PnpIcons I_PUMP = new PnpIcons(0, 0);
    /** Rotation coming back out — the same machine run backwards. */
    public static final PnpIcons I_TURBINE = new PnpIcons(1, 0);

    private final int iconX;
    private final int iconY;

    private PnpIcons(int x, int y) {
        super(x, y);
        this.iconX = x * 16;
        this.iconY = y * 16;
    }

    @OnlyIn(Dist.CLIENT)
    @Override
    public void bind() {
        RenderSystem.setShaderTexture(0, ATLAS);
    }

    @OnlyIn(Dist.CLIENT)
    @Override
    public void render(GuiGraphics graphics, int x, int y) {
        graphics.blit(ATLAS, x, y, 0, iconX, iconY, 16, 16, ATLAS_SIZE, ATLAS_SIZE);
    }

    @OnlyIn(Dist.CLIENT)
    @Override
    public void render(PoseStack ms, MultiBufferSource buffer, int color) {
        VertexConsumer builder = buffer.getBuffer(RenderType.text(ATLAS));
        Matrix4f matrix = ms.last().pose();
        Color rgb = new Color(color);

        float u1 = iconX * 1f / ATLAS_SIZE;
        float u2 = (iconX + 16) * 1f / ATLAS_SIZE;
        float v1 = iconY * 1f / ATLAS_SIZE;
        float v2 = (iconY + 16) * 1f / ATLAS_SIZE;

        vertex(builder, matrix, 0, 0, rgb, u1, v1);
        vertex(builder, matrix, 0, 1, rgb, u1, v2);
        vertex(builder, matrix, 1, 1, rgb, u2, v2);
        vertex(builder, matrix, 1, 0, rgb, u2, v1);
    }

    @OnlyIn(Dist.CLIENT)
    private static void vertex(VertexConsumer builder, Matrix4f matrix, float x, float y,
                               Color rgb, float u, float v) {
        builder.addVertex(matrix, x, y, 0)
                .setColor(rgb.getRed(), rgb.getGreen(), rgb.getBlue(), 255)
                .setUv(u, v)
                .setLight(LightTexture.FULL_BRIGHT);
    }
}
