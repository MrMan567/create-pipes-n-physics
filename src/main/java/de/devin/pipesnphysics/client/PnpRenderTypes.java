package de.devin.pipesnphysics.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

/**
 * Custom render types. Extends {@link RenderStateShard} only to reach its {@code protected} state
 * shards — the same trick Create's own {@code RenderTypes} uses.
 */
public final class PnpRenderTypes extends RenderStateShard {
    /**
     * The one-way valve direction arrows — a translucent overlay drawn ON TOP of everything (no depth
     * test, no depth write). The arrows and the in-pipe fluid share the pipe's space, so a
     * depth-WRITING arrow hid the fluid behind it, while a depth-TESTED arrow gets hidden by the
     * fluid's own near face. Since the arrows are a brief goggle-only hint, drawing them as a clean
     * overlay (both the fluid AND the arrows visible) is the right trade — they show through the
     * fluid/pipe, so the renderer draws them on a stage AFTER the fluid.
     */
    public static final RenderType ARROWS = RenderType.create(
            "pipesnphysics:range_arrows",
            DefaultVertexFormat.BLOCK, VertexFormat.Mode.QUADS, 256, true, true,
            RenderType.CompositeState.builder()
                    .setShaderState(RENDERTYPE_TRANSLUCENT_SHADER)
                    .setTextureState(BLOCK_SHEET_MIPPED)
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setLightmapState(LIGHTMAP)
                    .setDepthTestState(NO_DEPTH_TEST)
                    .setWriteMaskState(COLOR_WRITE)
                    .createCompositeState(true));

    /**
     * The pump reach tint — each reachable pipe's OWN baked model re-drawn a hair larger and
     * colored, so the pipe itself reads as painted (elbows, connection stubs and encasing all
     * follow, which no hand-built shell can). Textured like the block sheet it samples, and
     * DEPTH-TESTED (writing no depth) so terrain in front still occludes it; the swell is what
     * keeps it off the pipe's own faces instead of z-fighting them.
     */
    public static final RenderType REACH_TINT = RenderType.create(
            "pipesnphysics:reach_tint",
            DefaultVertexFormat.BLOCK, VertexFormat.Mode.QUADS, 2048, false, true,
            RenderType.CompositeState.builder()
                    .setShaderState(RENDERTYPE_TRANSLUCENT_SHADER)
                    .setTextureState(BLOCK_SHEET_MIPPED)
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setLightmapState(LIGHTMAP)
                    .setWriteMaskState(COLOR_WRITE)
                    .createCompositeState(true));

    private PnpRenderTypes() {
        super(null, null, null);
    }
}
