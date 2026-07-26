package de.devin.pipesnphysics.client.ponder;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllItems;
import com.simibubi.create.foundation.gui.RemovedGuiUtils;
import net.createmod.catnip.gui.element.BoxElement;
import net.createmod.catnip.gui.element.GuiGameElement;
import net.createmod.catnip.theme.Color;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.element.AnimatedOverlayElementBase;
import net.createmod.ponder.foundation.instruction.FadeInOutInstruction;
import net.createmod.ponder.foundation.ui.PonderUI;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * A mock of the in-game goggle overlay rendered inside a ponder scene: the same vanilla-style
 * tooltip box Create's GoggleOverlayRenderer draws (translucent background, gradient border, the
 * goggles item perched on the top edge), anchored to a scene position. Scenes use it to show
 * EXACTLY what the player would read through goggles instead of paraphrasing it in narration.
 */
public class GoggleTooltipElement extends AnimatedOverlayElementBase {
    private final Vec3 anchor;
    private final List<Component> lines;

    public GoggleTooltipElement(Vec3 anchor, List<Component> lines) {
        this.anchor = anchor;
        this.lines = lines;
    }

    @Override
    public void render(PonderScene scene, PonderUI screen, GuiGraphics graphics, float partialTicks, float fade) {
        if (fade < 1 / 16f) return;
        Vec2 sceneToScreen = scene.getTransform().sceneToScreen(anchor, partialTicks);
        Font font = screen.getFontRenderer();

        int textWidth = 0;
        for (Component line : lines) textWidth = Math.max(textWidth, font.width(line));
        int posX = Math.min((int) sceneToScreen.x + 20, screen.width - textWidth - 20);
        int posY = Math.max(24, Math.min((int) sceneToScreen.y - 10, screen.height - 40));

        Color background = BoxElement.COLOR_VANILLA_BACKGROUND.copy().scaleAlpha(0.75f * fade);
        Color borderTop = BoxElement.COLOR_VANILLA_BORDER.getFirst().copy().scaleAlpha(fade);
        Color borderBot = BoxElement.COLOR_VANILLA_BORDER.getSecond().copy().scaleAlpha(fade);

        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(0, 0, 400);
        GuiGameElement.of(AllItems.GOGGLES.asStack())
                .at(posX + 10, posY - 16, 450)
                .render(graphics);
        RemovedGuiUtils.drawHoveringText(graphics, lines, posX, posY, screen.width, screen.height, -1,
                background.getRGB(), borderTop.getRGB(), borderBot.getRGB(), font);
        pose.popPose();
    }

    /** Shows the tooltip for a duration with the same fade the built-in text windows use. */
    public static class Instruction extends FadeInOutInstruction {
        private final GoggleTooltipElement element;

        public Instruction(GoggleTooltipElement element, int duration) {
            super(duration);
            this.element = element;
        }

        @Override
        protected void show(PonderScene scene) {
            scene.addElement(element);
            element.setVisible(true);
        }

        @Override
        protected void hide(PonderScene scene) {
            element.setVisible(false);
        }

        @Override
        protected void applyFade(PonderScene scene, float fade) {
            element.setFade(fade);
        }
    }
}
